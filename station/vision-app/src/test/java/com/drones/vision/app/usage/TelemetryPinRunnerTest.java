package com.drones.vision.app.usage;

import com.drones.vision.app.config.properties.VisionTelemetryProperties;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.perception.application.pipeline.UsageTracker;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetStatus;
import com.drones.vision.warehouse.application.asset.AssetSummary;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Identity;
import com.drones.vision.warehouse.domain.model.InventoryState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TelemetryPinRunner} (docs/plans/active/ALWAYS-ON-FLOW-PLAN.md wave A1).
 *
 * <p>Each test drives one deterministic {@code sweepSafely()} rather than racing the scheduler —
 * this runner's contract is entirely about <em>which</em> assets one pass pins and unpins, so the
 * background timing {@code UsageIdleCloseRunnerTest} exercises adds nothing here but flakiness.
 */
class TelemetryPinRunnerTest {

    private static final CategoryId DRONE = new CategoryId("drone");

    private final List<TelemetryPinRunner> runners = new ArrayList<>();
    private final Ownership ownership = new Ownership(UserId.random(), GroupId.random());

    @AfterEach
    void tearDown() {
        runners.forEach(TelemetryPinRunner::close);
    }

    private TelemetryPinRunner newRunner(AssetService assetService, UsageTracker usageTracker, boolean enabled) {
        TelemetryPinRunner runner = new TelemetryPinRunner(assetService, usageTracker,
                new VisionTelemetryProperties(
                        new VisionTelemetryProperties.AlwaysOn(enabled, Duration.ofSeconds(30))));
        runners.add(runner);
        return runner;
    }

    private AssetSummary summary(Asset asset) {
        return new AssetSummary(asset, "Drone", AssetStatus.OFFLINE, null, null, InventoryState.IN_STOCK,
                Identity.NONE, Custody.NONE);
    }

    private Asset activeAsset() {
        return Asset.register(AssetId.random(), "drone", DRONE, ownership, Set.of(), Map.of(), Identity.NONE,
                Custody.NONE);
    }

    @Test
    void oneSweepPinsEveryInServiceAsset() {
        AssetService assetService = mock(AssetService.class);
        UsageTracker usageTracker = mock(UsageTracker.class);
        Asset first = activeAsset();
        Asset second = activeAsset();
        when(assetService.assets()).thenReturn(List.of(summary(first), summary(second)));
        TelemetryPinRunner runner = newRunner(assetService, usageTracker, true);

        runner.sweepSafely();

        verify(usageTracker).pinTelemetry(first.id());
        verify(usageTracker).pinTelemetry(second.id());
        assertEquals(2, runner.pinnedCount());
    }

    @Test
    void sweepingRepeatedlyKeepsPinningTheSameAssets() {
        // pinTelemetry is idempotent by contract, and this runner relies on that rather than
        // tracking which devices it has already opened -- re-pinning is also how a device attached
        // since the last sweep gets picked up.
        AssetService assetService = mock(AssetService.class);
        UsageTracker usageTracker = mock(UsageTracker.class);
        Asset asset = activeAsset();
        when(assetService.assets()).thenReturn(List.of(summary(asset)));
        TelemetryPinRunner runner = newRunner(assetService, usageTracker, true);

        runner.sweepSafely();
        runner.sweepSafely();
        runner.sweepSafely();

        verify(usageTracker, times(3)).pinTelemetry(asset.id());
        verify(usageTracker, never()).unpinTelemetry(any());
        assertEquals(1, runner.pinnedCount());
    }

    @Test
    void anAssetThatLeavesTheListingIsUnpinned() {
        // Deletion is observed as an absence, not an event -- which is exactly why this is a
        // reconciler rather than a listener.
        AssetService assetService = mock(AssetService.class);
        UsageTracker usageTracker = mock(UsageTracker.class);
        Asset staying = activeAsset();
        Asset leaving = activeAsset();
        when(assetService.assets())
                .thenReturn(List.of(summary(staying), summary(leaving)))
                .thenReturn(List.of(summary(staying)));
        TelemetryPinRunner runner = newRunner(assetService, usageTracker, true);

        runner.sweepSafely();
        runner.sweepSafely();

        verify(usageTracker).unpinTelemetry(leaving.id());
        verify(usageTracker, never()).unpinTelemetry(staying.id());
        assertEquals(1, runner.pinnedCount());
    }

    @Test
    void aDeactivatedAssetIsUnpinned() {
        AssetService assetService = mock(AssetService.class);
        UsageTracker usageTracker = mock(UsageTracker.class);
        Asset asset = activeAsset();
        when(assetService.assets())
                .thenReturn(List.of(summary(asset)))
                .thenReturn(List.of(summary(asset.withState(LifecycleState.DEACTIVATED))));
        TelemetryPinRunner runner = newRunner(assetService, usageTracker, true);

        runner.sweepSafely();
        runner.sweepSafely();

        verify(usageTracker).unpinTelemetry(asset.id());
        assertEquals(0, runner.pinnedCount());
    }

    @Test
    void aFailingSweepIsSwallowedSoTheLoopSurvives() {
        // A repository blip must not cancel a scheduleAtFixedRate task -- the next sweep reconciles.
        AssetService assetService = mock(AssetService.class);
        UsageTracker usageTracker = mock(UsageTracker.class);
        when(assetService.assets()).thenThrow(new IllegalStateException("database down"));
        TelemetryPinRunner runner = newRunner(assetService, usageTracker, true);

        runner.sweepSafely();

        assertEquals(null, runner.lastSweepAt(), "a sweep that threw must not stamp a fresh timestamp");
        assertEquals(0, runner.pinnedCount());
    }

    @Test
    void startIsANoOpWhileTheFeatureIsDisabled() {
        AssetService assetService = mock(AssetService.class);
        UsageTracker usageTracker = mock(UsageTracker.class);
        TelemetryPinRunner runner = newRunner(assetService, usageTracker, false);

        runner.start();

        verify(assetService, never()).assets();
        verify(usageTracker, never()).pinTelemetry(any());
    }
}
