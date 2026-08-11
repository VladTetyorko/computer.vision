package com.drones.vision.simulation.application;

import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.warehouse.domain.model.DeviceCategory;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.perception.domain.model.FeedId;
import com.drones.vision.perception.domain.model.FeedSpec;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.warehouse.domain.port.CategoryRepositoryPort;
import com.drones.vision.perception.domain.port.FeedTransmitterPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.drones.vision.warehouse.application.asset.AssetDetails;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetSpec;
import com.drones.vision.warehouse.application.asset.AssetStatus;
import com.drones.vision.warehouse.application.asset.AssetSummary;
import com.drones.vision.warehouse.application.device.DeviceRegistration;
import com.drones.vision.perception.application.pipeline.FeedTransmitterRegistry;

class DefaultSimulationServiceTest {

    private static final CategoryId SIMULATED = new CategoryId("simulated");
    private static final URI MEDIAMTX_RTSP_BASE = URI.create("rtsp://localhost:8554");

    private AssetService assetService;
    private CategoryRepositoryPort categoryRepository;
    private FeedTransmitterPort feedTransmitter;
    private FeedTransmitterPort mjpegTransmitter;
    private FeedTransmitterPort mavlinkTransmitter;
    private SimulationService service;
    private Ownership ownership;
    private UserId actor;

    @BeforeEach
    void setUp() {
        assetService = mock(AssetService.class);
        categoryRepository = mock(CategoryRepositoryPort.class);
        feedTransmitter = mock(FeedTransmitterPort.class);
        mjpegTransmitter = mock(FeedTransmitterPort.class);
        mavlinkTransmitter = mock(FeedTransmitterPort.class);
        service = new DefaultSimulationService(assetService, categoryRepository,
                new FeedTransmitterRegistry(List.of(feedTransmitter, mjpegTransmitter, mavlinkTransmitter)),
                MEDIAMTX_RTSP_BASE);
        actor = UserId.random();
        ownership = new Ownership(actor, GroupId.random());

        when(categoryRepository.findById(SIMULATED))
                .thenReturn(Optional.of(new DeviceCategory(SIMULATED, "Simulated", null, List.of())));
        // Protocol-aware, like the real MavlinkFeedTransmitter#supports() -- a blanket any()->true
        // stub would wrongly make this mock "support" rtsp/mjpeg FeedSpecs too, breaking every
        // pre-existing unsupported-protocol test (the registry picks the *first* supporting
        // transmitter, and mavlinkTransmitter is registered last, but still gets probed).
        when(mavlinkTransmitter.supports(any())).thenAnswer(invocation -> {
            FeedSpec spec = invocation.getArgument(0, FeedSpec.class);
            return spec != null && "mavlink".equals(spec.protocol());
        });
        when(mavlinkTransmitter.start(any(), any())).thenAnswer(invocation ->
                new StreamDescriptor("mavlink", invocation.getArgument(1, FeedSpec.class).source(), Map.of()));
    }

    // --- Path validation -------------------------------------------------------

