package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssetUsageTest {

    private static AssetUsage openUsage() {
        return new AssetUsage(UsageId.random(), AssetId.random(), Instant.now(), null, null, null, 0);
    }

    @Test
    void endedAtAndPositionsAreNullableForAnOpenUsage() {
        AssetUsage usage = openUsage();

        assertNull(usage.endedAt());
        assertNull(usage.startPosition());
        assertNull(usage.lastPosition());
    }

    @Test
    void rejectsInvalidArguments() {
        UsageId id = UsageId.random();
        AssetId assetId = AssetId.random();
        Instant startedAt = Instant.now();

        assertThrows(IllegalArgumentException.class,
                () -> new AssetUsage(null, assetId, startedAt, null, null, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new AssetUsage(id, null, startedAt, null, null, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new AssetUsage(id, assetId, null, null, null, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new AssetUsage(id, assetId, startedAt, null, null, null, -1));
    }

    @Test
    void rejectsEndedAtBeforeStartedAt() {
        Instant startedAt = Instant.now();
        Instant beforeStart = startedAt.minus(1, ChronoUnit.SECONDS);

        assertThrows(IllegalArgumentException.class,
                () -> new AssetUsage(UsageId.random(), AssetId.random(), startedAt, beforeStart, null, null, 0));
    }

    @Test
    void acceptsEndedAtEqualToStartedAt() {
        Instant startedAt = Instant.now();

        AssetUsage usage = new AssetUsage(UsageId.random(), AssetId.random(), startedAt, startedAt, null, null, 0);

        assertEquals(startedAt, usage.endedAt());
    }

    @Test
    void closedReturnsNewInstanceWithEndedAtSet() {
        AssetUsage usage = openUsage();
        Instant endedAt = usage.startedAt().plusSeconds(30);

        AssetUsage closed = usage.closed(endedAt);

        assertEquals(endedAt, closed.endedAt());
        assertNull(usage.endedAt(), "original instance must be unchanged");
    }

    @Test
    void withPositionsReturnsNewInstanceWithPositionsSet() {
        AssetUsage usage = openUsage();
        GeoPosition start = new GeoPosition(50.45, 30.52, 100.0);
        GeoPosition last = new GeoPosition(50.46, 30.53, 105.0);

        AssetUsage updated = usage.withPositions(start, last);

        assertEquals(start, updated.startPosition());
        assertEquals(last, updated.lastPosition());
        assertNull(usage.startPosition(), "original instance must be unchanged");
        assertNull(usage.lastPosition(), "original instance must be unchanged");
    }

    @Test
    void withSampleCountReturnsNewInstanceWithSampleCountSet() {
        AssetUsage usage = openUsage();

        AssetUsage updated = usage.withSampleCount(5);

        assertEquals(5, updated.sampleCount());
        assertEquals(0, usage.sampleCount(), "original instance must be unchanged");
    }
}
