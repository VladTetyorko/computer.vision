package com.drones.vision.application.fleet;

import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.events.domain.model.DetectionEvent;
import com.drones.vision.events.domain.model.DetectionEventId;
import com.drones.vision.events.domain.model.DetectionEventState;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.flight.domain.model.FlightState;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.flight.domain.model.Telemetry;
import com.drones.vision.kernel.UserId;
import com.drones.vision.events.domain.port.DetectionEventRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.drones.vision.application.asset.AssetAttention;
import com.drones.vision.application.asset.AssetService;
import com.drones.vision.application.asset.AssetStatus;
import com.drones.vision.application.asset.AssetSummary;
import com.drones.vision.application.category.CategoryCounts;
import com.drones.vision.application.pipeline.UsageTracker;
import com.drones.vision.application.scope.VisibilityScope;
import com.drones.vision.application.stream.ActiveStream;
import com.drones.vision.application.stream.StreamService;

class DefaultFleetSummaryServiceTest {

    private static final CategoryId DRONE = new CategoryId("drone");
    private static final CategoryId ROBOT = new CategoryId("robot");

    private AssetService assetService;
    private StreamService streamService;
    private UsageTracker usageTracker;
    private DetectionEventRepositoryPort detectionEventRepositoryPort;
    private DefaultFleetSummaryService service;
    private Ownership ownership;

    @BeforeEach
    void setUp() {
        assetService = mock(AssetService.class);
        streamService = mock(StreamService.class);
        usageTracker = mock(UsageTracker.class);
        detectionEventRepositoryPort = mock(DetectionEventRepositoryPort.class);
        service = new DefaultFleetSummaryService(assetService, streamService, usageTracker, detectionEventRepositoryPort);
        ownership = new Ownership(UserId.random(), GroupId.random());

        when(streamService.streams()).thenReturn(List.of());
        when(usageTracker.latestTelemetry(any())).thenReturn(Optional.empty());
        when(detectionEventRepositoryPort.findRecent(any(), anyInt())).thenReturn(List.of());
    }

    private Asset asset(String displayName, CategoryId category, LifecycleState state, DeviceId... devices) {
        return new Asset(AssetId.random(), displayName, category, ownership, Set.of(devices), Map.of(), state);
    }

    private static AssetSummary summary(Asset asset, String categoryName, AssetStatus status) {
        return new AssetSummary(asset, categoryName, status, null, null);
    }

    @Test
    void summaryOnAnEmptyFleetReturnsEmptyListsAndZeroTotals() {
        when(assetService.assets(false)).thenReturn(List.of());

        FleetSummary result = service.summary(false);

        assertTrue(result.categories().isEmpty());
        assertTrue(result.assets().isEmpty());
        assertEquals(0, result.totalAssets());
    }

    @Test
    void summaryPassesIncludeArchivedThroughToAssetService() {
        when(assetService.assets(true)).thenReturn(List.of());

        service.summary(true);

        verify(assetService).assets(true);
    }

    @Test
    void scopedSummaryAggregatesOnlyTheScopedAssetSet() {
        VisibilityScope scope = VisibilityScope.groups(Set.of(GroupId.random()));
        Asset drone = asset("d", DRONE, LifecycleState.ACTIVE, DeviceId.random());
        when(assetService.assets(scope, false)).thenReturn(List.of(summary(drone, "Drone", AssetStatus.OFFLINE)));

        FleetSummary result = service.summary(scope, false);

        verify(assetService).assets(scope, false);
        assertEquals(1, result.totalAssets());
        assertEquals(1, result.categories().size());
    }

    @Test
    void summaryComposesPerCategoryLifecycleAndStreamingCounts() {
        Asset droneStreaming = asset("Drone A", DRONE, LifecycleState.ACTIVE, DeviceId.random());
        Asset droneOffline = asset("Drone B", DRONE, LifecycleState.ACTIVE, DeviceId.random());
        Asset droneDeactivated = asset("Drone C", DRONE, LifecycleState.DEACTIVATED, DeviceId.random());
        Asset robotDeleted = asset("Robot A", ROBOT, LifecycleState.DELETED, DeviceId.random());

        when(assetService.assets(true)).thenReturn(List.of(
                summary(droneStreaming, "Drone", AssetStatus.STREAMING),
                summary(droneOffline, "Drone", AssetStatus.OFFLINE),
                summary(droneDeactivated, "Drone", AssetStatus.OFFLINE),
                summary(robotDeleted, "Robot", AssetStatus.OFFLINE)));

        FleetSummary result = service.summary(true);

        assertEquals(2, result.categories().size());
        CategoryCounts drone = result.categories().get(0); // "drone" sorts before "robot"
        assertEquals(DRONE, drone.categoryId());
        assertEquals(3, drone.total());
        assertEquals(2, drone.active());
        assertEquals(1, drone.deactivated());
        assertEquals(0, drone.deleted());
        assertEquals(1, drone.streaming());

        CategoryCounts robot = result.categories().get(1);
        assertEquals(ROBOT, robot.categoryId());
        assertEquals(1, robot.total());
        assertEquals(0, robot.active());
        assertEquals(0, robot.deactivated());
        assertEquals(1, robot.deleted());
        assertEquals(0, robot.streaming());
    }

    @Test
    void summaryAssetsAreSortedByDisplayNameCaseInsensitively() {
        when(assetService.assets(false)).thenReturn(List.of(
                summary(asset("Zephyr", DRONE, LifecycleState.ACTIVE, DeviceId.random()), "Drone", AssetStatus.OFFLINE),
                summary(asset("alpha", DRONE, LifecycleState.ACTIVE, DeviceId.random()), "Drone", AssetStatus.OFFLINE),
                summary(asset("Bravo", DRONE, LifecycleState.ACTIVE, DeviceId.random()), "Drone", AssetStatus.OFFLINE)));

        FleetSummary result = service.summary(false);

        assertEquals(List.of("alpha", "Bravo", "Zephyr"),
                result.assets().stream().map(AssetAttention::displayName).toList());
    }

