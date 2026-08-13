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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end smoke test for docs/main/CYCLES-PLAN.md §3: {@code POST /api/simulations} with {@code
 * "transport":"rtsp"} must flow a real (tiny, synthetic) video file through the full TX/RX
 * doctrine — {@code RtspFeedTransmitter} (TX) pushes it to a real <a
 * href="https://github.com/bluenviron/mediamtx">mediamtx</a> container, {@code FfmpegVideoSource}
 * (RX) ingests it back over the wire — through the actual production wiring from {@link
 * WiringConfiguration} (only {@link com.drones.vision.perception.domain.port.StreamPublisherPort} swapped
 * for a frame-recording test double, same technique as {@link FileSimulationSmokeTest}, whose
 * {@link FileSimulationSmokeTest.RecordingPublisherConfig}/{@link
 * FileSimulationSmokeTest.RecordingStreamPublisher} are reused here rather than duplicated — both
 * are package-private nested types in this same {@code com.drones.vision.app} package).
 *
 * <p>Unlike {@link FileSimulationSmokeTest} (which needs no docker), this test needs a real
 * mediamtx instance for the RTSP wire, so it is docker-gated exactly like adapter-rtsp's own
 * {@code MediamtxDockerIntegrationTest}/adapter-publish-hls's {@code MediamtxDockerIntegrationTest}:
 * skips cleanly (not a failure) whenever the {@code docker} CLI isn't usable. The container is
 * started once in a static {@link BeforeAll} (which — because {@code @EnabledIf} gates the whole
 * class, including {@code @BeforeAll} — never runs at all when docker is unavailable) and its
 * randomized host RTSP port is fed into {@link VisionPublishProperties.Mediamtx#rtspBase()} via
 * {@link DynamicPropertySource} before the Spring context loads, since {@code
 * WiringConfiguration#rtspFeedTransmitter} reads that same property this app's mediamtx-backed
 * viewer egress would (docs/main/CYCLES-PLAN.md §3: both TX and viewer egress push to the same
 * mediamtx sidecar). {@code vision.publish.enabled=false} keeps viewer egress out of the picture
 * entirely (the recording publisher wins either way), matching {@link FileSimulationSmokeTest}.
 */
@EnabledIf(value = "dockerAvailable", disabledReason = "docker is not available in this environment")
@SpringBootTest(properties = "vision.publish.enabled=false")
@Import(FileSimulationSmokeTest.RecordingPublisherConfig.class)
class RtspSimulationDockerE2ETest {

    private static final String IMAGE = "bluenviron/mediamtx:latest";

    private static final int WIDTH = 64;
    private static final int HEIGHT = 48;
    private static final int FRAME_COUNT = 20;
    private static final double FPS = 20.0; // ~1s of synthetic video

    // Generous throughout, per docs/main/CYCLES-PLAN.md §3's own instruction: this test proves a real
    // encoder -> wire -> demuxer -> pipeline round trip through a real container, on top of the
    // native-library-extraction cost FileSimulationSmokeTest already documents paying once.
    private static final long AWAIT_SECONDS = 90;
    private static final Duration TELEMETRY_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);
    // RtspFeedTransmitter's own close() joins its transmit thread with a 20s bound (see
    // adapter-rtsp/MODULE.md) -- give the "no rtsp-feed- thread left alive" check comfortable
    // headroom past that.
    private static final Duration THREAD_TEARDOWN_TIMEOUT = Duration.ofSeconds(30);

    private static String containerName;
    private static int rtspPort;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private AssetUsageRepositoryPort assetUsageRepositoryPort;

    @Autowired
    private TelemetryRepositoryPort telemetryRepositoryPort;

    @Autowired
    private FileSimulationSmokeTest.RecordingStreamPublisher recordingStreamPublisher;

    private MockMvc mockMvc;

    @BeforeAll
    static void startMediamtx() throws Exception {
        containerName = "vision-sim-rtsp-e2e-" + UUID.randomUUID();
        ProcessResult result = run(Duration.ofSeconds(90),
                "docker", "run", "-d", "--rm", "--name", containerName, "-p", "0:8554", IMAGE);
        if (result.exitCode() != 0) {
            fail("failed to start mediamtx container: " + result.output());
        }
        rtspPort = resolveHostPort(containerName, "8554/tcp");
        awaitTcpPortOpen(rtspPort, Duration.ofSeconds(10));
    }

    @AfterAll
    static void stopMediamtx() {
        if (containerName != null) {
            try {
                run(Duration.ofSeconds(15), "docker", "rm", "-f", containerName);
            } catch (Exception ignored) {
                // best-effort cleanup only
            }
        }
    }

    @DynamicPropertySource
    static void mediamtxProperties(DynamicPropertyRegistry registry) {
        registry.add("vision.publish.mediamtx.rtsp-base", () -> "rtsp://localhost:" + rtspPort);
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void postSimulationsWithRtspTransportRoundTripsThroughMediamtxThenDeleteStopsStreamAndFeedThread(
            @TempDir Path tempDir) throws Exception {
        Path videoFile = createTestVideo(tempDir, WIDTH, HEIGHT, FRAME_COUNT, FPS);

        String requestBody =
                "{\"videoPath\":" + jsonQuote(videoFile.toString()) + ",\"transport\":\"rtsp\"}";

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
                    + AWAIT_SECONDS + "s via the real RTSP TX->mediamtx->RX round trip");
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

        awaitNoRtspFeedThreadsAlive(THREAD_TEARDOWN_TIMEOUT);
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
     * "rtsp-feed-"} (the name {@code RtspFeedTransmitter} gives its transmit thread) or the
     * timeout elapses -- the test-form proof that {@code DELETE /api/simulations/{assetId}} really
     * tears down the TX side, not just the stream.
     */
    private static void awaitNoRtspFeedThreadsAlive(Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().startsWith("rtsp-feed-"))) {
                return;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        Set<String> stillAlive = Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(name -> name.startsWith("rtsp-feed-"))
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(stillAlive.isEmpty(),
                "expected no rtsp-feed- threads alive " + timeout + " after stopping the simulation, found: "
                        + stillAlive);
    }

    private static String jsonQuote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Path createTestVideo(Path dir, int width, int height, int frameCount, double fps)
            throws Exception {
        Path file = dir.resolve("rtsp-simulation-e2e-test.mp4");
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

    /** JUnit {@code @EnabledIf} condition: true iff the {@code docker} CLI can talk to a daemon. */
    static boolean dockerAvailable() {
        try {
            return run(Duration.ofSeconds(5), "docker", "info").exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static int resolveHostPort(String containerName, String containerPort)
            throws IOException, InterruptedException {
        ProcessResult result = run(Duration.ofSeconds(10), "docker", "port", containerName, containerPort);
        if (result.exitCode() != 0) {
            fail("failed to resolve host port for " + containerPort + ": " + result.output());
        }
        List<String> lines = result.output().lines().map(String::trim).filter(s -> !s.isBlank()).toList();
        String chosen = lines.stream().filter(l -> l.startsWith("0.0.0.0:")).findFirst().orElseGet(() -> lines.get(0));
        String portPart = chosen.substring(chosen.lastIndexOf(':') + 1);
        return Integer.parseInt(portPart.trim());
    }

    private static void awaitTcpPortOpen(int port, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", port), 500);
                return;
            } catch (IOException e) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static ProcessResult run(Duration timeout, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "docker-cli-output-reader");
            t.setDaemon(true);
            return t;
        });
        Future<String> outputFuture = executor.submit(
                () -> new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Command timed out after " + timeout + ": " + String.join(" ", command));
            }
            String output;
            try {
                output = outputFuture.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                output = "";
            }
            return new ProcessResult(process.exitValue(), output);
        } finally {
            executor.shutdownNow();
        }
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
