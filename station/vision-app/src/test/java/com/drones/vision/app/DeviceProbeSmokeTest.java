package com.drones.vision.app;

import com.jayway.jsonpath.JsonPath;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end smoke test for CONTRACT 1 (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3, UX-DESIGN.md §5.1):
 * {@code POST /api/devices/probe} must actually resolve a real {@code VideoSourcePort} adapter,
 * grab a real frame, and encode it — over the full production wiring ({@link WiringConfiguration},
 * no test doubles needed since a probe never touches {@code StreamPublisherPort} at all), for both
 * a zero-hardware {@code sim} source and a real (tiny, synthetic) video file through the {@code
 * file}-protocol FFmpeg ingest path — the same two sources {@link FileSimulationSmokeTest}/{@link
 * SimStreamSmokeTest} already prove for streaming, now proven for probing.
 *
 * <p>{@link MockMvc} is built by hand from the autowired {@link WebApplicationContext} (no {@code
 * @AutoConfigureMockMvc} on this Spring Boot 4 classpath, see {@link FileSimulationSmokeTest}'s own
 * javadoc), and the test video is generated the same way that class does (duplicated, not shared —
 * same precedent as {@link MjpegSimulationSmokeTest}/{@link RtspSimulationDockerE2ETest}).
 */
@SpringBootTest(properties = "vision.publish.enabled=false")
class DeviceProbeSmokeTest {

    private static final int WIDTH = 64;
    private static final int HEIGHT = 48;
    private static final int FRAME_COUNT = 5;
    private static final double FPS = 20.0;

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void probesASimulatedSourceAndReturnsARealDecodedFrame() throws Exception {
        String responseJson = mockMvc.perform(post("/api/devices/probe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"protocol\":\"sim\",\"uri\":\"sim://probe-smoke-test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.widthPx").value(640))
                .andExpect(jsonPath("$.heightPx").value(480))
                .andExpect(jsonPath("$.codec").value("mjpeg"))
                // adapter-simulation's SimulatedTelemetrySource claims any sim-protocol,
                // TELEMETRY-capable device regardless of URI — see DefaultProbeService's own
                // javadoc for why this is a fair approximation, not a false positive, for `sim`.
                .andExpect(jsonPath("$.telemetryDetected").value(true))
                .andExpect(jsonPath("$.frameJpegBase64").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String frameJpegBase64 = JsonPath.read(responseJson, "$.frameJpegBase64");
        assertTrue(Base64.getDecoder().decode(frameJpegBase64).length > 0, "expected non-empty decoded JPEG bytes");
    }

    @Test
    void probesARealVideoFileThroughTheFfmpegIngestPath(@TempDir Path tempDir) throws Exception {
        Path videoFile = createTestVideo(tempDir, WIDTH, HEIGHT, FRAME_COUNT, FPS);
        String uri = videoFile.toUri().toString();

        mockMvc.perform(post("/api/devices/probe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"protocol\":\"file\",\"uri\":" + jsonQuote(uri) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.widthPx").value(WIDTH))
                .andExpect(jsonPath("$.heightPx").value(HEIGHT))
                // BGR24 (FfmpegVideoSource's decoded output) carries no wire-codec memory — see
                // DefaultProbeService#codecFor's own javadoc for why this is an honest absence.
                .andExpect(jsonPath("$.codec").doesNotExist())
                // no TelemetrySourcePort claims the "file" protocol at all.
                .andExpect(jsonPath("$.telemetryDetected").value(false))
                .andExpect(jsonPath("$.warnings[0]").value("No telemetry detected — OSD unavailable"))
                .andExpect(jsonPath("$.frameJpegBase64").isNotEmpty());
    }

    @Test
    void probeReturns400ForAnUnrecognizedProtocol() throws Exception {
        mockMvc.perform(post("/api/devices/probe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"protocol\":\"bogus\",\"uri\":\"bogus://nowhere\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    private static String jsonQuote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Path createTestVideo(Path dir, int width, int height, int frameCount, double fps)
            throws Exception {
        Path file = dir.resolve("probe-smoke-test.mp4");
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
