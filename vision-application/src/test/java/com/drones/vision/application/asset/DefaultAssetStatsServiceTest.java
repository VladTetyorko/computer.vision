package com.drones.vision.application.asset;

import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.AssetUsage;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.Telemetry;
import com.drones.vision.domain.model.UsageId;
import com.drones.vision.domain.port.out.AssetUsageRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.drones.vision.application.pipeline.UsageTracker;

class DefaultAssetStatsServiceTest {

    private static final DeviceId DEVICE_ID = DeviceId.random();

    private AssetUsageRepositoryPort usageRepository;
    private UsageTracker usageTracker;
    private AssetId assetId;

    @BeforeEach
    void setUp() {
        usageRepository = mock(AssetUsageRepositoryPort.class);
        usageTracker = mock(UsageTracker.class);
        assetId = AssetId.random();
        when(usageTracker.latestTelemetry(assetId)).thenReturn(Optional.empty());
    }

    private DefaultAssetStatsService service() {
        return new DefaultAssetStatsService(usageRepository, usageTracker);
    }

    private DefaultAssetStatsService serviceWithClock(Instant now) {
        return new DefaultAssetStatsService(usageRepository, usageTracker, () -> now);
    }

    private static AssetUsage closedUsage(AssetId assetId, Instant startedAt, Instant endedAt) {
        return new AssetUsage(UsageId.random(), assetId, startedAt, endedAt, null, null, 0);
    }

    private static AssetUsage openUsage(AssetId assetId, Instant startedAt) {
        return new AssetUsage(UsageId.random(), assetId, startedAt, null, null, null, 0);
    }

    @Test
    void statsForAggregatesMultipleClosedFlights() {
        Instant start1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant end1 = start1.plusSeconds(60);
        Instant start2 = Instant.parse("2026-01-02T00:00:00Z");
        Instant end2 = start2.plusSeconds(120);
        when(usageRepository.findRecentByAsset(assetId, DefaultAssetStatsService.STATS_FETCH_LIMIT))
                .thenReturn(List.of(closedUsage(assetId, start1, end1), closedUsage(assetId, start2, end2)));

        AssetStats stats = service().statsFor(assetId);

        assertEquals(180, stats.totalFlightSeconds());
        assertEquals(2, stats.flightCount());
        assertEquals(90L, stats.avgFlightSeconds());
        assertEquals(start1, stats.firstFlownAt());
        assertEquals(end2, stats.lastFlownAt());
        assertFalse(stats.flightInProgress());
    }

    @Test
    void statsForCountsAnOpenFlightToNow() {
        Instant startedAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant now = startedAt.plusSeconds(45);
        when(usageRepository.findRecentByAsset(assetId, DefaultAssetStatsService.STATS_FETCH_LIMIT))
                .thenReturn(List.of(openUsage(assetId, startedAt)));

        AssetStats stats = serviceWithClock(now).statsFor(assetId);

        assertEquals(45, stats.totalFlightSeconds());
        assertEquals(1, stats.flightCount());
        assertTrue(stats.flightInProgress());
        assertEquals(startedAt, stats.firstFlownAt());
        assertEquals(startedAt, stats.lastFlownAt(), "an open usage's activity instant is its own startedAt, not now");
    }

    @Test
    void statsForAnAssetWithNoUsagesReturnsZerosAndNulls() {
        when(usageRepository.findRecentByAsset(assetId, DefaultAssetStatsService.STATS_FETCH_LIMIT))
                .thenReturn(List.of());

        AssetStats stats = service().statsFor(assetId);

        assertEquals(0, stats.totalFlightSeconds());
        assertEquals(0, stats.flightCount());
        assertNull(stats.firstFlownAt());
        assertNull(stats.lastFlownAt());
        assertNull(stats.avgFlightSeconds());
        assertNull(stats.lastKnownBatteryPercent());
        assertFalse(stats.flightInProgress());
    }

    @Test
    void statsForWithOnlyAnOpenFlightLeavesAvgFlightSecondsNull() {
        Instant startedAt = Instant.parse("2026-01-01T00:00:00Z");
        when(usageRepository.findRecentByAsset(assetId, DefaultAssetStatsService.STATS_FETCH_LIMIT))
                .thenReturn(List.of(openUsage(assetId, startedAt)));

        AssetStats stats = serviceWithClock(startedAt.plusSeconds(30)).statsFor(assetId);

        assertNull(stats.avgFlightSeconds(), "an open flight has no final duration to average in");
        assertTrue(stats.flightInProgress());
    }

    @Test
    void statsForPassesThroughTheLatestTelemetryBatteryReadingRoundedToTheNearestPercent() {
        when(usageRepository.findRecentByAsset(assetId, DefaultAssetStatsService.STATS_FETCH_LIMIT))
                .thenReturn(List.of());
        Telemetry sample = new Telemetry(DEVICE_ID, Instant.now(), null, null, null, null, 67.6, Map.of());
        when(usageTracker.latestTelemetry(assetId)).thenReturn(Optional.of(sample));

        AssetStats stats = service().statsFor(assetId);

        assertEquals(68, stats.lastKnownBatteryPercent());
    }

    @Test
    void statsForLeavesBatteryNullWhenTheAssetHasNeverReportedTelemetry() {
        when(usageRepository.findRecentByAsset(assetId, DefaultAssetStatsService.STATS_FETCH_LIMIT))
                .thenReturn(List.of());
        when(usageTracker.latestTelemetry(assetId)).thenReturn(Optional.empty());

        AssetStats stats = service().statsFor(assetId);

        assertNull(stats.lastKnownBatteryPercent());
    }

    @Test
    void statsForFloorsANegativeDurationCausedByClockSkewToZero() {
        Instant startedAt = Instant.parse("2026-01-01T00:00:10Z");
        Instant now = startedAt.minusSeconds(5); // "now" appears to precede the open usage's own startedAt
        when(usageRepository.findRecentByAsset(assetId, DefaultAssetStatsService.STATS_FETCH_LIMIT))
                .thenReturn(List.of(openUsage(assetId, startedAt)));

        AssetStats stats = serviceWithClock(now).statsFor(assetId);

        assertEquals(0, stats.totalFlightSeconds(), "a negative duration from clock skew must be floored at 0");
    }
}
