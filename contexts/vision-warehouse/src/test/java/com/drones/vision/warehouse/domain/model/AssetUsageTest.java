package com.drones.vision.warehouse.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.kernel.UsageOrigin;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssetUsageTest {

    private static AssetUsage openUsage() {
        return new AssetUsage(UsageId.random(), AssetId.random(), Instant.now(), null, null, null, 0, null,
                UsagePhase.PREFLIGHT, UsageOrigin.STREAM, null);
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
                () -> new AssetUsage(null, assetId, startedAt, null, null, null, 0, null, UsagePhase.PREFLIGHT,
                        UsageOrigin.STREAM, null));
        assertThrows(IllegalArgumentException.class,
                () -> new AssetUsage(id, null, startedAt, null, null, null, 0, null, UsagePhase.PREFLIGHT,
                        UsageOrigin.STREAM, null));
        assertThrows(IllegalArgumentException.class,
                () -> new AssetUsage(id, assetId, null, null, null, null, 0, null, UsagePhase.PREFLIGHT,
                        UsageOrigin.STREAM, null));
        assertThrows(IllegalArgumentException.class,
                () -> new AssetUsage(id, assetId, startedAt, null, null, null, -1, null, UsagePhase.PREFLIGHT,
                        UsageOrigin.STREAM, null));
    }

    @Test
    void rejectsEndedAtBeforeStartedAt() {
        Instant startedAt = Instant.now();
        Instant beforeStart = startedAt.minus(1, ChronoUnit.SECONDS);

        assertThrows(IllegalArgumentException.class,
                () -> new AssetUsage(UsageId.random(), AssetId.random(), startedAt, beforeStart, null, null, 0, null,
                        UsagePhase.PREFLIGHT, UsageOrigin.STREAM, null));
    }

    @Test
    void acceptsEndedAtEqualToStartedAt() {
        Instant startedAt = Instant.now();

        AssetUsage usage = new AssetUsage(UsageId.random(), AssetId.random(), startedAt, startedAt, null, null, 0,
                null, UsagePhase.PREFLIGHT, UsageOrigin.STREAM, null);

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
                streamId, UsagePhase.PREFLIGHT, UsageOrigin.STREAM, null);

        assertEquals(streamId, usage.streamId());
    }

    @Test
    void closedWithPositionsAndWithSampleCountAllPreserveStreamId() {
        StreamId streamId = StreamId.random();
        AssetUsage usage = new AssetUsage(UsageId.random(), AssetId.random(), Instant.now(), null, null, null, 0,
                streamId, UsagePhase.PREFLIGHT, UsageOrigin.STREAM, null);

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

        AssetUsage withoutStream = new AssetUsage(id, assetId, startedAt, null, null, null, 0, null,
                UsagePhase.PREFLIGHT, UsageOrigin.STREAM, null);
        AssetUsage withStream = new AssetUsage(id, assetId, startedAt, null, null, null, 0, StreamId.random(),
                UsagePhase.PREFLIGHT, UsageOrigin.STREAM, null);

        assertNotEquals(withoutStream, withStream);
    }

    // --- Phase (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.3, Wave O7) --------------------

    @Test
    void phaseDefaultsToPreflightViaEitherConvenienceConstructor() {
        AssetUsage sevenArg = openUsage();
        AssetUsage eightArg = new AssetUsage(UsageId.random(), AssetId.random(), Instant.now(), null, null, null, 0,
                StreamId.random(), UsagePhase.PREFLIGHT, UsageOrigin.STREAM, null);

        assertEquals(UsagePhase.PREFLIGHT, sevenArg.phase());
        assertEquals(UsagePhase.PREFLIGHT, eightArg.phase());
    }

    @Test
    void rejectsNullPhase() {
        assertThrows(IllegalArgumentException.class,
                () -> new AssetUsage(UsageId.random(), AssetId.random(), Instant.now(), null, null, null, 0, null,
                        null, UsageOrigin.STREAM, null));
    }

    @Test
    void withPhaseReturnsNewInstanceWithPhaseSet() {
        AssetUsage usage = openUsage();

        AssetUsage updated = usage.withPhase(UsagePhase.IN_FLIGHT);

        assertEquals(UsagePhase.IN_FLIGHT, updated.phase());
        assertEquals(UsagePhase.PREFLIGHT, usage.phase(), "original instance must be unchanged");
    }

    @Test
    void closedWithPositionsAndWithSampleCountAllPreservePhase() {
        AssetUsage usage = openUsage().withPhase(UsagePhase.LINK_LOST);

        AssetUsage closed = usage.closed(usage.startedAt().plusSeconds(30));
        AssetUsage repositioned =
                usage.withPositions(new GeoPosition(1.0, 2.0, null), new GeoPosition(3.0, 4.0, null));
        AssetUsage resampled = usage.withSampleCount(7);

        assertEquals(UsagePhase.LINK_LOST, closed.phase());
        assertEquals(UsagePhase.LINK_LOST, repositioned.phase());
        assertEquals(UsagePhase.LINK_LOST, resampled.phase());
    }

    // --- Origin (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D2, wave R2) --------------

    @Test
    void originDefaultsToStreamViaEitherLegacyConstructor() {
        AssetUsage sevenArg = openUsage();
        AssetUsage eightArg = new AssetUsage(UsageId.random(), AssetId.random(), Instant.now(), null, null, null, 0,
                StreamId.random(), UsagePhase.PREFLIGHT, UsageOrigin.STREAM, null);
        AssetUsage nineArg = new AssetUsage(UsageId.random(), AssetId.random(), Instant.now(), null, null, null, 0,
                StreamId.random(), UsagePhase.IN_FLIGHT, UsageOrigin.STREAM, null);

        assertEquals(UsageOrigin.STREAM, sevenArg.origin());
        assertEquals(UsageOrigin.STREAM, eightArg.origin());
        assertEquals(UsageOrigin.STREAM, nineArg.origin());
    }

    @Test
    void rejectsNullOrigin() {
        assertThrows(IllegalArgumentException.class,
                () -> new AssetUsage(UsageId.random(), AssetId.random(), Instant.now(), null, null, null, 0, null,
                        UsagePhase.PREFLIGHT, null, null));
    }

    @Test
    void withOriginReturnsNewInstanceWithOriginSet() {
        AssetUsage usage = openUsage();

        AssetUsage updated = usage.withOrigin(UsageOrigin.OPERATOR);

        assertEquals(UsageOrigin.OPERATOR, updated.origin());
        assertEquals(UsageOrigin.STREAM, usage.origin(), "original instance must be unchanged");
    }

    @Test
    void closedWithPositionsAndWithSampleCountAllPreserveOrigin() {
        AssetUsage usage = openUsage().withOrigin(UsageOrigin.OPERATOR);

        AssetUsage closed = usage.closed(usage.startedAt().plusSeconds(30));
        AssetUsage repositioned =
                usage.withPositions(new GeoPosition(1.0, 2.0, null), new GeoPosition(3.0, 4.0, null));
        AssetUsage resampled = usage.withSampleCount(7);

        assertEquals(UsageOrigin.OPERATOR, closed.origin());
        assertEquals(UsageOrigin.OPERATOR, repositioned.origin());
        assertEquals(UsageOrigin.OPERATOR, resampled.origin());
    }

    // --- Pilot (docs/plans/active/ASSET-FLOWS-PLAN.md §2, D1p) ---------------------------------

    @Test
    void pilotIdIsNullByDefaultViaOpenUsage() {
        AssetUsage usage = openUsage();

        assertNull(usage.pilotId(), "no acting user is known when a usage is opened by this helper");
    }

    @Test
    void pilotIdIsCarriedByTheElevenArgCanonicalConstructor() {
        UserId pilotId = UserId.random();

        AssetUsage usage = new AssetUsage(UsageId.random(), AssetId.random(), Instant.now(), null, null, null, 0,
                null, UsagePhase.PREFLIGHT, UsageOrigin.STREAM, pilotId);

        assertEquals(pilotId, usage.pilotId());
    }

    @Test
    void withPilotReturnsNewInstanceWithPilotSet() {
        AssetUsage usage = openUsage();
        UserId pilotId = UserId.random();

        AssetUsage updated = usage.withPilot(pilotId);

        assertEquals(pilotId, updated.pilotId());
        assertNull(usage.pilotId(), "original instance must be unchanged");
    }

    @Test
    void closedWithPositionsAndWithSampleCountAllPreservePilot() {
        UserId pilotId = UserId.random();
        AssetUsage usage = openUsage().withPilot(pilotId);

        AssetUsage closed = usage.closed(usage.startedAt().plusSeconds(30));
        AssetUsage repositioned =
                usage.withPositions(new GeoPosition(1.0, 2.0, null), new GeoPosition(3.0, 4.0, null));
        AssetUsage resampled = usage.withSampleCount(7);

        assertEquals(pilotId, closed.pilotId());
        assertEquals(pilotId, repositioned.pilotId());
        assertEquals(pilotId, resampled.pilotId());
    }

    @Test
    void equalityDistinguishesUsagesThatDifferOnlyByPilotId() {
        UsageId id = UsageId.random();
        AssetId assetId = AssetId.random();
        Instant startedAt = Instant.now();

        AssetUsage withoutPilot = new AssetUsage(id, assetId, startedAt, null, null, null, 0, null,
                UsagePhase.PREFLIGHT, UsageOrigin.STREAM, null);
        AssetUsage withPilot = new AssetUsage(id, assetId, startedAt, null, null, null, 0, null,
                UsagePhase.PREFLIGHT, UsageOrigin.STREAM, UserId.random());

        assertNotEquals(withoutPilot, withPilot);
    }
}
