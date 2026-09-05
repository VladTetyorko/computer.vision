package com.drones.vision.flight.application.seat;

import com.drones.vision.flight.domain.model.Seat;
import com.drones.vision.flight.domain.model.SeatKind;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;

import java.util.Optional;

/**
 * The seat registry: who currently holds each of an asset's two seats
 * (docs/plans/active/CREW-CONTROL-PLAN.md &sect;3.1/&sect;3.2 — "one hand per surface").
 *
 * <p>This service answers only <em>who currently holds a seat</em> and lets that be changed; it
 * carries no opinion on <em>who is allowed to</em> — every authorization question (may this actor
 * fly, may this actor operate the camera, may this actor force a seat) is answered by the caller
 * (the imported {@code AssetAuthority} contract, &sect;3.7) before any of these methods are
 * invoked. {@link #preempt} in particular is unconditional precisely because of this split: it
 * trusts the caller to have already established the actor's right to take the seat regardless of
 * who holds it now.
 *
 * <p>The one implementation, {@link DefaultSeatService}, is in-heap with a TTL and lazy expiry —
 * see its own class javadoc for the full contract.
 */
public interface SeatService {

    /**
     * The live holder of one seat, or empty if the seat is free or its hold has lapsed.
     *
     * @param assetId the asset
     * @param kind    which seat
     * @return the current holder, or {@link Optional#empty()} if free or expired
     */
    Optional<Seat> holder(AssetId assetId, SeatKind kind);

    /**
     * Takes a free seat, or renews the caller's own already-held one.
     *
     * @param assetId the asset
     * @param kind    which seat
     * @param actor   the acting user
     * @return the (re)stamped seat
     * @throws IllegalStateException if the seat is currently held by someone else; the message
     *                                names that holder
     */
    Seat take(AssetId assetId, SeatKind kind, UserId actor);

    /**
     * Takes a seat unconditionally, regardless of who (if anyone) holds it now. Authorization is
     * entirely the caller's job — this method never refuses.
     *
     * @param assetId the asset
     * @param kind    which seat
     * @param actor   the acting user
     * @return the freshly-stamped seat
     */
    Seat preempt(AssetId assetId, SeatKind kind, UserId actor);

    /**
     * Releases a seat if, and only if, {@code actor} currently holds it. Idempotent: releasing a
     * free seat, an already-expired one, or one held by someone else is a silent no-op.
     *
     * @param assetId the asset
     * @param kind    which seat
     * @param actor   the acting user
     */
    void release(AssetId assetId, SeatKind kind, UserId actor);

    /**
     * Releases a seat regardless of who holds it — the manager path. Idempotent: a no-op if the
     * seat is already free or expired.
     *
     * @param assetId the asset
     * @param kind    which seat
     */
    void forceRelease(AssetId assetId, SeatKind kind);

    /**
     * Registers a synchronous listener fired whenever the named seat is displaced from a holder it
     * was previously held by — by {@link #preempt} or by {@link #forceRelease} of a seat that was
     * actually held. The RC-release hook (&sect;3.2 rules 3/4) is registered this way, so a
     * displaced pilot's sticks die within one watchdog period of the takeover, not two stick
     * sources being live at once.
     *
     * @param assetId  the asset
     * @param kind     which seat
     * @param listener called synchronously, on the calling thread, when that seat is displaced
     */
    void onPreempted(AssetId assetId, SeatKind kind, Runnable listener);
}
