package com.drones.vision.warehouse.application.usage;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.kernel.UsageOrigin;
import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.warehouse.domain.model.UsagePhase;
import com.drones.vision.warehouse.domain.port.AssetLiveStatePort;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * docs/plans/active/OPERATOR-UX-5-PLAN.md finding U1, wave W1: unit tests for {@link
 * DefaultUsageIdleCloseService}, mirroring {@code DefaultAssetStatsServiceTest}'s Mockito-mock style
 * (this package's/module's dominant test convention for a service composing domain ports).
 */
class DefaultUsageIdleCloseServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");
    private static final Duration THRESHOLD = Duration.ofMinutes(10);

    private AssetUsageRepositoryPort usageRepository;
    private AssetLiveStatePort assetLiveStatePort;
    private UsageSessionService usageSessionService;

    @BeforeEach
    void setUp() {
        usageRepository = mock(AssetUsageRepositoryPort.class);
        assetLiveStatePort = mock(AssetLiveStatePort.class);
        usageSessionService = mock(UsageSessionService.class);
    }

    private DefaultUsageIdleCloseService service() {
        return new DefaultUsageIdleCloseService(usageRepository, assetLiveStatePort, usageSessionService,
                new IdleUsageCloseSettings(THRESHOLD), () -> NOW);
    }

    private static AssetUsage openUsage(AssetId assetId, Instant startedAt, UsagePhase phase) {
        return new AssetUsage(UsageId.random(), assetId, startedAt, null, null, null, 3, null, phase,
                UsageOrigin.STREAM, null);
    }

    private static AssetUsage closedUsage(AssetId assetId, Instant startedAt, Instant endedAt) {
        return new AssetUsage(UsageId.random(), assetId, startedAt, endedAt, null, null, 3, null,
                UsagePhase.CLOSED, UsageOrigin.STREAM, null);
    }

    private static Telemetry sampleAt(Instant at) {
        return new Telemetry(DeviceId.random(), at, null, null, null, null, null, Map.of());
    }

    private void stubFindRecent(AssetUsage... usages) {
        when(usageRepository.findRecent(DefaultUsageIdleCloseService.SWEEP_FETCH_LIMIT))
                .thenReturn(List.of(usages));
    }

    // -- the three-usage scenario the wave asks for -------------------------------------------

    @Test
    void closesAnIdleUsageAtItsLastTelemetrySampleInstant() {
        AssetId assetId = AssetId.random();
        Instant startedAt = NOW.minus(Duration.ofHours(2));
        Instant lastSampleAt = NOW.minus(Duration.ofMinutes(30)); // idle: older than the 10m threshold
        AssetUsage usage = openUsage(assetId, startedAt, UsagePhase.PREFLIGHT);
        stubFindRecent(usage);
        when(assetLiveStatePort.latestTelemetry(assetId)).thenReturn(Optional.of(sampleAt(lastSampleAt)));

        int closed = service().closeIdleUsages();

        assertEquals(1, closed);
        verify(usageSessionService).close(usage, UsagePhase.CLOSED, lastSampleAt);
    }

    @Test
    void leavesAnActiveUsageOpen() {
        AssetId assetId = AssetId.random();
        Instant startedAt = NOW.minus(Duration.ofHours(2));
        Instant lastSampleAt = NOW.minus(Duration.ofMinutes(1)); // active: well within the 10m threshold
        AssetUsage usage = openUsage(assetId, startedAt, UsagePhase.IN_FLIGHT);
        stubFindRecent(usage);
        when(assetLiveStatePort.latestTelemetry(assetId)).thenReturn(Optional.of(sampleAt(lastSampleAt)));

        int closed = service().closeIdleUsages();

        assertEquals(0, closed);
        verify(usageSessionService, never()).close(any(), any(), any());
    }

    @Test
    void leavesAnAlreadyEndedUsageUntouched() {
        AssetId assetId = AssetId.random();
        Instant startedAt = NOW.minus(Duration.ofDays(3));
        AssetUsage usage = closedUsage(assetId, startedAt, startedAt.plusSeconds(60));
        stubFindRecent(usage);

        int closed = service().closeIdleUsages();

        assertEquals(0, closed);
        verify(usageSessionService, never()).close(any(), any(), any());
        verify(assetLiveStatePort, never()).latestTelemetry(any());
    }

    // -- last-activity resolution --------------------------------------------------------------

    @Test
    void fallsBackToStartedAtWhenNoTelemetrySampleIsKnown() {
        // e.g. a station crash: perception's in-memory tracker holding the last sample is gone.
        AssetId assetId = AssetId.random();
        Instant startedAt = NOW.minus(Duration.ofHours(5));
        AssetUsage usage = openUsage(assetId, startedAt, UsagePhase.PREFLIGHT);
        stubFindRecent(usage);
        when(assetLiveStatePort.latestTelemetry(assetId)).thenReturn(Optional.empty());

        int closed = service().closeIdleUsages();

        assertEquals(1, closed);
        verify(usageSessionService).close(usage, UsagePhase.CLOSED, startedAt);
    }

    @Test
    void clampsALastTelemetrySampleFromBeforeThisUsageStartedToStartedAt() {
        // AssetLiveStatePort#latestTelemetry answers for the whole asset, not this usage; a stale
        // sample left over from an earlier, already-closed usage must never predate this one.
        AssetId assetId = AssetId.random();
        Instant startedAt = NOW.minus(Duration.ofHours(1));
        Instant staleSampleFromEarlierUsage = NOW.minus(Duration.ofDays(1));
        AssetUsage usage = openUsage(assetId, startedAt, UsagePhase.PREFLIGHT);
        stubFindRecent(usage);
        when(assetLiveStatePort.latestTelemetry(assetId)).thenReturn(Optional.of(sampleAt(staleSampleFromEarlierUsage)));

        int closed = service().closeIdleUsages();

        assertEquals(1, closed);
        verify(usageSessionService).close(usage, UsagePhase.CLOSED, startedAt);
    }

    // -- closed-phase mapping (mirrors flight's FlightPhaseRule#onSessionClosed by name only) ---

    @Test
    void closesInFlightAndLinkLostToAbandoned() {
        AssetId inFlightAsset = AssetId.random();
        AssetId linkLostAsset = AssetId.random();
        Instant startedAt = NOW.minus(Duration.ofHours(1));
        AssetUsage inFlight = openUsage(inFlightAsset, startedAt, UsagePhase.IN_FLIGHT);
        AssetUsage linkLost = openUsage(linkLostAsset, startedAt, UsagePhase.LINK_LOST);
        stubFindRecent(inFlight, linkLost);
        when(assetLiveStatePort.latestTelemetry(any())).thenReturn(Optional.empty());

        service().closeIdleUsages();

        verify(usageSessionService).close(inFlight, UsagePhase.ABANDONED, startedAt);
        verify(usageSessionService).close(linkLost, UsagePhase.ABANDONED, startedAt);
    }

    @Test
    void closesEveryOtherPhaseToClosed() {
        AssetId preflightAsset = AssetId.random();
        AssetId postflightAsset = AssetId.random();
        Instant startedAt = NOW.minus(Duration.ofHours(1));
        AssetUsage preflight = openUsage(preflightAsset, startedAt, UsagePhase.PREFLIGHT);
        AssetUsage postflight = openUsage(postflightAsset, startedAt, UsagePhase.POSTFLIGHT);
        stubFindRecent(preflight, postflight);
        when(assetLiveStatePort.latestTelemetry(any())).thenReturn(Optional.empty());

        service().closeIdleUsages();

        verify(usageSessionService).close(preflight, UsagePhase.CLOSED, startedAt);
        verify(usageSessionService).close(postflight, UsagePhase.CLOSED, startedAt);
    }

    // -- multiple usages / return value ----------------------------------------------------------

    @Test
    void returnsTheCountOfUsagesActuallyClosedAndSkipsTheRest() {
        AssetId idleAssetOne = AssetId.random();
        AssetId idleAssetTwo = AssetId.random();
        AssetId activeAsset = AssetId.random();
        Instant startedAt = NOW.minus(Duration.ofHours(4));
        AssetUsage idleOne = openUsage(idleAssetOne, startedAt, UsagePhase.PREFLIGHT);
        AssetUsage idleTwo = openUsage(idleAssetTwo, startedAt, UsagePhase.POSTFLIGHT);
        AssetUsage active = openUsage(activeAsset, startedAt, UsagePhase.IN_FLIGHT);
        AssetUsage alreadyClosed = closedUsage(AssetId.random(), startedAt, startedAt.plusSeconds(30));
        stubFindRecent(idleOne, idleTwo, active, alreadyClosed);
        when(assetLiveStatePort.latestTelemetry(idleAssetOne)).thenReturn(Optional.empty());
        when(assetLiveStatePort.latestTelemetry(idleAssetTwo)).thenReturn(Optional.empty());
        when(assetLiveStatePort.latestTelemetry(activeAsset))
                .thenReturn(Optional.of(sampleAt(NOW.minus(Duration.ofSeconds(1)))));

        int closed = service().closeIdleUsages();

        assertEquals(2, closed);
        verify(usageSessionService, times(2)).close(any(), any(), any());
    }

    // -- constructor -------------------------------------------------------------------------

    @Test
    void constructorRejectsNullArguments() {
        IdleUsageCloseSettings settings = IdleUsageCloseSettings.defaults();
        assertThrows(NullPointerException.class,
                () -> new DefaultUsageIdleCloseService(null, assetLiveStatePort, usageSessionService, settings));
        assertThrows(NullPointerException.class,
                () -> new DefaultUsageIdleCloseService(usageRepository, null, usageSessionService, settings));
        assertThrows(NullPointerException.class,
                () -> new DefaultUsageIdleCloseService(usageRepository, assetLiveStatePort, null, settings));
        assertThrows(NullPointerException.class,
                () -> new DefaultUsageIdleCloseService(usageRepository, assetLiveStatePort, usageSessionService, null));
    }
}
