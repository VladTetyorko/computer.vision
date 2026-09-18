package com.drones.vision.app;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;
import com.drones.vision.flight.domain.port.TelemetryRepositoryPort;
import com.jayway.jsonpath.JsonPath;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end smoke test for docs/main/CYCLES-PLAN.md §5: {@code POST /api/simulations} with {@code
 * "transport":"mjpeg"} must flow a real (tiny, synthetic) video file through the mjpeg TX/RX pair
 * — {@code MjpegFeedTransmitter} (TX) serves it as an HTTP {@code multipart/x-mixed-replace}
 * stream on its own ephemeral {@code 127.0.0.1} port, {@code MjpegVideoSource} (RX) ingests it
 * back over that same HTTP connection — through the actual production wiring from {@link
 * WiringConfiguration} (only {@link com.drones.vision.perception.domain.port.StreamPublisherPort} swapped
 * for a frame-recording test double, same technique as {@link FileSimulationSmokeTest}, whose
 * {@link FileSimulationSmokeTest.RecordingPublisherConfig}/{@link
 * FileSimulationSmokeTest.RecordingStreamPublisher} are reused here rather than duplicated).
 *
 * <p>Unlike {@link com.drones.vision.app.RtspSimulationDockerE2ETest} (which needs a real
 * mediamtx container for the RTSP wire), this test needs <b>no docker</b>: {@code
 * MjpegFeedTransmitter} serves its own in-process HTTP server, so the TX/RX round trip happens
 * entirely within this JVM — the same property adapter-mjpeg's own {@code
 * MjpegRoundTripIntegrationTest} already proves at the adapter level; this test proves it again
 * through the full {@code POST /api/simulations} -&gt; wiring -&gt; pipeline path. No RTSP-style
 * fixed "feed establish" delay is needed either (see {@code DefaultSimulationService}'s Gotchas):
 * {@code MjpegFeedTransmitter#start} only returns once its HTTP context is already registered on
 * an already-listening server, so the RX side's very first connection attempt succeeds.
 *
 * <p>{@code DELETE /api/simulations/{assetId}} (not {@code DELETE /api/assets/{id}/stream}, unlike
 * {@link FileSimulationSmokeTest}'s cleanup) is used deliberately: it is the endpoint that also
 * tears down the transmitted feed (see {@code SimulationController#stop}/{@code
 * DefaultSimulationService#stop}), and this test asserts that teardown by polling for no {@code
 * mjpeg-}-prefixed thread left alive afterward (mirrors {@code
 * RtspSimulationDockerE2ETest}'s {@code rtsp-feed-} check; see adapter-mjpeg/MODULE.md's Gotchas
 * for why the per-viewer/per-open thread rename is deliberately load-bearing for exactly this kind
 * of check).
 */
@SpringBootTest(properties = {"vision.publish.enabled=false", "vision.simulation.enabled=true"})
@Import(FileSimulationSmokeTest.RecordingPublisherConfig.class)
class MjpegSimulationSmokeTest {

    private static final int WIDTH = 64;
    private static final int HEIGHT = 48;
    private static final int FRAME_COUNT = 20;
    private static final double FPS = 20.0; // ~1s of synthetic video

    // Generous: the first FFmpeg-touching test in a fresh module run pays for native lib extraction.
    private static final long AWAIT_SECONDS = 60;
    private static final Duration TELEMETRY_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);
    // MjpegVideoSource's close() joins its read thread with a 20s bound and
    // MjpegFeedTransmitter's FeedRegistration#close() joins each viewer thread with a 5s bound
    // (see adapter-mjpeg/MODULE.md) -- give the "no mjpeg- thread left alive" check headroom past
    // the larger of the two.
    private static final Duration THREAD_TEARDOWN_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private AssetUsageRepositoryPort assetUsageRepositoryPort;

    @Autowired
    private TelemetryRepositoryPort telemetryRepositoryPort;

    @Autowired
    private FileSimulationSmokeTest.RecordingStreamPublisher recordingStreamPublisher;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void postSimulationsWithMjpegTransportStreamsFramesThenDeleteStopsStreamAndFeedThreads(@TempDir Path tempDir)
            throws Exception {
        Path videoFile = createTestVideo(tempDir, WIDTH, HEIGHT, FRAME_COUNT, FPS);

        String requestBody =
                "{\"videoPath\":" + jsonQuote(videoFile.toString()) + ",\"transport\":\"mjpeg\"}";

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
                    + AWAIT_SECONDS + "s via the in-process mjpeg TX->RX round trip");
            assertFalse(recordingStreamPublisher.frames().isEmpty());
            assertTrue(recordingStreamPublisher.frames().stream().allMatch(f -> f.streamId().equals(streamId)));

            AssetUsage openUsage = awaitOpenUsage(assetId);
            assertNotNull(openUsage, "expected a usage to open once the simulated asset started streaming");

            List<Telemetry> samples = awaitAtLeastTwoTelemetrySamples(openUsage.id());
            assertTrue(samples.size() >= 2, "expected >=2 telemetry samples within " + TELEMETRY_TIMEOUT
                    + " of the sim telemetry source's 1Hz cadence, got " + samples.size());
        } finally {
            mockMvc.perform(delete("/api/simulations/{assetId}", assetId.value()))
                    .andExpect(status().isNoContent());
        }

        AssetUsage closedUsage = assetUsageRepositoryPort.findRecentByAsset(assetId, 1).stream().findFirst()
                .orElseThrow(() -> new AssertionError("expected a persisted usage after stopping the simulation"));
        assertNotNull(closedUsage.endedAt(), "usage must be closed after DELETE /api/simulations/{assetId}");

        awaitNoMjpegThreadsAlive(THREAD_TEARDOWN_TIMEOUT);
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

    /**
     * Polls {@link Thread#getAllStackTraces()} until no live thread's name starts with {@code
     * "mjpeg-"} (the prefix both {@code MjpegVideoSource}'s read thread and {@code
     * MjpegFeedTransmitter}'s per-viewer thread rename use — see adapter-mjpeg/MODULE.md) or the
     * timeout elapses -- the test-form proof that {@code DELETE /api/simulations/{assetId}} really
     * tears down both the RX and TX sides, not just the stream.
     */
    private static void awaitNoMjpegThreadsAlive(Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().startsWith("mjpeg-"))) {
                return;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        Set<String> stillAlive = Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(name -> name.startsWith("mjpeg-"))
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(stillAlive.isEmpty(),
                "expected no mjpeg- threads alive " + timeout + " after stopping the simulation, found: "
                        + stillAlive);
    }

    private static String jsonQuote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Path createTestVideo(Path dir, int width, int height, int frameCount, double fps)
            throws Exception {
        Path file = dir.resolve("mjpeg-simulation-smoke-test.mp4");
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
}