    @Test
    void simulateRejectsANonExistentPathAndNeverTouchesAssetService() {
        SimulationSpec spec = new SimulationSpec(null, "/no/such/file-really-not-there.mp4", null, null, false);

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> service.simulate(spec, ownership, actor));
        assertTrue(thrown.getMessage().contains("file-really-not-there.mp4"),
                "expected message to name the offending path: " + thrown.getMessage());
        verifyNoInteractions(assetService);
    }

    @Test
    void simulateRejectsADirectoryPath(@TempDir Path tempDir) {
        SimulationSpec spec = new SimulationSpec(null, tempDir.toString(), null, null, false);

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> service.simulate(spec, ownership, actor));
        assertTrue(thrown.getMessage().contains(tempDir.toString()));
        verifyNoInteractions(assetService);
    }

    @Test
    void simulateRejectsAnUnreadableFile(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("clip.mp4");
        Files.writeString(file, "not a real video, just bytes");
        boolean revoked = file.toFile().setReadable(false);

        try {
            if (!revoked || Files.isReadable(file)) {
                // Some environments (e.g. running as root, which bypasses the read bit
                // entirely) cannot make the file genuinely unreadable; skip rather than
                // false-fail an unenforceable assertion.
                return;
            }
            SimulationSpec spec = new SimulationSpec(null, file.toString(), null, null, false);

            assertThrows(IllegalArgumentException.class, () -> service.simulate(spec, ownership, actor));
            verifyNoInteractions(assetService);
        } finally {
            file.toFile().setReadable(true);
        }
    }

    // --- Category validation ----------------------------------------------------

    @Test
    void simulateThrowsIllegalStateWhenSimulatedCategoryIsNotSeeded(@TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        when(categoryRepository.findById(SIMULATED)).thenReturn(Optional.empty());
        SimulationSpec spec = new SimulationSpec(null, file.toString(), null, null, false);

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> service.simulate(spec, ownership, actor));
        assertTrue(thrown.getMessage().contains("simulated") && thrown.getMessage().contains("not seeded"));
        verifyNoInteractions(assetService);
    }

    // --- Display name derivation -------------------------------------------------

    @Test
    void simulateDerivesDisplayNameFromFileNameWithoutExtensionWhenNoneGiven(@TempDir Path tempDir)
            throws IOException {
        Path file = videoFile(tempDir, "my-cool-drone-footage.mp4");
        stubCreate();
        SimulationSpec spec = new SimulationSpec(null, file.toString(), null, null, false);

        service.simulate(spec, ownership, actor);

        AssetSpec created = capturedAssetSpec();
        assertEquals("my-cool-drone-footage", created.displayName());
    }

    @Test
    void simulateDerivesDisplayNameWhenGivenNameIsBlank(@TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        stubCreate();
        SimulationSpec spec = new SimulationSpec("   ", file.toString(), null, null, false);

        service.simulate(spec, ownership, actor);

        assertEquals("clip", capturedAssetSpec().displayName());
    }

    @Test
    void simulateUsesTheGivenDisplayNameWhenPresent(@TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        stubCreate();
        SimulationSpec spec = new SimulationSpec("My Drone", file.toString(), null, null, false);

        service.simulate(spec, ownership, actor);

        assertEquals("My Drone", capturedAssetSpec().displayName());
    }

    // --- Device registration -------------------------------------------------------

    @Test
    void simulateRegistersExactlyOneVideoAndOneTelemetryDeviceWithCorrectProtocols(@TempDir Path tempDir)
            throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        stubCreate();
        SimulationSpec spec = new SimulationSpec("My Drone", file.toString(), null, null, false);

        service.simulate(spec, ownership, actor);

        AssetSpec created = capturedAssetSpec();
        assertEquals(SIMULATED, created.category());
        assertEquals(2, created.devices().size());

        DeviceRegistration video = created.devices().get(0);
        assertEquals("My Drone · video", video.name());
        assertEquals(Set.of(Capability.VIDEO), video.capabilities());
        assertEquals("file", video.stream().protocol());
        assertEquals(file.toUri(), video.stream().uri());
        assertEquals(Map.of("loop", "true"), video.stream().options());

        DeviceRegistration telemetry = created.devices().get(1);
        assertEquals("My Drone · telemetry", telemetry.name());
        assertEquals(Set.of(Capability.TELEMETRY), telemetry.capabilities());
        assertEquals("sim", telemetry.stream().protocol());
        assertEquals(Map.of(), telemetry.stream().options());

        assertEquals(file.toString(), created.attributes().get("source"));
    }

    @Test
    void simulateSetsTelemetryLatLonOptionsOnlyWhenProvided(@TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        stubCreate();
        SimulationSpec spec = new SimulationSpec("My Drone", file.toString(), 50.45, 30.52, false);

        service.simulate(spec, ownership, actor);

        DeviceRegistration telemetry = capturedAssetSpec().devices().get(1);
        assertEquals(Map.of("lat", "50.45", "lon", "30.52"), telemetry.stream().options());
    }

    @Test
    void simulateOmitsTelemetryLatOptionWhenOnlyLongitudeGiven(@TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        stubCreate();
        SimulationSpec spec = new SimulationSpec("My Drone", file.toString(), null, 30.52, false);

        service.simulate(spec, ownership, actor);

        DeviceRegistration telemetry = capturedAssetSpec().devices().get(1);
        assertEquals(Map.of("lon", "30.52"), telemetry.stream().options());
    }

    // --- CT-a: telemetry flight plan ------------------------------------------------

    @Test
    void simulateWithATelemetryPlanSerializesTheRouteSpeedAndModeAndUsesTheFirstWaypointAsLatLon(
            @TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        stubCreate();
        List<Waypoint> route = List.of(
                new Waypoint(50.45, 30.52, null),
                new Waypoint(50.46, 30.53, 120.0));
        TelemetryPlan plan = new TelemetryPlan(15.0, RouteMode.BOUNCE, route);
        SimulationSpec spec = new SimulationSpec("My Drone", file.toString(), null, null, false,
                SimulationTransport.DIRECT, plan);

        service.simulate(spec, ownership, actor);

        DeviceRegistration telemetry = capturedAssetSpec().devices().get(1);
        assertEquals(Map.of(
                "lat", "50.45",
                "lon", "30.52",
                "route", "50.45,30.52;50.46,30.53,120.0",
                "speedMps", "15.0",
                "routeMode", "bounce"
        ), telemetry.stream().options());
    }

    @Test
    void simulateWithATelemetryPlanOmitsSpeedAndModeOptionsWhenNeitherIsGiven(@TempDir Path tempDir)
            throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        stubCreate();
        List<Waypoint> route = List.of(new Waypoint(1.0, 2.0, null), new Waypoint(3.0, 4.0, null));
        TelemetryPlan plan = new TelemetryPlan(null, null, route);
        SimulationSpec spec = new SimulationSpec("My Drone", file.toString(), null, null, false,
                SimulationTransport.DIRECT, plan);

        service.simulate(spec, ownership, actor);

        DeviceRegistration telemetry = capturedAssetSpec().devices().get(1);
        assertEquals(Map.of("lat", "1.0", "lon", "2.0", "route", "1.0,2.0;3.0,4.0"),
                telemetry.stream().options());
    }

    @Test
    void simulateWithATelemetryPlanIgnoresBareLatitudeAndLongitude(@TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        stubCreate();
        List<Waypoint> route = List.of(new Waypoint(11.0, 22.0, null), new Waypoint(33.0, 44.0, null));
        TelemetryPlan plan = new TelemetryPlan(null, null, route);
        // Bare lat/lon (99.0/-99.0) are set too, but the plan must win entirely.
        SimulationSpec spec = new SimulationSpec("My Drone", file.toString(), 99.0, -99.0, false,
                SimulationTransport.DIRECT, plan);

        service.simulate(spec, ownership, actor);

        DeviceRegistration telemetry = capturedAssetSpec().devices().get(1);
        Map<String, String> options = telemetry.stream().options();
        assertEquals("11.0", options.get("lat"), "the plan's first waypoint must win over the bare latitude field");
        assertEquals("22.0", options.get("lon"), "the plan's first waypoint must win over the bare longitude field");
    }

    // --- CU-a: fully synthetic simulation (no videoPath) ---------------------------

    @Test
    void simulateWithNoVideoPathRegistersASimProtocolVideoDeviceWithNoLoopOption() {
        stubCreate();
        SimulationSpec spec = new SimulationSpec("My Drone", null, null, null, false);

        service.simulate(spec, ownership, actor);

        AssetSpec created = capturedAssetSpec();
        assertEquals(2, created.devices().size());
        DeviceRegistration video = created.devices().get(0);
        assertEquals("My Drone · video", video.name());
        assertEquals(Set.of(Capability.VIDEO), video.capabilities());
        assertEquals("sim", video.stream().protocol());
        assertEquals(URI.create("sim://my-drone"), video.stream().uri());
        assertEquals(Map.of(), video.stream().options(),
                "a fully synthetic video device has no loop option -- the renderer runs forever on its own");
        assertFalse(created.attributes().containsKey("source"), "no video file means no 'source' attribute");
    }

    @Test
    void simulateWithNoVideoPathDerivesDisplayNameToSyntheticDroneWhenAbsent() {
        stubCreate();
        SimulationSpec spec = new SimulationSpec(null, null, null, null, false);

        service.simulate(spec, ownership, actor);

        assertEquals("Synthetic drone", capturedAssetSpec().displayName());
    }

    @Test
    void simulateWithNoVideoPathDerivesDisplayNameToSyntheticDroneWhenBlank() {
        stubCreate();
        SimulationSpec spec = new SimulationSpec("   ", null, null, null, false);

        service.simulate(spec, ownership, actor);

        assertEquals("Synthetic drone", capturedAssetSpec().displayName());
    }

    @Test
    void simulateWithNoVideoPathUsesTheGivenDisplayNameWhenPresent() {
        stubCreate();
        SimulationSpec spec = new SimulationSpec("My Synthetic Drone", null, null, null, false);

        service.simulate(spec, ownership, actor);

        assertEquals("My Synthetic Drone", capturedAssetSpec().displayName());
    }

    @Test
    void simulateWithNoVideoPathStillHonorsTelemetryLatLonOptions() {
        stubCreate();
        SimulationSpec spec = new SimulationSpec("My Drone", null, 50.45, 30.52, false);

        service.simulate(spec, ownership, actor);

        DeviceRegistration telemetry = capturedAssetSpec().devices().get(1);
        assertEquals(Map.of("lat", "50.45", "lon", "30.52"), telemetry.stream().options());
    }

    @Test
    void simulationSpecRejectsRtspTransportWithoutVideoPath() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new SimulationSpec("My Drone", null, null, null, false, SimulationTransport.RTSP));
        assertTrue(thrown.getMessage().contains("RTSP") && thrown.getMessage().contains("videoPath"),
                "expected message to name the transport and mention videoPath: " + thrown.getMessage());
    }

    @Test
    void simulationSpecRejectsMjpegTransportWithoutVideoPath() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new SimulationSpec("My Drone", null, null, null, false, SimulationTransport.MJPEG));
        assertTrue(thrown.getMessage().contains("MJPEG") && thrown.getMessage().contains("videoPath"),
                "expected message to name the transport and mention videoPath: " + thrown.getMessage());
    }

    @Test
    void simulationSpecAllowsDirectTransportWithoutVideoPath() {
        SimulationSpec spec = new SimulationSpec("My Drone", null, null, null, false, SimulationTransport.DIRECT);

        assertNull(spec.videoPath());
    }

    @Test
    void simulationSpecStillRejectsABlankVideoPathDistinctFromNull() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new SimulationSpec("My Drone", "   ", null, null, false));
        assertTrue(thrown.getMessage().contains("blank"));
    }

    // --- autoStart ----------------------------------------------------------------

    @Test
    void simulateStartsAStreamWhenAutoStartIsTrue(@TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        Asset created = stubCreate();
        StreamId startedStream = StreamId.random();
        when(assetService.startStream(eq(created.id()), eq(null), eq(PipelineConfig.defaults())))
                .thenReturn(startedStream);
        SimulationSpec spec = new SimulationSpec("My Drone", file.toString(), null, null, true);

        SimulatedAsset result = service.simulate(spec, ownership, actor);

        assertEquals(created.id(), result.assetId());
        assertEquals(startedStream, result.streamId());
        verify(assetService).startStream(created.id(), null, PipelineConfig.defaults());
    }

    @Test
    void simulateDoesNotStartAStreamWhenAutoStartIsFalse(@TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        Asset created = stubCreate();
        SimulationSpec spec = new SimulationSpec("My Drone", file.toString(), null, null, false);

        SimulatedAsset result = service.simulate(spec, ownership, actor);

        assertEquals(created.id(), result.assetId());
        assertNull(result.streamId());
        verify(assetService, never()).startStream(any(), any(), any());
    }

    // --- transport ----------------------------------------------------------------

    @Test
    void simulateDefaultsTransportToDirectAndNeverTouchesFeedTransmitter(@TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        stubCreate();
        SimulationSpec spec = new SimulationSpec("My Drone", file.toString(), null, null, false);

        assertEquals(SimulationTransport.DIRECT, spec.transport(),
                "the 5-arg convenience constructor must default transport to DIRECT");

        service.simulate(spec, ownership, actor);

        DeviceRegistration video = capturedAssetSpec().devices().get(0);
        assertEquals("file", video.stream().protocol());
        verifyNoInteractions(feedTransmitter);
        verifyNoInteractions(mjpegTransmitter);
    }

    @Test
    void simulateWithRtspTransportRegistersTheTransmitterReturnedDescriptorAugmentedWithRxTimeout(
            @TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        stubCreate();
        URI feedTarget = URI.create("rtsp://localhost:8554/feed-abc");
        when(feedTransmitter.supports(any())).thenReturn(true);
        when(feedTransmitter.start(any(), any()))
                .thenReturn(new StreamDescriptor("rtsp", feedTarget, Map.of()));
        SimulationSpec spec =
                new SimulationSpec("My Drone", file.toString(), null, null, false, SimulationTransport.RTSP);

        service.simulate(spec, ownership, actor);

        ArgumentCaptor<FeedSpec> feedSpecCaptor = ArgumentCaptor.forClass(FeedSpec.class);
        verify(feedTransmitter).supports(feedSpecCaptor.capture());
        verify(feedTransmitter).start(any(), eq(feedSpecCaptor.getValue()));
        assertEquals("rtsp", feedSpecCaptor.getValue().protocol());
        assertEquals(file.toUri(), feedSpecCaptor.getValue().source());
        assertEquals(Map.of("loop", "true"), feedSpecCaptor.getValue().options());

        DeviceRegistration video = capturedAssetSpec().devices().get(0);
        assertEquals("rtsp", video.stream().protocol());
        assertEquals(feedTarget, video.stream().uri());
        assertEquals(Map.of("timeout", "2000000"), video.stream().options(),
                "the RX-side StreamDescriptor must carry the short-timeout contention fix (adapter-rtsp/MODULE.md "
                        + "Gotchas) on top of whatever the transmitter returned");
        verifyNoInteractions(mjpegTransmitter);
    }

    @Test
    void simulateThrowsForAnUnsupportedFeedSpecAndNeverTouchesAssetService(@TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        when(feedTransmitter.supports(any())).thenReturn(false);
        SimulationSpec spec =
                new SimulationSpec("My Drone", file.toString(), null, null, false, SimulationTransport.RTSP);

        assertThrows(IllegalArgumentException.class, () -> service.simulate(spec, ownership, actor));

        verifyNoInteractions(assetService);
        verify(feedTransmitter, never()).start(any(), any());
    }

    // --- mjpeg transport / registry selection --------------------------------------

    @Test
    void simulateWithMjpegTransportRegistersTheTransmitterReturnedDescriptorAsIs(@TempDir Path tempDir)
            throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        stubCreate();
        URI feedTarget = URI.create("http://127.0.0.1:54321/feed-abc");
        when(mjpegTransmitter.supports(any())).thenReturn(true);
        when(mjpegTransmitter.start(any(), any()))
                .thenReturn(new StreamDescriptor("mjpeg", feedTarget, Map.of()));
        SimulationSpec spec =
                new SimulationSpec("My Drone", file.toString(), null, null, false, SimulationTransport.MJPEG);

        service.simulate(spec, ownership, actor);

        ArgumentCaptor<FeedSpec> feedSpecCaptor = ArgumentCaptor.forClass(FeedSpec.class);
        verify(mjpegTransmitter).supports(feedSpecCaptor.capture());
        verify(mjpegTransmitter).start(any(), eq(feedSpecCaptor.getValue()));
        assertEquals("mjpeg", feedSpecCaptor.getValue().protocol());
        assertEquals(file.toUri(), feedSpecCaptor.getValue().source());
        assertEquals(Map.of("loop", "true"), feedSpecCaptor.getValue().options());

        DeviceRegistration video = capturedAssetSpec().devices().get(0);
        assertEquals("mjpeg", video.stream().protocol());
        assertEquals(feedTarget, video.stream().uri());
        assertEquals(Map.of(), video.stream().options(),
                "the mjpeg StreamDescriptor must be registered as-is -- no RX-side timeout augmentation, "
                        + "that fix is RTSP-specific (adapter-rtsp/MODULE.md Gotchas)");
        // feedTransmitter (registered first) is still probed via supports() by the registry's
        // linear scan, but must never actually be started for an mjpeg-transport simulation.
        verify(feedTransmitter, never()).start(any(), any());
    }

    @Test
    void simulateWithRtspTransportOnlyTouchesTheTransmitterThatSupportsIt(@TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        stubCreate();
        when(feedTransmitter.supports(any())).thenReturn(true);
        when(feedTransmitter.start(any(), any()))
                .thenReturn(new StreamDescriptor("rtsp", URI.create("rtsp://localhost:8554/feed-q"), Map.of()));
        SimulationSpec spec =
                new SimulationSpec("My Drone", file.toString(), null, null, false, SimulationTransport.RTSP);

        service.simulate(spec, ownership, actor);

        verifyNoInteractions(mjpegTransmitter);
    }

    @Test
    void simulateThrowsWithAClearMessageWhenNoRegisteredTransmitterSupportsMjpeg(@TempDir Path tempDir)
            throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        // Neither feedTransmitter nor mjpegTransmitter stubs supports() -> both default to false.
        SimulationSpec spec =
                new SimulationSpec("My Drone", file.toString(), null, null, false, SimulationTransport.MJPEG);

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> service.simulate(spec, ownership, actor));
        assertTrue(thrown.getMessage().contains("mjpeg"),
                "expected message to name the unsupported protocol: " + thrown.getMessage());
        verifyNoInteractions(assetService);
        verify(feedTransmitter, never()).start(any(), any());
        verify(mjpegTransmitter, never()).start(any(), any());
    }

    @Test
    void stopStopsTheStreamAndTheTrackedFeedViaTheTransmitterThatStartedItForAnMjpegAsset(@TempDir Path tempDir)
            throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        Asset created = stubCreate();
        when(mjpegTransmitter.supports(any())).thenReturn(true);
        when(mjpegTransmitter.start(any(), any()))
                .thenReturn(new StreamDescriptor("mjpeg", URI.create("http://127.0.0.1:9999/feed-m"), Map.of()));
        SimulationSpec spec =
                new SimulationSpec("My Drone", file.toString(), null, null, false, SimulationTransport.MJPEG);
        service.simulate(spec, ownership, actor);
        ArgumentCaptor<FeedId> feedIdCaptor = ArgumentCaptor.forClass(FeedId.class);
        verify(mjpegTransmitter).start(feedIdCaptor.capture(), any());

        service.stop(created.id());

        verify(assetService).stopStream(created.id());
        verify(mjpegTransmitter).stop(feedIdCaptor.getValue());
        verify(feedTransmitter, never()).stop(any());
    }

    @Test
    void simulateStopsTheFeedWhenAssetServiceCreateThrows(@TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        when(feedTransmitter.supports(any())).thenReturn(true);
        when(feedTransmitter.start(any(), any()))
                .thenReturn(new StreamDescriptor("rtsp", URI.create("rtsp://localhost:8554/feed-x"), Map.of()));
        when(assetService.create(any(), any(), any())).thenThrow(new IllegalStateException("category vanished"));
        SimulationSpec spec =
                new SimulationSpec("My Drone", file.toString(), null, null, false, SimulationTransport.RTSP);

        assertThrows(IllegalStateException.class, () -> service.simulate(spec, ownership, actor));

        ArgumentCaptor<FeedId> feedIdCaptor = ArgumentCaptor.forClass(FeedId.class);
        verify(feedTransmitter).start(feedIdCaptor.capture(), any());
        verify(feedTransmitter).stop(feedIdCaptor.getValue());
    }

    @Test
    void simulateStopsTheFeedWhenStartStreamThrows(@TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        Asset created = stubCreate();
        when(feedTransmitter.supports(any())).thenReturn(true);
        when(feedTransmitter.start(any(), any()))
                .thenReturn(new StreamDescriptor("rtsp", URI.create("rtsp://localhost:8554/feed-y"), Map.of()));
        when(assetService.startStream(any(), any(), any())).thenThrow(new IllegalStateException("device offline"));
        SimulationSpec spec =
                new SimulationSpec("My Drone", file.toString(), null, null, true, SimulationTransport.RTSP);

        assertThrows(IllegalStateException.class, () -> service.simulate(spec, ownership, actor));

        ArgumentCaptor<FeedId> feedIdCaptor = ArgumentCaptor.forClass(FeedId.class);
        verify(feedTransmitter).start(feedIdCaptor.capture(), any());
        verify(feedTransmitter).stop(feedIdCaptor.getValue());

        // The feed must have been untracked too: stopping the (failed) asset afterwards must not
        // attempt to stop the same feed a second time.
        service.stop(created.id());
        verify(feedTransmitter, times(1)).stop(feedIdCaptor.getValue());
    }

    // --- stop() ----------------------------------------------------------------

    @Test
    void stopStopsTheStreamAndTheTrackedFeedForAnRtspAsset(@TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        Asset created = stubCreate();
        when(feedTransmitter.supports(any())).thenReturn(true);
        when(feedTransmitter.start(any(), any()))
                .thenReturn(new StreamDescriptor("rtsp", URI.create("rtsp://localhost:8554/feed-z"), Map.of()));
        SimulationSpec spec =
                new SimulationSpec("My Drone", file.toString(), null, null, false, SimulationTransport.RTSP);
        service.simulate(spec, ownership, actor);
        ArgumentCaptor<FeedId> feedIdCaptor = ArgumentCaptor.forClass(FeedId.class);
        verify(feedTransmitter).start(feedIdCaptor.capture(), any());

        service.stop(created.id());

        verify(assetService).stopStream(created.id());
        verify(feedTransmitter).stop(feedIdCaptor.getValue());
    }

    @Test
    void stopNeverTouchesFeedTransmitterForADirectAsset(@TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        Asset created = stubCreate();
        SimulationSpec spec = new SimulationSpec("My Drone", file.toString(), null, null, false);
        service.simulate(spec, ownership, actor);

        service.stop(created.id());

        verify(assetService).stopStream(created.id());
        verifyNoInteractions(feedTransmitter);
    }

    @Test
    void stopIsANoOpForAnUntrackedAssetId() {
        AssetId unknown = AssetId.random();

        service.stop(unknown);

        verify(assetService).stopStream(unknown);
        verifyNoInteractions(feedTransmitter);
    }

    @Test
    void stopIsIdempotentAndOnlyStopsTheFeedOnce(@TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        Asset created = stubCreate();
        when(feedTransmitter.supports(any())).thenReturn(true);
        when(feedTransmitter.start(any(), any()))
                .thenReturn(new StreamDescriptor("rtsp", URI.create("rtsp://localhost:8554/feed-w"), Map.of()));
        SimulationSpec spec =
                new SimulationSpec("My Drone", file.toString(), null, null, false, SimulationTransport.RTSP);
        service.simulate(spec, ownership, actor);

        service.stop(created.id());
        service.stop(created.id());

        verify(feedTransmitter, times(1)).stop(any());
    }

    // --- mavlink telemetry transport (docs/plans/active/DRONE-INFRA-PLAN.md's natural follow-up) ----------------

    @Test
    void simulateDefaultsTelemetryTransportToSimAndNeverTouchesMavlinkTransmitter(@TempDir Path tempDir)
            throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        stubCreate();
        SimulationSpec spec = new SimulationSpec("My Drone", file.toString(), null, null, false);

        assertEquals(TelemetryTransport.SIM, spec.telemetryTransport(),
                "the shorter convenience constructors must default telemetryTransport to SIM");

        service.simulate(spec, ownership, actor);

        DeviceRegistration telemetry = capturedAssetSpec().devices().get(1);
        assertEquals("sim", telemetry.stream().protocol());
        verifyNoInteractions(mavlinkTransmitter);
    }

    @Test
    void simulationSpecNormalizesAnExplicitNullTelemetryTransportToSim() {
        SimulationSpec spec =
                new SimulationSpec("My Drone", null, null, null, false, SimulationTransport.DIRECT, null, null);

        assertEquals(TelemetryTransport.SIM, spec.telemetryTransport());
    }

    @Test
    void simulateWithMavlinkTelemetryTransportRegistersAMavlinkDeviceWithSysidAndALoopbackUdpUri(
            @TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        stubCreate();
        List<Waypoint> route = List.of(new Waypoint(1.0, 2.0, null), new Waypoint(3.0, 4.0, null));
        TelemetryPlan plan = new TelemetryPlan(null, null, route);
        SimulationSpec spec = new SimulationSpec("My Drone", file.toString(), null, null, false,
                SimulationTransport.DIRECT, plan, TelemetryTransport.MAVLINK);

        service.simulate(spec, ownership, actor);

        DeviceRegistration telemetry = capturedAssetSpec().devices().get(1);
        assertEquals("My Drone · telemetry", telemetry.name());
        assertEquals(Set.of(Capability.TELEMETRY), telemetry.capabilities());
        assertEquals("mavlink", telemetry.stream().protocol());
        assertEquals("udp", telemetry.stream().uri().getScheme());
        assertEquals("127.0.0.1", telemetry.stream().uri().getHost());
        assertTrue(telemetry.stream().uri().getPort() > 0, "a free loopback port must have been allocated");
        assertEquals(Map.of("sysid", "1"), telemetry.stream().options());

        ArgumentCaptor<FeedSpec> feedSpecCaptor = ArgumentCaptor.forClass(FeedSpec.class);
        verify(mavlinkTransmitter).start(any(), feedSpecCaptor.capture());
        assertEquals("mavlink", feedSpecCaptor.getValue().protocol());
        assertEquals(telemetry.stream().uri(), feedSpecCaptor.getValue().source(),
                "the feed's destination and the RX device's own listen uri must be the exact same udp://host:port "
                        + "-- MavlinkFeedTransmitter#source() is a destination, not a source file (unlike rtsp/mjpeg)");
    }

    @Test
    void simulateWithMavlinkTelemetryTransportMapsThePlanRouteAndSpeedIntoFeedOptions(@TempDir Path tempDir)
            throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        stubCreate();
        List<Waypoint> route = List.of(new Waypoint(50.45, 30.52, null), new Waypoint(50.46, 30.53, 120.0));
        TelemetryPlan plan = new TelemetryPlan(15.0, RouteMode.LOOP, route);
        SimulationSpec spec = new SimulationSpec("My Drone", file.toString(), null, null, false,
                SimulationTransport.DIRECT, plan, TelemetryTransport.MAVLINK);

        service.simulate(spec, ownership, actor);

        ArgumentCaptor<FeedSpec> feedSpecCaptor = ArgumentCaptor.forClass(FeedSpec.class);
        verify(mavlinkTransmitter).start(any(), feedSpecCaptor.capture());
        assertEquals(Map.of("route", "50.45,30.52;50.46,30.53,120.0", "sysid", "1", "speedMps", "15.0"),
                feedSpecCaptor.getValue().options());
    }

    @Test
    void simulateWithMavlinkTelemetryTransportIgnoresAnUnsupportedRouteModeAndStillStartsTheFeed(
            @TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        stubCreate();
        List<Waypoint> route = List.of(new Waypoint(1.0, 2.0, null), new Waypoint(3.0, 4.0, null));
        TelemetryPlan plan = new TelemetryPlan(null, RouteMode.BOUNCE, route);
        SimulationSpec spec = new SimulationSpec("My Drone", file.toString(), null, null, false,
                SimulationTransport.DIRECT, plan, TelemetryTransport.MAVLINK);

        assertDoesNotThrow(() -> service.simulate(spec, ownership, actor));

        ArgumentCaptor<FeedSpec> feedSpecCaptor = ArgumentCaptor.forClass(FeedSpec.class);
        verify(mavlinkTransmitter).start(any(), feedSpecCaptor.capture());
        assertEquals(Map.of("route", "1.0,2.0;3.0,4.0", "sysid", "1"), feedSpecCaptor.getValue().options(),
                "mode=BOUNCE is not supported by MavlinkFeedTransmitter's LOOP-only route engine -- it must be "
                        + "dropped (honestly, via a WARNING log), not mapped to any option and not thrown on");
    }

    @Test
    void simulateWithMavlinkTelemetryTransportSynthesizesATwoPointRouteFromBareLatLonWhenNoPlanIsGiven(
            @TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        stubCreate();
        SimulationSpec spec = new SimulationSpec("My Drone", file.toString(), 50.45, 30.52, false,
                SimulationTransport.DIRECT, null, TelemetryTransport.MAVLINK);

        service.simulate(spec, ownership, actor);

        ArgumentCaptor<FeedSpec> feedSpecCaptor = ArgumentCaptor.forClass(FeedSpec.class);
        verify(mavlinkTransmitter).start(any(), feedSpecCaptor.capture());
        double offsetLat = 50.45 + DefaultSimulationService.MAVLINK_FALLBACK_ROUTE_OFFSET_DEGREES;
        String expectedRoute = 50.45 + "," + 30.52 + ";" + offsetLat + "," + 30.52;
        assertEquals(expectedRoute, feedSpecCaptor.getValue().options().get("route"),
                "MavlinkFeedTransmitter's route option is required (no circular-track fallback of its own) -- a "
                        + "bare lat/lon with no plan must still synthesize a minimal (moving) two-point route");
    }

    @Test
    void simulateWithMavlinkTelemetryTransportSynthesizesARouteFromTheDefaultCenterWhenNeitherPlanNorLatLonIsGiven(
            @TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        stubCreate();
        SimulationSpec spec = new SimulationSpec("My Drone", file.toString(), null, null, false,
                SimulationTransport.DIRECT, null, TelemetryTransport.MAVLINK);

        service.simulate(spec, ownership, actor);

        ArgumentCaptor<FeedSpec> feedSpecCaptor = ArgumentCaptor.forClass(FeedSpec.class);
        verify(mavlinkTransmitter).start(any(), feedSpecCaptor.capture());
        double lat = DefaultSimulationService.MAVLINK_FALLBACK_LATITUDE;
        double lon = DefaultSimulationService.MAVLINK_FALLBACK_LONGITUDE;
        double offsetLat = lat + DefaultSimulationService.MAVLINK_FALLBACK_ROUTE_OFFSET_DEGREES;
        String expectedRoute = lat + "," + lon + ";" + offsetLat + "," + lon;
        assertEquals(expectedRoute, feedSpecCaptor.getValue().options().get("route"));
    }

    @Test
    void mavlinkTelemetryTransportComposesWithRtspVideoTransport(@TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        stubCreate();
        // Protocol-scoped, unlike the rtsp-only tests' blanket any()->true stub: this test's video
        // FeedSpec (rtsp) and telemetry FeedSpec (mavlink) both flow through the same registry, so a
        // blanket stub would wrongly make feedTransmitter "support" (and be selected for) the mavlink
        // spec too, since it is probed first in registration order.
        stubFeedTransmitterSupportsRtspOnly();
        when(feedTransmitter.start(any(), any()))
                .thenReturn(new StreamDescriptor("rtsp", URI.create("rtsp://localhost:8554/feed-both"), Map.of()));
        SimulationSpec spec = new SimulationSpec("My Drone", file.toString(), null, null, false,
                SimulationTransport.RTSP, null, TelemetryTransport.MAVLINK);

        service.simulate(spec, ownership, actor);

        AssetSpec created = capturedAssetSpec();
        assertEquals("rtsp", created.devices().get(0).stream().protocol());
        assertEquals("mavlink", created.devices().get(1).stream().protocol());
        verify(feedTransmitter).start(any(), any());
        verify(mavlinkTransmitter).start(any(), any());
    }

    @Test
    void mavlinkTelemetryTransportComposesWithANullVideoPathSyntheticSimulation() {
        stubCreate();
        SimulationSpec spec = new SimulationSpec("My Drone", null, null, null, false, SimulationTransport.DIRECT,
                null, TelemetryTransport.MAVLINK);

        service.simulate(spec, ownership, actor);

        AssetSpec created = capturedAssetSpec();
        assertEquals("sim", created.devices().get(0).stream().protocol());
        assertEquals("mavlink", created.devices().get(1).stream().protocol());
        verify(mavlinkTransmitter).start(any(), any());
    }

    @Test
    void stopStopsTheMavlinkTelemetryFeedViaTheTransmitterThatStartedIt(@TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        Asset created = stubCreate();
        SimulationSpec spec = new SimulationSpec("My Drone", file.toString(), null, null, false,
                SimulationTransport.DIRECT, null, TelemetryTransport.MAVLINK);
        service.simulate(spec, ownership, actor);
        ArgumentCaptor<FeedId> feedIdCaptor = ArgumentCaptor.forClass(FeedId.class);
        verify(mavlinkTransmitter).start(feedIdCaptor.capture(), any());

        service.stop(created.id());

        verify(assetService).stopStream(created.id());
        verify(mavlinkTransmitter).stop(feedIdCaptor.getValue());
        verify(feedTransmitter, never()).stop(any());
    }

    @Test
    void simulateStopsTheMavlinkTelemetryFeedWhenAssetServiceCreateThrows(@TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        when(assetService.create(any(), any(), any())).thenThrow(new IllegalStateException("category vanished"));
        SimulationSpec spec = new SimulationSpec("My Drone", file.toString(), null, null, false,
                SimulationTransport.DIRECT, null, TelemetryTransport.MAVLINK);

        assertThrows(IllegalStateException.class, () -> service.simulate(spec, ownership, actor));

        ArgumentCaptor<FeedId> feedIdCaptor = ArgumentCaptor.forClass(FeedId.class);
        verify(mavlinkTransmitter).start(feedIdCaptor.capture(), any());
        verify(mavlinkTransmitter).stop(feedIdCaptor.getValue());
    }

    @Test
    void simulateStopsTheMavlinkTelemetryFeedWhenStartStreamThrows(@TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        Asset created = stubCreate();
        when(assetService.startStream(any(), any(), any())).thenThrow(new IllegalStateException("device offline"));
        SimulationSpec spec = new SimulationSpec("My Drone", file.toString(), null, null, true,
                SimulationTransport.DIRECT, null, TelemetryTransport.MAVLINK);

        assertThrows(IllegalStateException.class, () -> service.simulate(spec, ownership, actor));

        ArgumentCaptor<FeedId> feedIdCaptor = ArgumentCaptor.forClass(FeedId.class);
        verify(mavlinkTransmitter).start(feedIdCaptor.capture(), any());
        verify(mavlinkTransmitter).stop(feedIdCaptor.getValue());

        // The feed must have been untracked too: stopping the (failed) asset afterwards must not
        // attempt to stop the same feed a second time.
        service.stop(created.id());
        verify(mavlinkTransmitter, times(1)).stop(feedIdCaptor.getValue());
    }

    @Test
    void simulateStopsTheAlreadyStartedVideoFeedWhenMavlinkTelemetryWiringFailsAfterward(@TempDir Path tempDir)
            throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        stubFeedTransmitterSupportsRtspOnly(); // must not also shadow the mavlink spec, see the test above
        when(feedTransmitter.start(any(), any()))
                .thenReturn(new StreamDescriptor("rtsp", URI.create("rtsp://localhost:8554/feed-video-only"), Map.of()));
        when(mavlinkTransmitter.supports(any())).thenReturn(false); // no transmitter supports mavlink this time
        SimulationSpec spec = new SimulationSpec("My Drone", file.toString(), null, null, false,
                SimulationTransport.RTSP, null, TelemetryTransport.MAVLINK);

        assertThrows(IllegalArgumentException.class, () -> service.simulate(spec, ownership, actor));

        ArgumentCaptor<FeedId> feedIdCaptor = ArgumentCaptor.forClass(FeedId.class);
        verify(feedTransmitter).start(feedIdCaptor.capture(), any());
        verify(feedTransmitter).stop(feedIdCaptor.getValue());
        verifyNoInteractions(assetService);
    }

    @Test
    void resumeAllNeverResumesAMavlinkTelemetryDeviceEvenAlongsideAResumableRtspVideoDevice(@TempDir Path tempDir)
            throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        FeedId videoFeedId = FeedId.random();
        Device videoDevice = rtspVideoDevice(feedUri(videoFeedId));
        Device mavlinkTelemetryDevice = new Device(DeviceId.random(), "drone · telemetry",
                Set.of(Capability.TELEMETRY),
                new StreamDescriptor("mavlink", URI.create("udp://127.0.0.1:55000"), Map.of("sysid", "1")));
        Asset asset = simulatedAsset(videoDevice, Map.of("source", file.toString()));
        AssetSummary summary = summaryOf(asset);
        when(assetService.assets()).thenReturn(List.of(summary));
        when(assetService.details(asset.id()))
                .thenReturn(new AssetDetails(summary, List.of(videoDevice, mavlinkTelemetryDevice), List.of()));
        when(feedTransmitter.supports(any())).thenReturn(true);

        List<AssetId> resumed = service.resumeAll();

        assertEquals(List.of(asset.id()), resumed);
        verify(feedTransmitter).start(eq(videoFeedId), any());
        verifyNoInteractions(mavlinkTransmitter);
    }

    // --- resumeAll() -------------------------------------------------------------

    @Test
    void resumeAllRestartsTheFeedForAPersistedOwnRtspAsset(@TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        FeedId feedId = FeedId.random();
        Device videoDevice = rtspVideoDevice(feedUri(feedId));
        Asset asset = simulatedAsset(videoDevice, Map.of("source", file.toString()));
        stubFleetOf(asset, videoDevice);
        when(feedTransmitter.supports(any())).thenReturn(true);

        List<AssetId> resumed = service.resumeAll();

        assertEquals(List.of(asset.id()), resumed);
        ArgumentCaptor<FeedSpec> specCaptor = ArgumentCaptor.forClass(FeedSpec.class);
        verify(feedTransmitter).start(eq(feedId), specCaptor.capture());
        assertEquals("rtsp", specCaptor.getValue().protocol());
        assertEquals(file.toUri(), specCaptor.getValue().source());
    }

    @Test
    void resumeAllSkipsANonSimulatedAsset(@TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        Device videoDevice = rtspVideoDevice(feedUri(FeedId.random()));
        Asset asset = new Asset(AssetId.random(), "real drone", new CategoryId("drone"), ownership,
                Set.of(videoDevice.id()), Map.of("source", file.toString()));
        when(assetService.assets()).thenReturn(List.of(summaryOf(asset)));

        List<AssetId> resumed = service.resumeAll();

        assertEquals(List.of(), resumed);
        verify(assetService, never()).details(any());
        verifyNoInteractions(feedTransmitter);
    }

    @Test
    void resumeAllSkipsADeactivatedAsset(@TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        Device videoDevice = rtspVideoDevice(feedUri(FeedId.random()));
        Asset asset = new Asset(AssetId.random(), "drone", SIMULATED, ownership, Set.of(videoDevice.id()),
                Map.of("source", file.toString()), LifecycleState.DEACTIVATED);
        when(assetService.assets()).thenReturn(List.of(summaryOf(asset)));

        List<AssetId> resumed = service.resumeAll();

        assertEquals(List.of(), resumed);
        verifyNoInteractions(feedTransmitter);
    }

    @Test
    void resumeAllSkipsAnAssetWithNoRtspVideoDevice() {
        Device fileVideoDevice = new Device(DeviceId.random(), "drone · video", Set.of(Capability.VIDEO),
                new StreamDescriptor("file", URI.create("file:///tmp/clip.mp4"), Map.of("loop", "true")));
        Asset asset = simulatedAsset(fileVideoDevice, Map.of("source", "/tmp/clip.mp4"));
        stubFleetOf(asset, fileVideoDevice);

        List<AssetId> resumed = service.resumeAll();

        assertEquals(List.of(), resumed);
        verifyNoInteractions(feedTransmitter);
    }

    @Test
    void resumeAllSkipsAnRtspDeviceThatIsNotOurOwnMediamtxBase() {
        Device foreignDevice = rtspVideoDevice(URI.create("rtsp://some-real-camera.example:554/live"));
        Asset asset = simulatedAsset(foreignDevice, Map.of("source", "/tmp/clip.mp4"));
        stubFleetOf(asset, foreignDevice);

        List<AssetId> resumed = service.resumeAll();

        assertEquals(List.of(), resumed);
        verifyNoInteractions(feedTransmitter);
    }

    @Test
    void resumeAllSkipsWhenTheSourceAttributeIsMissing() {
        Device videoDevice = rtspVideoDevice(feedUri(FeedId.random()));
        Asset asset = simulatedAsset(videoDevice, Map.of());
        stubFleetOf(asset, videoDevice);

        List<AssetId> resumed = service.resumeAll();

        assertEquals(List.of(), resumed);
        verifyNoInteractions(feedTransmitter);
    }

    @Test
    void resumeAllSkipsWhenTheSourceFileNoLongerExists() {
        Device videoDevice = rtspVideoDevice(feedUri(FeedId.random()));
        Asset asset = simulatedAsset(videoDevice, Map.of("source", "/no/such/file-any-more.mp4"));
        stubFleetOf(asset, videoDevice);

        List<AssetId> resumed = service.resumeAll();

        assertEquals(List.of(), resumed);
        verifyNoInteractions(feedTransmitter);
    }

    @Test
    void resumeAllSkipsWhenTheUriHasNoParseableFeedId() {
        Device videoDevice = rtspVideoDevice(URI.create(MEDIAMTX_RTSP_BASE + "/not-a-feed-path"));
        Asset asset = simulatedAsset(videoDevice, Map.of("source", "/tmp/clip.mp4"));
        stubFleetOf(asset, videoDevice);

        List<AssetId> resumed = service.resumeAll();

        assertEquals(List.of(), resumed);
        verifyNoInteractions(feedTransmitter);
    }

    @Test
    void resumeAllSkipsWhenNoTransmitterSupportsTheRebuiltFeed(@TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        Device videoDevice = rtspVideoDevice(feedUri(FeedId.random()));
        Asset asset = simulatedAsset(videoDevice, Map.of("source", file.toString()));
        stubFleetOf(asset, videoDevice);
        when(feedTransmitter.supports(any())).thenReturn(false);
        when(mjpegTransmitter.supports(any())).thenReturn(false);

        List<AssetId> resumed = service.resumeAll();

        assertEquals(List.of(), resumed);
        verify(feedTransmitter, never()).start(any(), any());
    }

    @Test
    void resumeAllIsIdempotentAndNeverRestartsAnAlreadyTrackedAssetTwice(@TempDir Path tempDir) throws IOException {
        Path file = videoFile(tempDir, "clip.mp4");
        FeedId feedId = FeedId.random();
        Device videoDevice = rtspVideoDevice(feedUri(feedId));
        Asset asset = simulatedAsset(videoDevice, Map.of("source", file.toString()));
        stubFleetOf(asset, videoDevice);
        when(feedTransmitter.supports(any())).thenReturn(true);

        List<AssetId> first = service.resumeAll();
        List<AssetId> second = service.resumeAll();

        assertEquals(List.of(asset.id()), first);
        assertEquals(List.of(), second, "already tracked -- the second call must not restart it again");
        verify(feedTransmitter, times(1)).start(eq(feedId), any());
    }

    private Device rtspVideoDevice(URI uri) {
        return new Device(DeviceId.random(), "drone · video", Set.of(Capability.VIDEO),
                new StreamDescriptor("rtsp", uri, Map.of()));
    }

    private URI feedUri(FeedId feedId) {
        return URI.create(MEDIAMTX_RTSP_BASE + "/feed-" + feedId.value());
    }

    private Asset simulatedAsset(Device videoDevice, Map<String, String> attributes) {
        return new Asset(AssetId.random(), "drone", SIMULATED, ownership, Set.of(videoDevice.id()), attributes);
    }

    private AssetSummary summaryOf(Asset asset) {
        return new AssetSummary(asset, "Simulated", AssetStatus.OFFLINE, null, null);
    }

    /** Stubs {@link #assetService} so {@code resumeAll} sees exactly one asset, with one device. */
    private void stubFleetOf(Asset asset, Device device) {
        AssetSummary summary = summaryOf(asset);
        when(assetService.assets()).thenReturn(List.of(summary));
        when(assetService.details(asset.id())).thenReturn(new AssetDetails(summary, List.of(device), List.of()));
    }

    // --- Helpers -------------------------------------------------------------

    private Path videoFile(Path dir, String name) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, "not a real video, just enough bytes to exist and be readable");
        return file;
    }

    /**
     * Restricts {@link #feedTransmitter}'s {@code supports()} stub to rtsp-protocol specs only --
     * needed whenever a test's video (rtsp) and telemetry (mavlink) {@link FeedSpec}s both flow
     * through the same {@link FeedTransmitterRegistry} in one call: the pre-existing blanket {@code
     * any()->true} idiom other tests in this file use is only safe when exactly one FeedSpec
     * protocol is ever probed per test, since the registry selects the first transmitter (in
     * registration order) whose {@code supports()} returns {@code true}.
     */
    private void stubFeedTransmitterSupportsRtspOnly() {
        when(feedTransmitter.supports(any())).thenAnswer(invocation ->
                "rtsp".equals(invocation.getArgument(0, FeedSpec.class).protocol()));
    }

    private Asset stubCreate() {
        Asset created = new Asset(AssetId.random(), "placeholder", SIMULATED, ownership,
                Set.of(DeviceId.random(), DeviceId.random()), Map.of());
        when(assetService.create(any(), eq(ownership), eq(actor))).thenReturn(created);
        return created;
    }

    private AssetSpec capturedAssetSpec() {
        ArgumentCaptor<AssetSpec> captor = ArgumentCaptor.forClass(AssetSpec.class);
        verify(assetService).create(captor.capture(), eq(ownership), eq(actor));
        return captor.getValue();
    }
}
