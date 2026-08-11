package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.application.discovery.DiscoveryService;
import com.drones.vision.application.discovery.DiscoveryScanSpec;
import com.drones.vision.application.discovery.DiscoveryScanResult;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.warehouse.domain.model.DiscoveredDevice;
import com.drones.vision.kernel.StreamDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DiscoveryControllerTest {

    private DiscoveryService discoveryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        discoveryService = mock(DiscoveryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DiscoveryController(discoveryService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static DiscoveredDevice fullCandidate() {
        return new DiscoveredDevice("mdns", "front-cam", URI.create("rtsp://192.168.1.10:554"),
                new CategoryId("ip-camera"),
                new StreamDescriptor("rtsp", URI.create("rtsp://192.168.1.10:554/"), Map.of()),
                Map.of("service", "_rtsp._tcp.local."));
    }

    private static DiscoveredDevice minimalCandidate() {
        return new DiscoveredDevice("onvif", "onvif-device", URI.create("http://192.168.1.20"), null, null,
                Map.of());
    }

    @Test
    void scanReturns200WithFullCandidateShapeIncludingFlattenedStream() throws Exception {
        DiscoveryScanResult result =
                new DiscoveryScanResult(List.of(fullCandidate()), Set.of());
        when(discoveryService.scan(any())).thenReturn(result);

        mockMvc.perform(post("/api/discovery/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devices", hasSize(1)))
                .andExpect(jsonPath("$.devices[0].method").value("mdns"))
                .andExpect(jsonPath("$.devices[0].name").value("front-cam"))
                .andExpect(jsonPath("$.devices[0].address").value("rtsp://192.168.1.10:554"))
                .andExpect(jsonPath("$.devices[0].suggestedCategory").value("ip-camera"))
                .andExpect(jsonPath("$.devices[0].protocol").value("rtsp"))
                .andExpect(jsonPath("$.devices[0].uri").value("rtsp://192.168.1.10:554/"))
                .andExpect(jsonPath("$.devices[0].details.service").value("_rtsp._tcp.local."))
                .andExpect(jsonPath("$.failedMethods", hasSize(0)));
    }

    @Test
    void scanReturns200WithMinimalCandidateOmittingAbsentFields() throws Exception {
        DiscoveryScanResult result =
                new DiscoveryScanResult(List.of(minimalCandidate()), Set.of());
        when(discoveryService.scan(any())).thenReturn(result);

        mockMvc.perform(post("/api/discovery/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devices", hasSize(1)))
                .andExpect(jsonPath("$.devices[0].method").value("onvif"))
                .andExpect(jsonPath("$.devices[0].name").value("onvif-device"))
                .andExpect(jsonPath("$.devices[0].address").value("http://192.168.1.20"))
                .andExpect(jsonPath("$.devices[0].suggestedCategory").doesNotExist())
                .andExpect(jsonPath("$.devices[0].protocol").doesNotExist())
                .andExpect(jsonPath("$.devices[0].uri").doesNotExist())
                .andExpect(jsonPath("$.devices[0].details").isEmpty());
    }

    @Test
    void scanUsesScanRequestDefaultsWhenBodyAbsent() throws Exception {
        DiscoveryScanResult result =
                new DiscoveryScanResult(List.of(), Set.of());
        when(discoveryService.scan(any())).thenReturn(result);

        mockMvc.perform(post("/api/discovery/scan"))
                .andExpect(status().isOk());

        ArgumentCaptor<DiscoveryScanSpec> captor =
                ArgumentCaptor.forClass(DiscoveryScanSpec.class);
        verify(discoveryService).scan(captor.capture());
        assertEquals(DiscoveryScanSpec.defaults(), captor.getValue());
    }

    @Test
    void scanUsesScanRequestDefaultsWhenBodyFieldsAbsent() throws Exception {
        DiscoveryScanResult result =
                new DiscoveryScanResult(List.of(), Set.of());
        when(discoveryService.scan(any())).thenReturn(result);

        mockMvc.perform(post("/api/discovery/scan").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        ArgumentCaptor<DiscoveryScanSpec> captor =
                ArgumentCaptor.forClass(DiscoveryScanSpec.class);
        verify(discoveryService).scan(captor.capture());
        assertEquals(DiscoveryScanSpec.defaults(), captor.getValue());
    }

    @Test
    void scanMapsExplicitTimeoutAndMethodsOntoScanRequest() throws Exception {
        DiscoveryScanResult result =
                new DiscoveryScanResult(List.of(), Set.of());
        when(discoveryService.scan(any())).thenReturn(result);

        String body = """
                {"timeoutMs":2000,"methods":["mdns","onvif"]}
                """;

        mockMvc.perform(post("/api/discovery/scan").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<DiscoveryScanSpec> captor =
                ArgumentCaptor.forClass(DiscoveryScanSpec.class);
        verify(discoveryService).scan(captor.capture());
        assertEquals(Duration.ofMillis(2000), captor.getValue().timeout());
        assertEquals(Set.of("mdns", "onvif"), captor.getValue().methods());
    }

    @Test
    void scanReturns400ForUnknownMethod() throws Exception {
        when(discoveryService.scan(any()))
                .thenThrow(new IllegalArgumentException("Unknown discovery method: bogus"));

        String body = """
                {"methods":["bogus"]}
                """;

        mockMvc.perform(post("/api/discovery/scan").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Unknown discovery method: bogus"));
    }

    @Test
    void scanReturns400ForNonPositiveTimeout() throws Exception {
        String body = """
                {"timeoutMs":0}
                """;

        mockMvc.perform(post("/api/discovery/scan").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void scanSerializesFailedMethods() throws Exception {
        DiscoveryScanResult result =
                new DiscoveryScanResult(List.of(), Set.of("v4l2", "onvif"));
        when(discoveryService.scan(any())).thenReturn(result);

        mockMvc.perform(post("/api/discovery/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devices", hasSize(0)))
                .andExpect(jsonPath("$.failedMethods", hasSize(2)));
    }
}
