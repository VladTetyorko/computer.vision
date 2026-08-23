package com.drones.vision.app.geo;

import com.drones.vision.app.config.properties.VisionGeoProperties;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.map.application.track.CameraPoseService;
import com.drones.vision.map.application.track.TrackProjectionInput;
import com.drones.vision.map.application.track.TrackProjectionService;
import com.drones.vision.map.domain.model.CameraPose;
import com.drones.vision.map.domain.model.CameraPoseSource;
import com.drones.vision.perception.application.stream.ActiveStream;
import com.drones.vision.perception.application.stream.StreamService;
import com.drones.vision.perception.domain.model.PixelFormat;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.warehouse.application.asset.AssetDetails;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetStatus;
import com.drones.vision.warehouse.application.asset.AssetSummary;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Device;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TrackProjectionRunner} (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md D2/D3/D7,
 * Wave G4) — real background scheduler throughout, driven at a fast tick interval and observed by
 * polling, the same style {@code CvChannelSupervisorTest} (this codebase's only other self-scheduled
 * bean) already uses rather than reaching for a fake clock.
 */
class TrackProjectionRunnerTest {

    private static final long FAST_TICK_MILLIS = 20;

    private final List<TrackProjectionRunner> runners = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (TrackProjectionRunner runner : runners) {
            runner.close();
        }
    }

    private TrackProjectionRunner newRunner(AssetService assetService, StreamService streamService,
                                             CameraPoseService cameraPoseService,
                                             TrackProjectionService trackProjectionService) {
        TrackProjectionRunner runner = new TrackProjectionRunner(assetService, streamService, cameraPoseService,
                trackProjectionService, properties());
        runners.add(runner);
        return runner;
    }

    private static VisionGeoProperties properties() {
        return new VisionGeoProperties(true, FAST_TICK_MILLIS, 1.0, 500.0, 0.5, 100.0,
                new VisionGeoProperties.Trail(2.0, 600, Duration.ofMinutes(30)),
                new VisionGeoProperties.Calibration(25.0));
    }

    private static CameraPose pose(AssetId assetId) {
        return new CameraPose(assetId, new GeoPosition(50.45, 30.52, null), 12.0, 90.0, 30.0, 60.0, null,
                CameraPoseSource.MANUAL, null, Instant.now(), UserId.random());
    }

    private static AssetDetails details(AssetId assetId, DeviceId deviceId) {
        Asset asset = new Asset(assetId, "camera-1", new CategoryId("camera"),
                new Ownership(UserId.random(), GroupId.random()), Set.of(deviceId), Map.of());
        AssetSummary summary = new AssetSummary(asset, "Camera", AssetStatus.OFFLINE, null, null);
        Device device = new Device(deviceId, "cam-1", Set.of(),
                new StreamDescriptor("sim", URI.create("sim://cam-1"), Map.of()), LifecycleState.ACTIVE);
        return new AssetDetails(summary, List.of(device), List.of());
    }

    private static VideoFrame frame(StreamId streamId, int width, int height) {
        return new VideoFrame(streamId, 0L, Instant.now(), width, height, PixelFormat.JPEG, ByteBuffer.allocate(0));
    }

    private static void awaitTrue(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        fail(message);
    }

    private static boolean verifiedProject(TrackProjectionService trackProjectionService) {
        try {
            verify(trackProjectionService, atLeastOnce()).project(any());
            return true;
        } catch (AssertionError e) {
            return false;
        }
    }

    private static boolean verifiedClearAsset(TrackProjectionService trackProjectionService, AssetId assetId) {
        try {
            verify(trackProjectionService, atLeastOnce()).clearAsset(assetId);
            return true;
        } catch (AssertionError e) {
            return false;
        }
    }

    private static boolean verifiedPruneTrail(TrackProjectionService trackProjectionService) {
        try {
            verify(trackProjectionService, atLeastOnce()).pruneTrail(any());
            return true;
        } catch (AssertionError e) {
            return false;
        }
    }

    @Test
    void hasCameraPoseReflectsTheMostRecentTickEvenWhenTheStreamNeverResolves() throws Exception {
        AssetId posedAssetId = AssetId.random();
        CameraPoseService cameraPoseService = mock(CameraPoseService.class);
        when(cameraPoseService.list()).thenReturn(List.of(pose(posedAssetId)));
        AssetService assetService = mock(AssetService.class);
        when(assetService.details(posedAssetId)).thenThrow(new NoSuchElementException("orphaned pose"));
        StreamService streamService = mock(StreamService.class);
        when(streamService.streams()).thenReturn(List.of());
        TrackProjectionService trackProjectionService = mock(TrackProjectionService.class);

        TrackProjectionRunner runner = newRunner(assetService, streamService, cameraPoseService,
                trackProjectionService);
        runner.start();

        awaitTrue(() -> runner.hasCameraPose(posedAssetId),
                "hasCameraPose must be populated from CameraPoseService#list() even when the asset's "
                        + "stream never resolves -- D9's cache is independent of projection success");
        assertFalse(runner.hasCameraPose(AssetId.random()), "an asset never returned by list() must not be cached");
    }

    @Test
    void projectsAnAssetWithAResolvableActiveStream() throws Exception {
        AssetId assetId = AssetId.random();
        DeviceId deviceId = DeviceId.random();
        StreamId streamId = StreamId.random();
        CameraPose pose = pose(assetId);

        CameraPoseService cameraPoseService = mock(CameraPoseService.class);
        when(cameraPoseService.list()).thenReturn(List.of(pose));
        AssetService assetService = mock(AssetService.class);
        when(assetService.details(assetId)).thenReturn(details(assetId, deviceId));
        StreamService streamService = mock(StreamService.class);
        when(streamService.streams()).thenReturn(List.of(new ActiveStream(streamId, deviceId, Instant.now())));
        when(streamService.latestRawFrame(streamId)).thenReturn(Optional.of(frame(streamId, 1920, 1080)));
        when(streamService.tracks(streamId)).thenReturn(List.of());
        TrackProjectionService trackProjectionService = mock(TrackProjectionService.class);

        TrackProjectionRunner runner = newRunner(assetService, streamService, cameraPoseService,
                trackProjectionService);
        runner.start();

        awaitTrue(() -> verifiedProject(trackProjectionService),
                "project should be called once the asset's active stream resolves");

        ArgumentCaptor<TrackProjectionInput> captor = ArgumentCaptor.forClass(TrackProjectionInput.class);
        verify(trackProjectionService, atLeastOnce()).project(captor.capture());
        TrackProjectionInput input = captor.getValue();
        assertEquals(pose, input.pose());
        assertEquals(1920, input.imageWidthPixels());
        assertEquals(1080, input.imageHeightPixels());
        assertTrue(input.tracks().isEmpty());
    }

    @Test
    void clearsAnAssetOnceItsPoseStopsBeingReturnedByList() throws Exception {
        // A found G2 gap this runner closes: CameraPoseService#delete never touches
        // TrackProjectionService, so this tick-over-tick comparison is the only thing that ever
        // notices a pose disappearing.
        AssetId assetId = AssetId.random();
        DeviceId deviceId = DeviceId.random();
        StreamId streamId = StreamId.random();

        CameraPoseService cameraPoseService = mock(CameraPoseService.class);
        when(cameraPoseService.list()).thenReturn(List.of(pose(assetId)), List.of());
        AssetService assetService = mock(AssetService.class);
        when(assetService.details(assetId)).thenReturn(details(assetId, deviceId));
        StreamService streamService = mock(StreamService.class);
        when(streamService.streams()).thenReturn(List.of(new ActiveStream(streamId, deviceId, Instant.now())));
        when(streamService.latestRawFrame(streamId)).thenReturn(Optional.of(frame(streamId, 800, 600)));
        when(streamService.tracks(streamId)).thenReturn(List.of());
        TrackProjectionService trackProjectionService = mock(TrackProjectionService.class);

        TrackProjectionRunner runner = newRunner(assetService, streamService, cameraPoseService,
                trackProjectionService);
        runner.start();

        awaitTrue(() -> verifiedClearAsset(trackProjectionService, assetId),
                "clearAsset must be called once the pose stops appearing in list()");
    }

    @Test
    void clearsAnAssetWhoseStreamBecomesUnresolvable() throws Exception {
        // D3's own documented case: "the caller invokes clearAsset directly -- the owning stream
        // stopped" -- exercised here via streams() dropping the match while the pose itself persists.
        AssetId assetId = AssetId.random();
        DeviceId deviceId = DeviceId.random();
        StreamId streamId = StreamId.random();
        List<ActiveStream> withStream = List.of(new ActiveStream(streamId, deviceId, Instant.now()));

        CameraPoseService cameraPoseService = mock(CameraPoseService.class);
        when(cameraPoseService.list()).thenReturn(List.of(pose(assetId)));
        AssetService assetService = mock(AssetService.class);
        when(assetService.details(assetId)).thenReturn(details(assetId, deviceId));
        StreamService streamService = mock(StreamService.class);
        when(streamService.streams()).thenReturn(withStream, List.of());
        when(streamService.latestRawFrame(streamId)).thenReturn(Optional.of(frame(streamId, 800, 600)));
        when(streamService.tracks(streamId)).thenReturn(List.of());
        TrackProjectionService trackProjectionService = mock(TrackProjectionService.class);

        TrackProjectionRunner runner = newRunner(assetService, streamService, cameraPoseService,
                trackProjectionService);
        runner.start();

        awaitTrue(() -> verifiedClearAsset(trackProjectionService, assetId),
                "clearAsset must be called once the asset's stream stops resolving, even though its "
                        + "pose is still stored -- a single unresolved tick is treated as stopped, no debounce");
        assertTrue(runner.hasCameraPose(assetId),
                "the pose is still stored, so the D9 demand cache must keep reporting it regardless of "
                        + "projection success");
    }

    @Test
    void oneAssetsResolutionFailureDoesNotStopTheOthersFromBeingProjected() throws Exception {
        AssetId brokenAssetId = AssetId.random();
        AssetId healthyAssetId = AssetId.random();
        DeviceId healthyDeviceId = DeviceId.random();
        StreamId healthyStreamId = StreamId.random();

        CameraPoseService cameraPoseService = mock(CameraPoseService.class);
        when(cameraPoseService.list()).thenReturn(List.of(pose(brokenAssetId), pose(healthyAssetId)));
        AssetService assetService = mock(AssetService.class);
        when(assetService.details(brokenAssetId)).thenThrow(new RuntimeException("boom"));
        when(assetService.details(healthyAssetId)).thenReturn(details(healthyAssetId, healthyDeviceId));
        StreamService streamService = mock(StreamService.class);
        when(streamService.streams())
                .thenReturn(List.of(new ActiveStream(healthyStreamId, healthyDeviceId, Instant.now())));
        when(streamService.latestRawFrame(healthyStreamId)).thenReturn(Optional.of(frame(healthyStreamId, 640, 480)));
        when(streamService.tracks(healthyStreamId)).thenReturn(List.of());
        TrackProjectionService trackProjectionService = mock(TrackProjectionService.class);

        TrackProjectionRunner runner = newRunner(assetService, streamService, cameraPoseService,
                trackProjectionService);
        runner.start();

        awaitTrue(() -> verifiedProject(trackProjectionService),
                "the healthy asset must still be projected despite the broken one throwing on every tick");
        assertTrue(runner.hasCameraPose(brokenAssetId),
                "a per-asset resolution failure must not remove it from the demand cache -- only list() does");
    }

    @Test
    void pruneTrailIsCalledEachTickWithARetentionBasedCutoff() throws Exception {
        Instant before = Instant.now();
        CameraPoseService cameraPoseService = mock(CameraPoseService.class);
        when(cameraPoseService.list()).thenReturn(List.of());
        AssetService assetService = mock(AssetService.class);
        StreamService streamService = mock(StreamService.class);
        TrackProjectionService trackProjectionService = mock(TrackProjectionService.class);

        TrackProjectionRunner runner = newRunner(assetService, streamService, cameraPoseService,
                trackProjectionService);
        runner.start();

        awaitTrue(() -> verifiedPruneTrail(trackProjectionService), "pruneTrail should be called on every tick");

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(trackProjectionService, atLeastOnce()).pruneTrail(cutoff.capture());
        assertTrue(cutoff.getValue().isBefore(before),
                "the cutoff must be now-minus-retention (30 minutes here), strictly before this test's own start");
    }

    @Test
    void startIsIdempotent() {
        CameraPoseService cameraPoseService = mock(CameraPoseService.class);
        when(cameraPoseService.list()).thenReturn(List.of());
        TrackProjectionRunner runner = newRunner(mock(AssetService.class), mock(StreamService.class),
                cameraPoseService, mock(TrackProjectionService.class));

        runner.start();
        runner.start(); // must not double-arm the scheduler or throw
    }

    @Test
    void closeIsIdempotent() {
        CameraPoseService cameraPoseService = mock(CameraPoseService.class);
        when(cameraPoseService.list()).thenReturn(List.of());
        TrackProjectionRunner runner = newRunner(mock(AssetService.class), mock(StreamService.class),
                cameraPoseService, mock(TrackProjectionService.class));

        runner.start();
        runner.close();
        runner.close(); // idempotent -- must not throw
    }
}
