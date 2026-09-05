package com.drones.vision.flight.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SeatTest {

    private final AssetId assetId = AssetId.random();
    private final UserId holder = UserId.random();
    private final Instant acquiredAt = Instant.parse("2026-09-04T10:12:03Z");
    private final Instant expiresAt = Instant.parse("2026-09-04T10:12:18Z");

    @Test
    void constructsWithValidFields() {
        Seat seat = new Seat(assetId, SeatKind.FLIGHT, holder, acquiredAt, expiresAt);
        assertEquals(assetId, seat.assetId());
        assertEquals(SeatKind.FLIGHT, seat.kind());
        assertEquals(holder, seat.holder());
        assertEquals(acquiredAt, seat.acquiredAt());
        assertEquals(expiresAt, seat.expiresAt());
    }

    @Test
    void allowsExpiresAtEqualToAcquiredAt() {
        Seat seat = new Seat(assetId, SeatKind.CAMERA, holder, acquiredAt, acquiredAt);
        assertEquals(acquiredAt, seat.expiresAt());
    }

    @Test
    void rejectsNullAssetId() {
        assertThrows(IllegalArgumentException.class,
                () -> new Seat(null, SeatKind.FLIGHT, holder, acquiredAt, expiresAt));
    }

    @Test
    void rejectsNullKind() {
        assertThrows(IllegalArgumentException.class,
                () -> new Seat(assetId, null, holder, acquiredAt, expiresAt));
    }

    @Test
    void rejectsNullHolder() {
        assertThrows(IllegalArgumentException.class,
                () -> new Seat(assetId, SeatKind.FLIGHT, null, acquiredAt, expiresAt));
    }

    @Test
    void rejectsNullAcquiredAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new Seat(assetId, SeatKind.FLIGHT, holder, null, expiresAt));
    }

    @Test
    void rejectsNullExpiresAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new Seat(assetId, SeatKind.FLIGHT, holder, acquiredAt, null));
    }

    @Test
    void rejectsExpiresAtBeforeAcquiredAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new Seat(assetId, SeatKind.FLIGHT, holder, acquiredAt, acquiredAt.minusSeconds(1)));
    }
}
