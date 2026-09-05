package com.drones.vision.app.discovery;

import com.drones.vision.adapter.mavlink.MavlinkTelemetrySource;
import com.drones.vision.app.config.properties.VisionDiscoveryProperties;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.warehouse.application.discovery.DiscoveryInboxService;
import com.drones.vision.warehouse.application.discovery.DiscoveryScanResult;
import com.drones.vision.warehouse.application.discovery.DiscoveryService;
import com.drones.vision.warehouse.application.discovery.ReportOutcome;
import com.drones.vision.warehouse.domain.model.DiscoveredDevice;
import com.drones.vision.warehouse.domain.model.DiscoveryCandidate;
import com.drones.vision.warehouse.domain.model.DiscoveryCandidateId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DiscoveryInboxRunner} (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md
 * &sect;11, Z2c) — real background scheduler throughout, driven at the fastest legal cadence (one
 * second — {@code sweepSeconds}/{@code scanTimeoutSeconds} are whole seconds, per the frozen
 * contract's own property names) and observed by polling, the same style {@code
 * UsageIdleCloseRunnerTest} already uses rather than reaching for a fake clock.
 */
class DiscoveryInboxRunnerTest {

    private static final int FAST_SWEEP_SECONDS = 1;

    private final List<DiscoveryInboxRunner> runners = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (DiscoveryInboxRunner runner : runners) {
            runner.close();
        }
    }

    private DiscoveryInboxRunner newRunner(DiscoveryInboxService discoveryInboxService,
                                            DiscoveryService discoveryService,
                                            MavlinkTelemetrySource mavlinkTelemetrySource, boolean lobbyEnabled) {
        DiscoveryInboxRunner runner = new DiscoveryInboxRunner(discoveryInboxService, discoveryService,
                mavlinkTelemetrySource, properties(lobbyEnabled));
        runners.add(runner);
        return runner;
    }

    private static VisionDiscoveryProperties properties(boolean lobbyEnabled) {
        return new VisionDiscoveryProperties(14_550, null, null, new VisionDiscoveryProperties.Lobby(lobbyEnabled),
                new VisionDiscoveryProperties.Inbox(true, FAST_SWEEP_SECONDS, FAST_SWEEP_SECONDS), null, null);
    }

    private static DiscoveredDevice discoveredDevice() {
        return new DiscoveredDevice("mavlink", "New quad", URI.create("udp://10.0.0.5:14550"),
                new CategoryId("quadcopter"),
                new StreamDescriptor("mavlink", URI.create("udp://10.0.0.5:14550"), Map.of("sysid", "7")),
                Map.of("sysid", "7"));
    }

    private static void awaitTrue(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        fail(message);
    }

    @Test
    void sweepsOnceImmediatelyOnStart() throws Exception {
        DiscoveryInboxService discoveryInboxService = mock(DiscoveryInboxService.class);
        DiscoveryService discoveryService = mock(DiscoveryService.class);
        MavlinkTelemetrySource mavlinkTelemetrySource = mock(MavlinkTelemetrySource.class);
        when(discoveryService.scan(any())).thenReturn(new DiscoveryScanResult(List.of(), Set.of()));

        DiscoveryInboxRunner runner = newRunner(discoveryInboxService, discoveryService, mavlinkTelemetrySource, true);
        runner.start();

        awaitTrue(() -> {
                    try {
                        verify(discoveryService, atLeast(1)).scan(any());
                        return true;
                    } catch (AssertionError e) {
                        return false;
                    }
                },
                "the first sweep must fire immediately on start, not after one sweep-period");
    }

    @Test
    void holdsTheLobbyBeforeScanningWhenLobbyEnabled() throws Exception {
        DiscoveryInboxService discoveryInboxService = mock(DiscoveryInboxService.class);
        DiscoveryService discoveryService = mock(DiscoveryService.class);
        MavlinkTelemetrySource mavlinkTelemetrySource = mock(MavlinkTelemetrySource.class);
        when(discoveryService.scan(any())).thenReturn(new DiscoveryScanResult(List.of(), Set.of()));

        DiscoveryInboxRunner runner = newRunner(discoveryInboxService, discoveryService, mavlinkTelemetrySource, true);
        runner.start();

        awaitTrue(() -> {
                    try {
                        verify(mavlinkTelemetrySource, atLeast(1)).holdLobby(14_550);
                        return true;
                    } catch (AssertionError e) {
                        return false;
                    }
                },
                "holdLobby must be called with the configured mavlinkPort while lobby.enabled");
    }

    @Test
    void neverHoldsTheLobbyWhenLobbyDisabled() throws Exception {
        DiscoveryInboxService discoveryInboxService = mock(DiscoveryInboxService.class);
        DiscoveryService discoveryService = mock(DiscoveryService.class);
        MavlinkTelemetrySource mavlinkTelemetrySource = mock(MavlinkTelemetrySource.class);
        when(discoveryService.scan(any())).thenReturn(new DiscoveryScanResult(List.of(), Set.of()));

        DiscoveryInboxRunner runner =
                newRunner(discoveryInboxService, discoveryService, mavlinkTelemetrySource, false);
        runner.start();

        awaitTrue(() -> {
                    try {
                        verify(discoveryService, atLeast(1)).scan(any());
                        return true;
                    } catch (AssertionError e) {
                        return false;
                    }
                },
                "the sweep must still scan even with lobby.enabled=false");
        verify(mavlinkTelemetrySource, never()).holdLobby(anyInt());
    }

    @Test
    void reportsEveryDiscoveredDeviceFromTheScan() throws Exception {
        DiscoveryInboxService discoveryInboxService = mock(DiscoveryInboxService.class);
        DiscoveryService discoveryService = mock(DiscoveryService.class);
        MavlinkTelemetrySource mavlinkTelemetrySource = mock(MavlinkTelemetrySource.class);
        DiscoveredDevice discovered = discoveredDevice();
        when(discoveryService.scan(any())).thenReturn(new DiscoveryScanResult(List.of(discovered), Set.of()));
        when(discoveryInboxService.report(discovered)).thenReturn(
                new ReportOutcome(
                        DiscoveryCandidate.newlyReported(DiscoveryCandidateId.random(), discovered, Instant.now()),
                        true));

        DiscoveryInboxRunner runner = newRunner(discoveryInboxService, discoveryService, mavlinkTelemetrySource, true);
        runner.start();

        awaitTrue(() -> {
                    try {
                        verify(discoveryInboxService, atLeast(1)).report(discovered);
                        return true;
                    } catch (AssertionError e) {
                        return false;
                    }
                },
                "every DiscoveredDevice the scan finds must be reported to the inbox service");
    }

    @Test
    void aSweepFailureDoesNotStopSubsequentSweeps() throws Exception {
        DiscoveryInboxService discoveryInboxService = mock(DiscoveryInboxService.class);
        DiscoveryService discoveryService = mock(DiscoveryService.class);
        MavlinkTelemetrySource mavlinkTelemetrySource = mock(MavlinkTelemetrySource.class);
        AtomicInteger callCount = new AtomicInteger();
        when(discoveryService.scan(any())).thenAnswer(invocation -> {
            int call = callCount.incrementAndGet();
            if (call == 1) {
                throw new RuntimeException("boom");
            }
            return new DiscoveryScanResult(List.of(), Set.of());
        });

        DiscoveryInboxRunner runner = newRunner(discoveryInboxService, discoveryService, mavlinkTelemetrySource, true);
        runner.start();

        awaitTrue(() -> callCount.get() >= 2,
                "a RuntimeException from one sweep must not prevent the next scheduled sweep from running");
    }

    @Test
    void startIsIdempotent() {
        DiscoveryInboxService discoveryInboxService = mock(DiscoveryInboxService.class);
        DiscoveryService discoveryService = mock(DiscoveryService.class);
        MavlinkTelemetrySource mavlinkTelemetrySource = mock(MavlinkTelemetrySource.class);
        when(discoveryService.scan(any())).thenReturn(new DiscoveryScanResult(List.of(), Set.of()));
        DiscoveryInboxRunner runner = newRunner(discoveryInboxService, discoveryService, mavlinkTelemetrySource, true);

        runner.start();
        runner.start(); // must not double-arm the scheduler or throw
    }

    @Test
    void closeIsIdempotent() {
        DiscoveryInboxService discoveryInboxService = mock(DiscoveryInboxService.class);
        DiscoveryService discoveryService = mock(DiscoveryService.class);
        MavlinkTelemetrySource mavlinkTelemetrySource = mock(MavlinkTelemetrySource.class);
        when(discoveryService.scan(any())).thenReturn(new DiscoveryScanResult(List.of(), Set.of()));
        DiscoveryInboxRunner runner = newRunner(discoveryInboxService, discoveryService, mavlinkTelemetrySource, true);

        runner.start();
        runner.close();
        runner.close(); // idempotent -- must not throw
    }
}
