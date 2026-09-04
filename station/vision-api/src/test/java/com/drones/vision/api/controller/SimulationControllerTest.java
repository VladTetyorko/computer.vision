package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.simulation.application.RouteMode;
import com.drones.vision.simulation.application.SimulatedAsset;
import com.drones.vision.simulation.application.SimulationService;
import com.drones.vision.simulation.application.SimulationSpec;
import com.drones.vision.simulation.application.SimulationTransport;
import com.drones.vision.simulation.application.TelemetryPlan;
import com.drones.vision.simulation.application.TelemetryTransport;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.platform.Authority;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.map.application.MapAccessPolicy;
import com.drones.vision.api.security.PrincipalResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import com.drones.vision.api.security.CurrentUser;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SimulationControllerTest {

    private SimulationService simulationService;
    private StreamPublisherPort streamPublisherPort;
    /** Backs {@link SimulationController#stop}'s scoped read (docs/plans/done/LIVE-SCOPE-PLAN.md §2, W2). */
    private AssetService assetService;
    private MockMvc mockMvc;

    private final UserId ownerId = UserId.random();
    private final Ownership ownership = new Ownership(ownerId, GroupId.random());
    /** Unbounded (auth-off-equivalent, and {@code canManageOrg() == true}) by default, so every
     * pre-existing test below is unaffected. */
    private final CurrentUser currentUser = new CurrentUser(ownership);

    @BeforeEach
    void setUp() {
        simulationService = mock(SimulationService.class);
        streamPublisherPort = mock(StreamPublisherPort.class);
        assetService = mock(AssetService.class);

        mockMvc = mockMvcFor(currentUser);
    }

    /**
     * A {@link CurrentUser} answering with {@link #ownership}/{@link #ownerId} but a caller-supplied
     * {@link VisibilityScope}, for the docs/plans/done/LIVE-SCOPE-PLAN.md §2, W2 authority tests
     * below -- same idiom {@code AssetControllerTest} uses. {@link PrincipalResolver#viewer()} is
     * never called by this controller, so it throws rather than fake a map viewer no test here needs.
     */
    private CurrentUser currentUserWithScope(VisibilityScope scope) {
        return new CurrentUser(new PrincipalResolver() {
            @Override
            public UserId userId() {
                return ownerId;
            }

            @Override
            public Ownership ownership() {
                return ownership;
            }

            @Override
            public VisibilityScope scope() {
                return scope;
            }

            @Override
            public MapAccessPolicy.Viewer viewer() {
                throw new UnsupportedOperationException("SimulationController never calls viewer()");
            }

            @Override
            public Role role() {
                throw new UnsupportedOperationException("SimulationController never calls role()");
            }

            @Override
            public Authority authority() {
                throw new UnsupportedOperationException("SimulationController never calls authority()");
            }
        });
    }

    private MockMvc mockMvcFor(CurrentUser user) {
        return MockMvcBuilders
                .standaloneSetup(new SimulationController(simulationService, user, streamPublisherPort, assetService))
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

    // ---- CU-a: fully synthetic simulation (no videoPath) ----

    @Test
    void simulateTreatsABlankVideoPathAsAbsentAndBuildsAFullySyntheticSpec() throws Exception {
        when(simulationService.simulate(any(), eq(ownership), eq(ownerId)))
                .thenReturn(new SimulatedAsset(AssetId.random(), StreamId.random()));

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"videoPath\":\"\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<SimulationSpec> captor = ArgumentCaptor.forClass(SimulationSpec.class);
        verify(simulationService).simulate(captor.capture(), any(), any());
        assertNull(captor.getValue().videoPath());
    }

    @Test
    void simulateWithAnEmptyBodyBuildsAFullySyntheticSpec() throws Exception {
        when(simulationService.simulate(any(), eq(ownership), eq(ownerId)))
                .thenReturn(new SimulatedAsset(AssetId.random(), StreamId.random()));

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<SimulationSpec> captor = ArgumentCaptor.forClass(SimulationSpec.class);
        verify(simulationService).simulate(captor.capture(), any(), any());
        assertNull(captor.getValue().videoPath());
        assertEquals(SimulationTransport.DIRECT, captor.getValue().transport());
    }

    @Test
    void simulateWithJustATelemetryBlockYieldsAFullySyntheticMovingDrone() throws Exception {
        // docs/main/CYCLES-PLAN.md §9, CU-a: "POST /api/simulations {} with just a telemetry block
        // yields a complete moving drone" -- the synthetic telemetry test in one call.
        when(simulationService.simulate(any(), eq(ownership), eq(ownerId)))
                .thenReturn(new SimulatedAsset(AssetId.random(), StreamId.random()));

        String body = """
                {"telemetry":{"speedMps":15.0,
                "route":[{"latitude":50.45,"longitude":30.52},{"latitude":50.46,"longitude":30.53}]}}
                """;

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<SimulationSpec> captor = ArgumentCaptor.forClass(SimulationSpec.class);
        verify(simulationService).simulate(captor.capture(), any(), any());
        SimulationSpec spec = captor.getValue();
        assertNull(spec.videoPath());
        assertEquals(SimulationTransport.DIRECT, spec.transport());
        assertEquals(2, spec.plan().route().size());
    }

    @Test
    void simulateReturns400WhenRtspTransportHasNoVideoPath() throws Exception {
        String body = """
                {"transport":"rtsp"}
                """;

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(allOf(containsString("RTSP"), containsString("videoPath"))));

        verifyNoInteractions(simulationService);
    }

    @Test
    void simulateReturns400WhenMjpegTransportHasNoVideoPath() throws Exception {
        String body = """
                {"transport":"mjpeg"}
                """;

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(allOf(containsString("MJPEG"), containsString("videoPath"))));

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

    // ---- docs/plans/done/MVP2-PLAN.md §L: whepUrl beside viewUrl ----

    @Test
    void simulateReturns201WithWhepUrlWhenPublisherHasOne() throws Exception {
        AssetId assetId = AssetId.random();
        StreamId streamId = StreamId.random();
        when(simulationService.simulate(any(), eq(ownership), eq(ownerId)))
                .thenReturn(new SimulatedAsset(assetId, streamId));
        when(streamPublisherPort.viewUrl(streamId))
                .thenReturn(Optional.of(URI.create("/hls/" + streamId.value() + "/index.m3u8")));
        when(streamPublisherPort.whepUrl(streamId))
                .thenReturn(Optional.of(URI.create("http://localhost:18889/" + streamId.value() + "/whep")));

        String body = """
                {"videoPath":"/data/clips/drone.mp4"}
                """;

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.viewUrl").value("/hls/" + streamId.value() + "/index.m3u8"))
                .andExpect(jsonPath("$.whepUrl").value("http://localhost:18889/" + streamId.value() + "/whep"));
    }

    @Test
    void simulateOmitsWhepUrlWhenPublisherHasNoWebRtcEndpoint() throws Exception {
        StreamId streamId = StreamId.random();
        when(simulationService.simulate(any(), any(), any()))
                .thenReturn(new SimulatedAsset(AssetId.random(), streamId));
        when(streamPublisherPort.viewUrl(streamId))
                .thenReturn(Optional.of(URI.create("/hls/" + streamId.value() + "/index.m3u8")));
        when(streamPublisherPort.whepUrl(streamId)).thenReturn(Optional.empty());

        String body = """
                {"videoPath":"/data/clips/drone.mp4"}
                """;

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.viewUrl").value("/hls/" + streamId.value() + "/index.m3u8"))
                .andExpect(jsonPath("$.whepUrl").doesNotExist());
    }

    @Test
    void simulateOmitsWhepUrlAlongsideStreamIdAndViewUrlWhenNotAutoStarted() throws Exception {
        AssetId assetId = AssetId.random();
        when(simulationService.simulate(any(), eq(ownership), eq(ownerId)))
                .thenReturn(new SimulatedAsset(assetId, null));

        String body = """
                {"videoPath":"/data/clips/drone.mp4","autoStart":false}
                """;

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.whepUrl").doesNotExist());

        verifyNoInteractions(streamPublisherPort);
    }

    // ---- transport parsing ----

    @Test
    void simulateDefaultsTransportToDirectWhenFieldIsAbsent() throws Exception {
        when(simulationService.simulate(any(), eq(ownership), eq(ownerId)))
                .thenReturn(new SimulatedAsset(AssetId.random(), StreamId.random()));

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"videoPath\":\"/data/clips/drone.mp4\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<SimulationSpec> captor = ArgumentCaptor.forClass(SimulationSpec.class);
        verify(simulationService).simulate(captor.capture(), any(), any());
        assertEquals(SimulationTransport.DIRECT, captor.getValue().transport());
    }

    @Test
    void simulateParsesRtspTransportCaseInsensitively() throws Exception {
        when(simulationService.simulate(any(), eq(ownership), eq(ownerId)))
                .thenReturn(new SimulatedAsset(AssetId.random(), StreamId.random()));

        String body = """
                {"videoPath":"/data/clips/drone.mp4","transport":"RtSp"}
                """;

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<SimulationSpec> captor = ArgumentCaptor.forClass(SimulationSpec.class);
        verify(simulationService).simulate(captor.capture(), any(), any());
        assertEquals(SimulationTransport.RTSP, captor.getValue().transport());
    }

    @Test
    void simulateParsesMjpegTransportCaseInsensitively() throws Exception {
        when(simulationService.simulate(any(), eq(ownership), eq(ownerId)))
                .thenReturn(new SimulatedAsset(AssetId.random(), StreamId.random()));

        String body = """
                {"videoPath":"/data/clips/drone.mp4","transport":"MjPeG"}
                """;

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<SimulationSpec> captor = ArgumentCaptor.forClass(SimulationSpec.class);
        verify(simulationService).simulate(captor.capture(), any(), any());
        assertEquals(SimulationTransport.MJPEG, captor.getValue().transport());
    }

    @Test
    void simulateReturns400ForAnUnknownTransportAndNeverTouchesTheService() throws Exception {
        String body = """
                {"videoPath":"/data/clips/drone.mp4","transport":"udp"}
                """;

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(allOf(containsString("udp"), containsString("DIRECT"),
                        containsString("RTSP"), containsString("MJPEG"))));

        verifyNoInteractions(simulationService);
    }

    // ---- telemetryTransport (docs/plans/active/DRONE-INFRA-PLAN.md's own natural follow-up) ----

    @Test
    void simulateDefaultsTelemetryTransportToSimWhenFieldIsAbsent() throws Exception {
        when(simulationService.simulate(any(), eq(ownership), eq(ownerId)))
                .thenReturn(new SimulatedAsset(AssetId.random(), StreamId.random()));

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"videoPath\":\"/data/clips/drone.mp4\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<SimulationSpec> captor = ArgumentCaptor.forClass(SimulationSpec.class);
        verify(simulationService).simulate(captor.capture(), any(), any());
        assertEquals(TelemetryTransport.SIM, captor.getValue().telemetryTransport());
    }

    @Test
    void simulateParsesMavlinkTelemetryTransportCaseInsensitively() throws Exception {
        when(simulationService.simulate(any(), eq(ownership), eq(ownerId)))
                .thenReturn(new SimulatedAsset(AssetId.random(), StreamId.random()));

        String body = """
                {"videoPath":"/data/clips/drone.mp4","telemetryTransport":"MavLink"}
                """;

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<SimulationSpec> captor = ArgumentCaptor.forClass(SimulationSpec.class);
        verify(simulationService).simulate(captor.capture(), any(), any());
        assertEquals(TelemetryTransport.MAVLINK, captor.getValue().telemetryTransport());
    }

    @Test
    void simulateReturns400ForAnUnknownTelemetryTransportAndNeverTouchesTheService() throws Exception {
        String body = """
                {"videoPath":"/data/clips/drone.mp4","telemetryTransport":"lora"}
                """;

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value(allOf(containsString("lora"), containsString("SIM"), containsString("MAVLINK"))));

        verifyNoInteractions(simulationService);
    }

    // ---- CT-a: telemetry flight plan ----

    @Test
    void simulateParsesATelemetryPlanWithARouteSpeedAndMode() throws Exception {
        when(simulationService.simulate(any(), eq(ownership), eq(ownerId)))
                .thenReturn(new SimulatedAsset(AssetId.random(), StreamId.random()));

        String body = """
                {"videoPath":"/data/clips/drone.mp4","telemetry":{"speedMps":15.0,"routeMode":"bounce",
                "route":[{"latitude":50.45,"longitude":30.52},{"latitude":50.46,"longitude":30.53,"altitudeMeters":120.0}]}}
                """;

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<SimulationSpec> captor = ArgumentCaptor.forClass(SimulationSpec.class);
        verify(simulationService).simulate(captor.capture(), any(), any());
        TelemetryPlan plan = captor.getValue().plan();
        assertEquals(15.0, plan.speedMps());
        assertEquals(RouteMode.BOUNCE, plan.mode());
        assertEquals(2, plan.route().size());
        assertEquals(50.45, plan.route().get(0).latitude());
        assertEquals(30.52, plan.route().get(0).longitude());
        assertEquals(120.0, plan.route().get(1).altitudeMeters());
    }

    @Test
    void simulateDefersRouteModeToTheAdaptersDefaultWhenAbsentFromTelemetry() throws Exception {
        when(simulationService.simulate(any(), eq(ownership), eq(ownerId)))
                .thenReturn(new SimulatedAsset(AssetId.random(), StreamId.random()));

        String body = """
                {"videoPath":"/data/clips/drone.mp4","telemetry":{
                "route":[{"latitude":1.0,"longitude":2.0},{"latitude":3.0,"longitude":4.0}]}}
                """;

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<SimulationSpec> captor = ArgumentCaptor.forClass(SimulationSpec.class);
        verify(simulationService).simulate(captor.capture(), any(), any());
        TelemetryPlan plan = captor.getValue().plan();
        assertEquals(null, plan.mode());
        assertEquals(null, plan.speedMps());
    }

    @Test
    void simulateDefaultsPlanToNullWhenTelemetryFieldIsAbsent() throws Exception {
        when(simulationService.simulate(any(), eq(ownership), eq(ownerId)))
                .thenReturn(new SimulatedAsset(AssetId.random(), StreamId.random()));

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"videoPath\":\"/data/clips/drone.mp4\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<SimulationSpec> captor = ArgumentCaptor.forClass(SimulationSpec.class);
        verify(simulationService).simulate(captor.capture(), any(), any());
        assertEquals(null, captor.getValue().plan());
    }

    @Test
    void simulateReturns400WhenTelemetryRouteHasFewerThanTwoWaypoints() throws Exception {
        String body = """
                {"videoPath":"/data/clips/drone.mp4","telemetry":{"route":[{"latitude":1.0,"longitude":2.0}]}}
                """;

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verifyNoInteractions(simulationService);
    }

    @Test
    void simulateReturns400WhenATelemetryWaypointIsOutOfRange() throws Exception {
        String body = """
                {"videoPath":"/data/clips/drone.mp4","telemetry":{
                "route":[{"latitude":1.0,"longitude":2.0},{"latitude":999.0,"longitude":4.0}]}}
                """;

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verifyNoInteractions(simulationService);
    }

    @Test
    void simulateReturns400WhenTelemetrySpeedMpsIsNotPositive() throws Exception {
        String body = """
                {"videoPath":"/data/clips/drone.mp4","telemetry":{"speedMps":0,
                "route":[{"latitude":1.0,"longitude":2.0},{"latitude":3.0,"longitude":4.0}]}}
                """;

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verifyNoInteractions(simulationService);
    }

    @Test
    void simulateReturns400ForAnUnknownRouteModeAndNeverTouchesTheService() throws Exception {
        String body = """
                {"videoPath":"/data/clips/drone.mp4","telemetry":{"routeMode":"zigzag",
                "route":[{"latitude":1.0,"longitude":2.0},{"latitude":3.0,"longitude":4.0}]}}
                """;

        mockMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(allOf(containsString("zigzag"), containsString("LOOP"),
                        containsString("BOUNCE"), containsString("ONCE"))));

        verifyNoInteractions(simulationService);
    }

    // ---- DELETE /api/simulations/{assetId} ----

    @Test
    void deleteSimulationsReturns204AndStopsTheSimulation() throws Exception {
        AssetId assetId = AssetId.random();

        mockMvc.perform(delete("/api/simulations/{assetId}", assetId.value()))
                .andExpect(status().isNoContent());

        verify(simulationService).stop(assetId);
    }

    @Test
    void deleteSimulationsIsIdempotent() throws Exception {
        AssetId assetId = AssetId.random();

        mockMvc.perform(delete("/api/simulations/{assetId}", assetId.value())).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/simulations/{assetId}", assetId.value())).andExpect(status().isNoContent());

        verify(simulationService, times(2)).stop(assetId);
    }

    @Test
    void deleteSimulationsReturns400ForABadUuidAndNeverTouchesTheService() throws Exception {
        mockMvc.perform(delete("/api/simulations/{assetId}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verifyNoInteractions(simulationService);
    }

    // ---- docs/plans/done/LIVE-SCOPE-PLAN.md §2, W2: authority --------------------------------
    //
    // Before this wave, #simulate had no gate at all (any authenticated caller, including a PILOT,
    // could register and auto-start a fleet asset) and #stop had no scope check either. These tests
    // fail without the changes made in this wave.

    @Test
    void simulateReturns403ForAPilotScope() throws Exception {
        MockMvc pilotMvc = mockMvcFor(currentUserWithScope(VisibilityScope.assignedAssets(Set.of())));

        String body = """
                {"videoPath":"/data/clips/drone.mp4"}
                """;

        pilotMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verifyNoInteractions(simulationService);
    }

    @Test
    void simulateReturns201ForAManagerScope() throws Exception {
        // canManageOrg() is true for GROUPS too (a MANAGER), not just UNBOUNDED -- the same
        // asymmetry AssetController#create's own gate draws.
        MockMvc managerMvc = mockMvcFor(currentUserWithScope(VisibilityScope.groups(Set.of(ownership.groupId()))));
        when(simulationService.simulate(any(), eq(ownership), eq(ownerId)))
                .thenReturn(new SimulatedAsset(AssetId.random(), null));

        managerMvc.perform(post("/api/simulations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"videoPath\":\"/data/clips/drone.mp4\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void stopReturns404ForAPilotScopedToADifferentAsset() throws Exception {
        AssetId assetId = AssetId.random();
        when(assetService.details(any(VisibilityScope.class), eq(assetId)))
                .thenThrow(new NoSuchElementException("Unknown asset: " + assetId.value()));
        MockMvc pilotMvc = mockMvcFor(currentUserWithScope(VisibilityScope.assignedAssets(Set.of())));

        pilotMvc.perform(delete("/api/simulations/{assetId}", assetId.value()))
                .andExpect(status().isNotFound());

        verify(simulationService, never()).stop(any());
    }

    @Test
    void stopReturns204ForAPilotScopedToTheAsset() throws Exception {
        AssetId assetId = AssetId.random();
        MockMvc pilotMvc = mockMvcFor(currentUserWithScope(VisibilityScope.assignedAssets(Set.of(assetId))));

        pilotMvc.perform(delete("/api/simulations/{assetId}", assetId.value()))
                .andExpect(status().isNoContent());

        verify(simulationService).stop(assetId);
    }
}
