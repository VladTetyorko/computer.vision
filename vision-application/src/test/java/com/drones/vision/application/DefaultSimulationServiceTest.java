package com.drones.vision.application;

import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.DeviceCategory;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.FeedId;
import com.drones.vision.domain.model.FeedSpec;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.CategoryRepositoryPort;
import com.drones.vision.domain.port.out.FeedTransmitterPort;
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

class DefaultSimulationServiceTest {

    private static final CategoryId SIMULATED = new CategoryId("simulated");

    private AssetService assetService;
    private CategoryRepositoryPort categoryRepository;
    private FeedTransmitterPort feedTransmitter;
    private FeedTransmitterPort mjpegTransmitter;
    private SimulationService service;
    private Ownership ownership;
    private UserId actor;

    @BeforeEach
    void setUp() {
        assetService = mock(AssetService.class);
        categoryRepository = mock(CategoryRepositoryPort.class);
        feedTransmitter = mock(FeedTransmitterPort.class);
        mjpegTransmitter = mock(FeedTransmitterPort.class);
        service = new DefaultSimulationService(assetService, categoryRepository,
                new FeedTransmitterRegistry(List.of(feedTransmitter, mjpegTransmitter)));
        actor = UserId.random();
        ownership = new Ownership(actor, GroupId.random());

        when(categoryRepository.findById(SIMULATED))
                .thenReturn(Optional.of(new DeviceCategory(SIMULATED, "Simulated", null, List.of())));
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

    // --- Helpers -------------------------------------------------------------

    private Path videoFile(Path dir, String name) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, "not a real video, just enough bytes to exist and be readable");
        return file;
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
