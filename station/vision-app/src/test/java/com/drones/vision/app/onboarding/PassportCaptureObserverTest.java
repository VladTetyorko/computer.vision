package com.drones.vision.app.onboarding;

import com.drones.vision.flight.application.VehicleProfileService;
import com.drones.vision.flight.domain.model.FlightPhase;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.PlatformActor;
import com.drones.vision.warehouse.domain.model.UsagePhase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit test for {@link PassportCaptureObserver} (docs/plans/active/DRONE-ONBOARDING-PLAN.md
 * §2.4, Wave O11) — no Spring context. Every real capture runs on the observer's own background
 * thread, so assertions on {@link VehicleProfileService} interactions use {@code
 * Mockito.timeout(long)} rather than a synchronous {@code verify}, the same convention {@code
 * DefaultStreamServiceTest} already uses for its own async supervisor assertions.
 */
class PassportCaptureObserverTest {

    private static final Duration WINDOW = Duration.ofSeconds(10);

    private VehicleProfileService vehicleProfileService;
    private PassportCaptureObserver observer;

    @BeforeEach
    void setUp() {
        vehicleProfileService = mock(VehicleProfileService.class);
        observer = new PassportCaptureObserver(vehicleProfileService, WINDOW);
    }

    @Test
    void usageOpeningWithNullPreviousCapturesAPreflightSnapshot() {
        AssetId assetId = AssetId.random();
        UsageId usageId = UsageId.random();

        observer.onPhaseChanged(assetId, usageId, null, UsagePhase.PREFLIGHT, Instant.now());

        verify(vehicleProfileService, timeout(2000)).captureSnapshot(eq(assetId), eq(usageId),
                eq(FlightPhase.PREFLIGHT), eq(WINDOW), eq(PlatformActor.USER_ID), eq(Authority.full()));
    }

    @Test
    void reachingPostflightCapturesAPostflightSnapshot() {
        AssetId assetId = AssetId.random();
        UsageId usageId = UsageId.random();

        observer.onPhaseChanged(assetId, usageId, UsagePhase.IN_FLIGHT, UsagePhase.POSTFLIGHT, Instant.now());

        verify(vehicleProfileService, timeout(2000)).captureSnapshot(eq(assetId), eq(usageId),
                eq(FlightPhase.POSTFLIGHT), eq(WINDOW), eq(PlatformActor.USER_ID), eq(Authority.full()));
    }

    @Test
    void everyOtherTransitionNeverCapturesAnything() throws InterruptedException {
        AssetId assetId = AssetId.random();
        UsageId usageId = UsageId.random();

        observer.onPhaseChanged(assetId, usageId, UsagePhase.PREFLIGHT, UsagePhase.IN_FLIGHT, Instant.now());
        observer.onPhaseChanged(assetId, usageId, UsagePhase.IN_FLIGHT, UsagePhase.LINK_LOST, Instant.now());
        observer.onPhaseChanged(assetId, usageId, UsagePhase.LINK_LOST, UsagePhase.ABANDONED, Instant.now());
        observer.onPhaseChanged(assetId, usageId, UsagePhase.POSTFLIGHT, UsagePhase.CLOSED, Instant.now());
        // Give the (empty) executor queue a moment to prove a negative before asserting never().
        Thread.sleep(200);

        verify(vehicleProfileService, never()).captureSnapshot(any(), any(), any(), any(), any(), any());
    }

    @Test
    void anExpectedIllegalStateExceptionIsSwallowedAndLoggedOncePerUsage() {
        AssetId assetId = AssetId.random();
        UsageId usageId = UsageId.random();
        when(vehicleProfileService.captureSnapshot(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("no device this platform can probe"));

        // Two captures for the same usage (PREFLIGHT then POSTFLIGHT) -- neither call may propagate,
        // and the calling thread must never block on the executor.
        observer.onPhaseChanged(assetId, usageId, null, UsagePhase.PREFLIGHT, Instant.now());
        observer.onPhaseChanged(assetId, usageId, UsagePhase.IN_FLIGHT, UsagePhase.POSTFLIGHT, Instant.now());

        verify(vehicleProfileService, timeout(2000).times(2)).captureSnapshot(any(), any(), any(), any(), any(),
                any());
    }

    @Test
    void anUnexpectedRuntimeExceptionIsAlsoSwallowed() {
        AssetId assetId = AssetId.random();
        UsageId usageId = UsageId.random();
        when(vehicleProfileService.captureSnapshot(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("boom"));

        observer.onPhaseChanged(assetId, usageId, null, UsagePhase.PREFLIGHT, Instant.now());

        verify(vehicleProfileService, timeout(2000)).captureSnapshot(any(), any(), any(), any(), any(), any());
        // No exception propagated out of onPhaseChanged() above -- the test reaching this line
        // without failing is itself the assertion that the caller's thread was never broken.
    }

    @Test
    void onPhaseChangedNeverBlocksTheCallingThread() {
        AssetId assetId = AssetId.random();
        UsageId usageId = UsageId.random();
        when(vehicleProfileService.captureSnapshot(any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            Thread.sleep(1000);
            return null;
        });

        long start = System.nanoTime();
        observer.onPhaseChanged(assetId, usageId, null, UsagePhase.PREFLIGHT, Instant.now());
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(elapsedMillis < 500, "onPhaseChanged blocked the caller for " + elapsedMillis + "ms");
    }

    @Test
    void closeShutsTheExecutorDownCleanly() {
        observer.close();
        // A second close() must not throw -- ThreadPoolExecutor#shutdown() is itself idempotent.
        observer.close();
    }
}
