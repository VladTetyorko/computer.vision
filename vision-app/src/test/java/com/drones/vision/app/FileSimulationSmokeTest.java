package com.drones.vision.app;

import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.AssetUsage;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.Telemetry;
import com.drones.vision.domain.model.UsageId;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.domain.port.out.AssetUsageRepositoryPort;
import com.drones.vision.domain.port.out.StreamPublisherPort;
import com.drones.vision.domain.port.out.TelemetryRepositoryPort;
import com.jayway.jsonpath.JsonPath;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end smoke test for docs/CYCLES-PLAN.md §1c: {@code POST /api/simulations} with a real
 * (tiny, synthetic) video file must flow frames through the exact same {@code "file"}-protocol
 * FFmpeg ingest path a real drone simulation would use, open a usage, accumulate telemetry from
 * the paired {@code sim} telemetry device, and close cleanly on stop — all through the real HTTP
 * endpoint, over the full production wiring from {@link WiringConfiguration} (only {@link
 * StreamPublisherPort} swapped for a frame-recording test double), mirroring {@link
 * SimStreamSmokeTest}'s approach but exercised via {@link MockMvc} instead of calling use-case
 * beans directly, since this is specifically testing the wired {@code SimulationController}.
 *
 * <p>The video is generated on the fly with JavaCV's {@code FFmpegFrameRecorder} (same technique
 * as adapter-rtsp's {@code FfmpegVideoSourceTest}) — vision-app depends on adapter-rtsp, so the
 * native FFmpeg libraries are already on this module's test classpath. The first FFmpeg-touching
 * test in a fresh module pays real cost for native library extraction, hence the generous {@link
 * #AWAIT_SECONDS}.
 *
 * <p>{@link MockMvc} is built by hand from the autowired {@link WebApplicationContext} (rather
 * than {@code @AutoConfigureMockMvc}, whose web-mvc test-autoconfiguration support isn't on this
 * Spring Boot 4 classpath here) — same {@code webAppContextSetup} technique, just assembled
 * explicitly.
 */
@SpringBootTest(properties = "vision.publish.enabled=false")
@Import(FileSimulationSmokeTest.RecordingPublisherConfig.class)
class FileSimulationSmokeTest {

    private static final int WIDTH = 64;
    private static final int HEIGHT = 48;
    private static final int FRAME_COUNT = 20;
    private static final double FPS = 20.0; // ~1s of synthetic video

    // Generous: the first FFmpeg-touching test in this module pays for native lib extraction.
    private static final long AWAIT_SECONDS = 60;
    private static final Duration TELEMETRY_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private AssetUsageRepositoryPort assetUsageRepositoryPort;

    @Autowired
    private TelemetryRepositoryPort telemetryRepositoryPort;

    @Autowired
    private RecordingStreamPublisher recordingStreamPublisher;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void postSimulationsStreamsFramesAccumulatesTelemetryThenStopClosesTheUsage(@TempDir Path tempDir)
            throws Exception {
        Path videoFile = createTestVideo(tempDir, WIDTH, HEIGHT, FRAME_COUNT, FPS);

        String requestBody = "{\"videoPath\":" + jsonQuote(videoFile.toString()) + "}";

        String responseJson = mockMvc.perform(post("/api/simulations")
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assetId").exists())
                .andExpect(jsonPath("$.streamId").exists())
                .andReturn().getResponse().getContentAsString();

        AssetId assetId = AssetId.of(JsonPath.read(responseJson, "$.assetId"));
        StreamId streamId = StreamId.of(JsonPath.read(responseJson, "$.streamId"));

        try {
            boolean receivedFrame = recordingStreamPublisher.awaitFirstFrame(AWAIT_SECONDS, TimeUnit.SECONDS);
            assertTrue(receivedFrame, "expected at least one frame to reach StreamPublisherPort within "
                    + AWAIT_SECONDS + "s");
            assertFalse(recordingStreamPublisher.frames().isEmpty());
            assertTrue(recordingStreamPublisher.frames().stream().allMatch(f -> f.streamId().equals(streamId)));

            AssetUsage openUsage = awaitOpenUsage(assetId);
            assertNotNull(openUsage, "expected a usage to open once the simulated asset started streaming");

            List<Telemetry> samples = awaitAtLeastTwoTelemetrySamples(openUsage.id());
            assertTrue(samples.size() >= 2, "expected >=2 telemetry samples within " + TELEMETRY_TIMEOUT
                    + " of the sim telemetry source's 1Hz cadence, got " + samples.size());
        } finally {
            mockMvc.perform(delete("/api/assets/{id}/stream", assetId.value()))
                    .andExpect(status().isNoContent());
        }

        AssetUsage closedUsage = assetUsageRepositoryPort.findRecentByAsset(assetId, 1).stream().findFirst()
                .orElseThrow(() -> new AssertionError("expected a persisted usage after stopping the stream"));
        assertNotNull(closedUsage.endedAt(), "usage must be closed after stopping the simulated asset's stream");
    }

    private AssetUsage awaitOpenUsage(AssetId assetId) throws InterruptedException {
        Instant deadline = Instant.now().plus(TELEMETRY_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            var open = assetUsageRepositoryPort.findOpenByAsset(assetId);
            if (open.isPresent()) {
                return open.get();
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        return null;
    }

    private List<Telemetry> awaitAtLeastTwoTelemetrySamples(UsageId usageId) throws InterruptedException {
        Instant deadline = Instant.now().plus(TELEMETRY_TIMEOUT);
        List<Telemetry> samples = List.of();
        while (Instant.now().isBefore(deadline)) {
            samples = telemetryRepositoryPort.findByUsage(usageId, 100);
            if (samples.size() >= 2) {
                return samples;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        return samples;
    }

    private static String jsonQuote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Path createTestVideo(Path dir, int width, int height, int frameCount, double fps)
            throws Exception {
        Path file = dir.resolve("simulation-smoke-test.mp4");
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(file.toFile(), width, height)) {
            recorder.setFormat("mp4");
            recorder.setFrameRate(fps);
            recorder.start();
            for (int i = 0; i < frameCount; i++) {
                recorder.record(solidFrame(width, height, i));
            }
        }
        return file;
    }

    /** A single-color synthetic BGR frame, shaded by frame index, for the test-only recorder. */
    private static Frame solidFrame(int width, int height, int frameIndex) {
        int channels = 3;
        int stride = width * channels;
        ByteBuffer buffer = ByteBuffer.allocateDirect(stride * height);
        byte value = (byte) (frameIndex * 17);
        for (int i = 0; i < buffer.capacity(); i++) {
            buffer.put(value);
        }
        buffer.rewind();

        Frame frame = new Frame();
        frame.imageWidth = width;
        frame.imageHeight = height;
        frame.imageDepth = Frame.DEPTH_UBYTE;
        frame.imageChannels = channels;
        frame.imageStride = stride;
        frame.image = new Buffer[] {buffer};
        return frame;
    }

    @TestConfiguration
    static class RecordingPublisherConfig {

        @Bean
        @Primary
        RecordingStreamPublisher recordingStreamPublisher() {
            return new RecordingStreamPublisher();
        }
    }

    /** Test-only {@link StreamPublisherPort} that records published frames instead of discarding them. */
    static final class RecordingStreamPublisher implements StreamPublisherPort {

        private final List<VideoFrame> frames = new CopyOnWriteArrayList<>();
        private final CountDownLatch firstFrame = new CountDownLatch(1);

        @Override
        public void streamStarted(StreamId id, Device device) {
            // no-op
        }

        @Override
        public void publish(StreamId id, VideoFrame frame) {
            frames.add(frame);
            firstFrame.countDown();
        }

        @Override
        public void streamEnded(StreamId id) {
            // no-op
        }

        boolean awaitFirstFrame(long timeout, TimeUnit unit) throws InterruptedException {
            return firstFrame.await(timeout, unit);
        }

        List<VideoFrame> frames() {
            return frames;
        }
    }
}
