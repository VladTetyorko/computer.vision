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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
 * End-to-end smoke test for docs/main/CYCLES-PLAN.md §1c: {@code POST /api/simulations} with a real
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

    // --- docs/main/CYCLES-PLAN.md §7 (CT-a): telemetry flight plan ------------------------

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;
    private static final double ROUTE_BASE_LATITUDE = 50.45;
    private static final double ROUTE_BASE_LONGITUDE = 30.52;
    private static final double ROUTE_LEG_METERS = 300.0; // waypoint spacing: wp0 -> wp1 -> wp2
    private static final double ROUTE_SPEED_MPS = 100.0; // covers the ~600m route in exactly 6 real 1Hz ticks
    // 8, not 6: a couple of ticks of margin past the route's exact completion tick, so the last
    // observed sample is reliably holding at the checkpoint (routeMode=once) rather than landing
    // exactly on the completion tick itself.
    private static final int ROUTE_MIN_SAMPLE_COUNT = 8;
    // Generous/CI-safe: this source ticks at the real 1Hz cadence in this module (see Gotchas),
    // so this must comfortably outlast usage-open + several ticks of route progression.
    private static final Duration ROUTE_TELEMETRY_TIMEOUT = Duration.ofSeconds(30);
    private static final double ROUTE_DISTANCE_TREND_TOLERANCE_METERS = 5.0;
    private static final double ROUTE_ARRIVAL_TOLERANCE_METERS = 50.0;

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

    /**
     * docs/main/CYCLES-PLAN.md §7 (CT-a): a 3-waypoint route with a high {@code speedMps} must fly the
     * simulated drone's telemetry along it — successive samples' distance to the final checkpoint
     * must trend toward zero (generous tolerance, since this runs at the real, unthrottled 1Hz
     * cadence — see the class-level Gotcha), and {@code routeMode=once} means it holds there once
     * arrived rather than looping back, keeping the "trending toward zero" assertion robust instead
     * of flaky around whichever tick the poll happens to land on.
     */
    @Test
    void postSimulationsWithARouteFliesTelemetryTowardTheFinalCheckpoint(@TempDir Path tempDir) throws Exception {
        Path videoFile = createTestVideo(tempDir, WIDTH, HEIGHT, FRAME_COUNT, FPS);

        double waypoint1Latitude = northOffsetLatitude(ROUTE_BASE_LATITUDE, ROUTE_LEG_METERS);
        double waypoint2Latitude = northOffsetLatitude(ROUTE_BASE_LATITUDE, ROUTE_LEG_METERS * 2);

        String requestBody = """
                {"videoPath":%s,"telemetry":{"speedMps":%s,"routeMode":"once","route":[
                    {"latitude":%s,"longitude":%s},
                    {"latitude":%s,"longitude":%s},
                    {"latitude":%s,"longitude":%s}
                ]}}
                """.formatted(jsonQuote(videoFile.toString()), jsonNumber(ROUTE_SPEED_MPS),
                jsonNumber(ROUTE_BASE_LATITUDE), jsonNumber(ROUTE_BASE_LONGITUDE),
                jsonNumber(waypoint1Latitude), jsonNumber(ROUTE_BASE_LONGITUDE),
                jsonNumber(waypoint2Latitude), jsonNumber(ROUTE_BASE_LONGITUDE));

        String responseJson = mockMvc.perform(post("/api/simulations")
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assetId").exists())
                .andExpect(jsonPath("$.streamId").exists())
                .andReturn().getResponse().getContentAsString();

        AssetId assetId = AssetId.of(JsonPath.read(responseJson, "$.assetId"));

        try {
            AssetUsage openUsage = awaitOpenUsage(assetId);
            assertNotNull(openUsage, "expected a usage to open once the simulated asset started streaming");

            List<Telemetry> samples =
                    awaitAtLeastNTelemetrySamples(openUsage.id(), ROUTE_MIN_SAMPLE_COUNT, ROUTE_TELEMETRY_TIMEOUT);
            assertTrue(samples.size() >= ROUTE_MIN_SAMPLE_COUNT,
                    "expected >= " + ROUTE_MIN_SAMPLE_COUNT + " telemetry samples flying the configured route "
                            + "within " + ROUTE_TELEMETRY_TIMEOUT + ", got " + samples.size());

            double[] distancesToCheckpoint2 = samples.stream()
                    .mapToDouble(sample -> distanceMeters(
                            sample.latitude(), sample.longitude(), waypoint2Latitude, ROUTE_BASE_LONGITUDE))
                    .toArray();

            for (int i = 1; i < distancesToCheckpoint2.length; i++) {
                assertTrue(distancesToCheckpoint2[i] <= distancesToCheckpoint2[i - 1]
                                + ROUTE_DISTANCE_TREND_TOLERANCE_METERS,
                        "distance to the final checkpoint must trend toward zero across successive samples: "
                                + Arrays.toString(distancesToCheckpoint2));
            }
            double lastDistance = distancesToCheckpoint2[distancesToCheckpoint2.length - 1];
            assertTrue(lastDistance < ROUTE_ARRIVAL_TOLERANCE_METERS,
                    "expected the last sample to have reached (and, routeMode=once, be holding at) the final "
                            + "checkpoint within " + ROUTE_ARRIVAL_TOLERANCE_METERS + "m, was " + lastDistance + "m");
        } finally {
            mockMvc.perform(delete("/api/assets/{id}/stream", assetId.value()))
                    .andExpect(status().isNoContent());
        }
    }

    /** Equirectangular approximation, adequate at this route's scale — same technique adapter-simulation's own tests use. */
    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double meanLatitudeRadians = Math.toRadians((lat1 + lat2) / 2.0);
        double northMeters = Math.toRadians(lat2 - lat1) * EARTH_RADIUS_METERS;
        double eastMeters = Math.toRadians(lon2 - lon1) * EARTH_RADIUS_METERS * Math.cos(meanLatitudeRadians);
        return Math.sqrt(northMeters * northMeters + eastMeters * eastMeters);
    }

    private static double northOffsetLatitude(double baseLatitude, double meters) {
        return baseLatitude + Math.toDegrees(meters / EARTH_RADIUS_METERS);
    }

    private static String jsonNumber(double value) {
        return String.format(Locale.ROOT, "%s", value);
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
        return awaitAtLeastNTelemetrySamples(usageId, 2, TELEMETRY_TIMEOUT);
    }

    private List<Telemetry> awaitAtLeastNTelemetrySamples(UsageId usageId, int minCount, Duration timeout)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        List<Telemetry> samples = List.of();
        while (Instant.now().isBefore(deadline)) {
            samples = telemetryRepositoryPort.findByUsage(usageId, 200);
            if (samples.size() >= minCount) {
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
