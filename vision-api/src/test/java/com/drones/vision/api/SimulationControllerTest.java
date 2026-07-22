package com.drones.vision.api;

import com.drones.vision.application.SimulatedAsset;
import com.drones.vision.application.SimulationService;
import com.drones.vision.application.SimulationSpec;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.StreamPublisherPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SimulationControllerTest {

    private SimulationService simulationService;
    private StreamPublisherPort streamPublisherPort;
    private MockMvc mockMvc;

    private final UserId ownerId = UserId.random();
    private final Ownership ownership = new Ownership(ownerId, GroupId.random());
    private final CurrentUser currentUser = new CurrentUser(ownership);

    @BeforeEach
    void setUp() {
        simulationService = mock(SimulationService.class);
        streamPublisherPort = mock(StreamPublisherPort.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new SimulationController(simulationService, currentUser, streamPublisherPort))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    // ---- happy paths ----

    @Test
    void simulateReturns201WithAssetIdStreamIdAndViewUrlWhenAutoStarted() throws Exception {
        AssetId assetId = AssetId.random();
        StreamId streamId = StreamId.random();
        when(simulationService.simulate(any(), eq(ownership), eq(ownerId)))
                .thenReturn(new SimulatedAsset(assetId, streamId));
        when(streamPublisherPort.viewUrl(streamId))
                .thenReturn(Optional.of(URI.create("/hls/" + streamId.value() + "/index.m3u8")));

        String body = """
                {"videoPath":"/data/clips/drone.mp4"}
                """;

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assetId").value(assetId.value().toString()))
                .andExpect(jsonPath("$.streamId").value(streamId.value().toString()))
                .andExpect(jsonPath("$.viewUrl").value("/hls/" + streamId.value() + "/index.m3u8"));

        ArgumentCaptor<SimulationSpec> captor = ArgumentCaptor.forClass(SimulationSpec.class);
        verify(simulationService).simulate(captor.capture(), eq(ownership), eq(ownerId));
        SimulationSpec spec = captor.getValue();
        assertEquals("/data/clips/drone.mp4", spec.videoPath());
        assertEquals(true, spec.autoStart(), "autoStart must default to true when absent from the request");
    }

    @Test
    void simulateDefaultsAutoStartToTrueWhenFieldIsAbsent() throws Exception {
        when(simulationService.simulate(any(), eq(ownership), eq(ownerId)))
                .thenReturn(new SimulatedAsset(AssetId.random(), StreamId.random()));

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"videoPath\":\"/data/clips/drone.mp4\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<SimulationSpec> captor = ArgumentCaptor.forClass(SimulationSpec.class);
        verify(simulationService).simulate(captor.capture(), any(), any());
        assertEquals(true, captor.getValue().autoStart());
    }

    @Test
    void simulateHonorsExplicitAutoStartFalseAndOmitsStreamIdAndViewUrl() throws Exception {
        AssetId assetId = AssetId.random();
        when(simulationService.simulate(any(), eq(ownership), eq(ownerId)))
                .thenReturn(new SimulatedAsset(assetId, null));

        String body = """
                {"videoPath":"/data/clips/drone.mp4","autoStart":false}
                """;

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assetId").value(assetId.value().toString()))
                .andExpect(jsonPath("$.streamId").doesNotExist())
                .andExpect(jsonPath("$.viewUrl").doesNotExist());

        ArgumentCaptor<SimulationSpec> captor = ArgumentCaptor.forClass(SimulationSpec.class);
        verify(simulationService).simulate(captor.capture(), any(), any());
        assertEquals(false, captor.getValue().autoStart());
        verifyNoInteractions(streamPublisherPort);
    }

    @Test
    void simulatePassesThroughDisplayNameAndHomePoint() throws Exception {
        when(simulationService.simulate(any(), eq(ownership), eq(ownerId)))
                .thenReturn(new SimulatedAsset(AssetId.random(), StreamId.random()));
        when(streamPublisherPort.viewUrl(any())).thenReturn(Optional.empty());

        String body = """
                {"displayName":"My Drone","videoPath":"/data/clips/drone.mp4","latitude":50.45,"longitude":30.52}
                """;

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.viewUrl").doesNotExist());

        ArgumentCaptor<SimulationSpec> captor = ArgumentCaptor.forClass(SimulationSpec.class);
        verify(simulationService).simulate(captor.capture(), any(), any());
        SimulationSpec spec = captor.getValue();
        assertEquals("My Drone", spec.displayName());
        assertEquals(50.45, spec.latitude());
        assertEquals(30.52, spec.longitude());
    }

    // ---- validation / error mapping ----

    @Test
    void simulateReturns400ForBlankVideoPath() throws Exception {
        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"videoPath\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verifyNoInteractions(simulationService);
    }

    @Test
    void simulateReturns400ForMissingVideoPath() throws Exception {
        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verifyNoInteractions(simulationService);
    }

    @Test
    void simulateReturns400WhenServiceRejectsANonExistentPath() throws Exception {
        when(simulationService.simulate(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Video file does not exist: /no/such/file.mp4"));

        String body = """
                {"videoPath":"/no/such/file.mp4"}
                """;

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Video file does not exist: /no/such/file.mp4"));
    }

    @Test
    void simulateReturns409WhenSimulatedCategoryIsNotSeeded() throws Exception {
        when(simulationService.simulate(any(), any(), any()))
                .thenThrow(new IllegalStateException("category 'simulated' is not seeded"));

        String body = """
                {"videoPath":"/data/clips/drone.mp4"}
                """;

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("category 'simulated' is not seeded"));
    }

    @Test
    void simulateReturns201WithoutViewUrlWhenPublisherHasNone() throws Exception {
        StreamId streamId = StreamId.random();
        when(simulationService.simulate(any(), any(), any()))
                .thenReturn(new SimulatedAsset(AssetId.random(), streamId));
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        String body = """
                {"videoPath":"/data/clips/drone.mp4"}
                """;

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.streamId").value(streamId.value().toString()))
                .andExpect(jsonPath("$.viewUrl").doesNotExist());
    }
}
