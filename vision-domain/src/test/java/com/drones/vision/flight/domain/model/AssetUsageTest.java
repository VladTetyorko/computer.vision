package com.drones.vision.flight.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.UsageId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    @Test
    void streamIdIsNullByDefaultViaTheSevenArgConvenienceConstructor() {
        AssetUsage usage = openUsage();

        assertNull(usage.streamId(), "a usage built via the legacy 7-arg constructor has no stream link");
    }

    @Test
    void streamIdIsCarriedByTheEightArgCanonicalConstructor() {
        StreamId streamId = StreamId.random();

        AssetUsage usage = new AssetUsage(UsageId.random(), AssetId.random(), Instant.now(), null, null, null, 0,
                streamId);

        assertEquals(streamId, usage.streamId());
    }

    @Test
    void closedWithPositionsAndWithSampleCountAllPreserveStreamId() {
        StreamId streamId = StreamId.random();
        AssetUsage usage = new AssetUsage(UsageId.random(), AssetId.random(), Instant.now(), null, null, null, 0,
                streamId);

        AssetUsage closed = usage.closed(usage.startedAt().plusSeconds(30));
        AssetUsage repositioned = usage.withPositions(new GeoPosition(1.0, 2.0, null), new GeoPosition(3.0, 4.0, null));
        AssetUsage resampled = usage.withSampleCount(7);

        assertEquals(streamId, closed.streamId());
        assertEquals(streamId, repositioned.streamId());
        assertEquals(streamId, resampled.streamId());
    }

    @Test
    void equalityDistinguishesUsagesThatDifferOnlyByStreamId() {
        UsageId id = UsageId.random();
        AssetId assetId = AssetId.random();
        Instant startedAt = Instant.now();

        AssetUsage withoutStream = new AssetUsage(id, assetId, startedAt, null, null, null, 0);
        AssetUsage withStream = new AssetUsage(id, assetId, startedAt, null, null, null, 0, StreamId.random());

        assertNotEquals(withoutStream, withStream);
    }
}