    @Test
    void summaryCapsThePerAssetListButReportsTheTrueTotal() {
        List<AssetSummary> summaries = new ArrayList<>();
        int count = DefaultFleetSummaryService.MAX_ASSETS_IN_SUMMARY + 1;
        for (int i = 0; i < count; i++) {
            summaries.add(summary(asset("asset-" + i, DRONE, LifecycleState.ACTIVE, DeviceId.random()), "Drone",
                    AssetStatus.OFFLINE));
        }
        when(assetService.assets(false)).thenReturn(summaries);

        FleetSummary result = service.summary(false);

        assertEquals(DefaultFleetSummaryService.MAX_ASSETS_IN_SUMMARY, result.assets().size());
        assertEquals(count, result.totalAssets());
        assertEquals(count, result.categories().get(0).total(),
                "category counts must reflect the full fleet, independent of the per-asset list's cap");
    }

    @Test
    void summaryBuildsAnAttentionRowWithStreamIdBatteryTelemetryAgeAndOpenEventCount() {
        DeviceId deviceId = DeviceId.random();
        Asset asset = asset("Drone A", DRONE, LifecycleState.ACTIVE, deviceId);
        when(assetService.assets(false)).thenReturn(List.of(summary(asset, "Drone", AssetStatus.STREAMING)));

        StreamId streamId = StreamId.random();
        when(streamService.streams()).thenReturn(List.of(new ActiveStream(streamId, deviceId, Instant.now())));

        Instant sampleAt = Instant.now().minusMillis(3000);
        FlightState flightState = new FlightState("ardupilot", "RTL", true, true, 3, 12, 0.9, 87, List.of());
        Telemetry sample = new Telemetry(deviceId, sampleAt, 1.0, 2.0, null, null, 42.0, Map.of(), flightState);
        when(usageTracker.latestTelemetry(asset.id())).thenReturn(Optional.of(sample));

        DetectionEvent openForThisAsset = new DetectionEvent(DetectionEventId.random(), streamId, asset.id(),
                "person", 0.9, Instant.now(), Instant.now(), DetectionEventState.OPEN, null);
        DetectionEvent closedForThisAsset = new DetectionEvent(DetectionEventId.random(), streamId, asset.id(),
                "car", 0.9, Instant.now(), Instant.now(), DetectionEventState.CLOSED, null);
        DetectionEvent openForAnotherAsset = new DetectionEvent(DetectionEventId.random(), streamId, AssetId.random(),
                "person", 0.9, Instant.now(), Instant.now(), DetectionEventState.OPEN, null);
        DetectionEvent openWithUnresolvableAsset = new DetectionEvent(DetectionEventId.random(), streamId, null,
                "person", 0.9, Instant.now(), Instant.now(), DetectionEventState.OPEN, null);
        when(detectionEventRepositoryPort.findRecent(any(), anyInt())).thenReturn(
                List.of(openForThisAsset, closedForThisAsset, openForAnotherAsset, openWithUnresolvableAsset));

        FleetSummary result = service.summary(false);

        assertEquals(1, result.assets().size());
        AssetAttention row = result.assets().get(0);
        assertEquals(asset.id(), row.assetId());
        assertTrue(row.streaming());
        assertEquals(streamId, row.streamId());
        assertEquals(42.0, row.batteryPercent());
        assertTrue(row.telemetryAgeMs() >= 2900 && row.telemetryAgeMs() < 15000,
                "telemetryAgeMs should reflect roughly 3s since the sample: " + row.telemetryAgeMs());
        assertEquals(1, row.openEventCount(), "only THIS asset's OPEN event must be counted");
        assertEquals("RTL", row.flightMode());
        assertEquals(true, row.armed());
        assertEquals(true, row.failsafe());
    }

    @Test
    void summaryLeavesFlightModeArmedAndFailsafeAbsentWhenTelemetryHasNoFlightState() {
        DeviceId deviceId = DeviceId.random();
        Asset asset = asset("Drone C", DRONE, LifecycleState.ACTIVE, deviceId);
        when(assetService.assets(false)).thenReturn(List.of(summary(asset, "Drone", AssetStatus.OFFLINE)));

        Telemetry sample = new Telemetry(deviceId, Instant.now(), 1.0, 2.0, null, null, 42.0, Map.of());
        when(usageTracker.latestTelemetry(asset.id())).thenReturn(Optional.of(sample));

        FleetSummary result = service.summary(false);

        AssetAttention row = result.assets().get(0);
        assertNull(row.flightMode());
        assertNull(row.armed());
        assertNull(row.failsafe());
    }

    @Test
    void summaryLeavesBatteryTelemetryAgeAndStreamIdAbsentWhenUnavailable() {
        Asset asset = asset("Drone B", DRONE, LifecycleState.ACTIVE, DeviceId.random());
        when(assetService.assets(false)).thenReturn(List.of(summary(asset, "Drone", AssetStatus.OFFLINE)));

        FleetSummary result = service.summary(false);

        AssetAttention row = result.assets().get(0);
        assertTrue(!row.streaming());
        assertNull(row.streamId());
        assertNull(row.batteryPercent());
        assertNull(row.telemetryAgeMs());
        assertEquals(0, row.openEventCount());
        assertNull(row.flightMode());
        assertNull(row.armed());
        assertNull(row.failsafe());
    }
}
