package com.drones.vision.flight.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;

import java.time.Instant;

/**
 * One in-heap, time-limited hold on one of an asset's two seats
 * (docs/plans/active/CREW-CONTROL-PLAN.md &sect;3.1).
 *
 * <p>Deliberately not a database row and not a field on {@code AssetUsage} — a seat exists exactly
 * as long as {@code application.seat.SeatService} keeps it in memory and expires on its own via
 * {@code expiresAt}; a station restart or a browser crash clears every seat for free, which is the
 * point (see the plan's own "three rejected placements" for why a durable holder is the wrong,
 * dangerous shape).
 *
 * @param assetId    the asset this seat belongs to
 * @param kind       which seat this is
 * @param holder     the user currently holding it
 * @param acquiredAt when the current holder first took it; unchanged by a renewal (only {@link
 *                   #expiresAt} moves on a renewal — a fresh {@link #acquiredAt} means a genuinely
 *                   new hold, not a continued one)
 * @param expiresAt  when this hold lapses absent a renewal; must not be before {@link #acquiredAt}
 */
public record Seat(AssetId assetId, SeatKind kind, UserId holder, Instant acquiredAt, Instant expiresAt) {

    public Seat {
        if (assetId == null) {
            throw new IllegalArgumentException("Seat assetId must not be null");
        }
        if (kind == null) {
            throw new IllegalArgumentException("Seat kind must not be null");
        }
        if (holder == null) {
            throw new IllegalArgumentException("Seat holder must not be null");
        }
        if (acquiredAt == null) {
            throw new IllegalArgumentException("Seat acquiredAt must not be null");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("Seat expiresAt must not be null");
        }
        if (expiresAt.isBefore(acquiredAt)) {
            throw new IllegalArgumentException("Seat expiresAt must not be before acquiredAt");
        }
    }
}
