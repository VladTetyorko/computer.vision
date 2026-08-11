package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.application.device.ProbeFailedException;
import com.drones.vision.application.device.ProbeResult;
import com.drones.vision.application.device.ProbeService;
import com.drones.vision.application.stream.UnsupportedProtocolException;
import com.drones.vision.perception.domain.model.PixelFormat;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.VideoFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import com.drones.vision.api.support.SnapshotJpegEncoder;
import com.drones.vision.api.support.VisionApiProperties;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeviceProbeControllerTest {

    private ProbeService probeService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        probeService = mock(ProbeService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DeviceProbeController(probeService,
                        new SnapshotJpegEncoder(VisionApiProperties.defaults())))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /** Small enough (&le;480px) and already {@link PixelFormat#JPEG} — {@code SnapshotJpegEncoder}
     *  returns these bytes untouched, so the test never needs a real JPEG. */
    private static VideoFrame smallJpegFrame(int width, int height, byte[] payload) {
        return new VideoFrame(StreamId.random(), 0L, Instant.now(), width, height, PixelFormat.JPEG,
                ByteBuffer.wrap(payload));
    }

    @Test
    void probeReturns200WithTheMappedShapeOnSuccess() throws Exception {
        byte[] payload = {1, 2, 3, 4};
        VideoFrame frame = smallJpegFrame(320, 240, payload);
        ProbeResult result = new ProbeResult(frame, "mjpeg", 24, true, List.of());
        when(probeService.probe(any())).thenReturn(result);

        String body = """
                {"protocol":"sim","uri":"sim://cam"}
                """;

        mockMvc.perform(post("/api/devices/probe").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.widthPx").value(320))
                .andExpect(jsonPath("$.heightPx").value(240))
                .andExpect(jsonPath("$.codec").value("mjpeg"))
                .andExpect(jsonPath("$.fps").value(24))
                .andExpect(jsonPath("$.telemetryDetected").value(true))
                .andExpect(jsonPath("$.frameJpegBase64").value(Base64.getEncoder().encodeToString(payload)))
                .andExpect(jsonPath("$.warnings", org.hamcrest.Matchers.empty()));
    }

    @Test
    void probeOmitsCodecAndFpsWhenAbsent() throws Exception {
        VideoFrame frame = smallJpegFrame(320, 240, new byte[]{9});
        ProbeResult result = new ProbeResult(frame, null, null, false, List.of("No telemetry detected — OSD unavailable"));
        when(probeService.probe(any())).thenReturn(result);

        mockMvc.perform(post("/api/devices/probe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"protocol\":\"rtsp\",\"uri\":\"rtsp://cam\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codec").doesNotExist())
                .andExpect(jsonPath("$.fps").doesNotExist())
                .andExpect(jsonPath("$.warnings[0]").value("No telemetry detected — OSD unavailable"));
    }

    @Test
    void probeReturns400ForABlankProtocol() throws Exception {
        mockMvc.perform(post("/api/devices/probe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"protocol\":\"\",\"uri\":\"rtsp://cam\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verifyNoInteractions(probeService);
    }

    @Test
    void probeReturns400ForAMalformedUri() throws Exception {
        mockMvc.perform(post("/api/devices/probe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"protocol\":\"rtsp\",\"uri\":\"::not a uri::\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void probeReturns400ForAnUnrecognizedProtocol() throws Exception {
        when(probeService.probe(any())).thenThrow(new UnsupportedProtocolException("bogus"));

        mockMvc.perform(post("/api/devices/probe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"protocol\":\"bogus\",\"uri\":\"bogus://cam\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void probeReturns422WithTheSpecificMessageOnFailure() throws Exception {
        when(probeService.probe(any()))
                .thenThrow(new ProbeFailedException("RTSP 401 — camera rejected the password"));

        mockMvc.perform(post("/api/devices/probe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"protocol\":\"rtsp\",\"uri\":\"rtsp://cam:554/x\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"))
                .andExpect(jsonPath("$.message").value("RTSP 401 — camera rejected the password"));
    }
}
