package com.drones.vision.api.security;

import com.drones.vision.api.dto.SeatHolderResponse;
import com.drones.vision.api.dto.SeatsResponse;
import com.drones.vision.api.support.SeatSupport;
import com.drones.vision.flight.application.seat.SeatService;
import com.drones.vision.flight.domain.model.Seat;
import com.drones.vision.flight.domain.model.SeatKind;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * The one seat-enforcement collaborator every guarded verb calls (docs/plans/active/CREW-CONTROL-PLAN.md
 * &sect;3.2/&sect;3.3), the same shape {@link StreamAccess} already establishes for visibility.
 *
 * <h2>The flag gate (&sect;3.8)</h2>
 * When {@link SeatAccessSettings#enabled()} is {@code false}, every guard method below is a
 * pass-through (no seat is ever taken, no 409/403 is ever thrown) and {@link #seats(AssetId)} reports
 * both seats free with every {@code may*} flag {@code true} — the system behaves exactly as it did
 * before this feature existed. {@link #requireVisibleAsset(AssetId)} is the one exception: existence
 * hiding is base REST hygiene, not part of the crew feature, so it runs unconditionally.
 *
 * <h2>The five rules, where they live</h2>
 * <ul>
 *   <li>Rule 1 (take by use) — every {@code require*Seat} overload takes the seat as a side effect.</li>
 *   <li>Rule 2 (free means free, but only for someone with authority) — {@link #requireFlightSeat}/
 *       {@link #requireCameraSeat} consult {@link AssetAuthority#mayFly}/{@link
 *       AssetAuthority#mayOperateCamera} before taking a seat that is not already the caller's, so a
 *       caller with no standing for a seat kind is refused ({@link AccessDeniedException}, 403) rather
 *       than silently taking it — this is IC-2's entire purpose.</li>
 *   <li>Rule 3 (flight-seat holder always wins the camera) — {@link #requireCameraSeat(AssetId)}
 *       checks holdership <em>first</em>, before any authority question, and unconditionally {@link
 *       SeatService#preempt}s when the caller already holds FLIGHT — self-audited {@code
 *       PREEMPT:CAMERA} by {@link SeatService} itself.</li>
 *   <li>Rule 4 (only a manager forces the flight seat) — {@link #takeSeat}/{@link #releaseSeat}'s
 *       {@code force} path, gated on {@link AssetAuthority#mayForceSeat}, composes {@link
 *       SeatService#forceRelease} (fires {@code onPreempted} — the RC-release hook) then {@link
 *       SeatService#take}, plus this class's own {@code FORCE:<KIND>} audit entry ({@link
 *       SeatSupport#auditForce}) — {@link SeatService#preempt} is deliberately <em>not</em> reused
 *       here, since it always audits {@code PREEMPT:<KIND>}, not the {@code FORCE:FLIGHT} &sect;3.6
 *       names for this path.</li>
 *   <li>Rule 5 (silence releases) — {@link SeatService}'s own lazy expiry; nothing here to add.</li>
 * </ul>
 *
 * <h2>Two entry points for the flight seat</h2>
 * {@link #requireFlightSeat(AssetId)} reads the ambient {@link CurrentUser}; {@link
 * #requireFlightSeat(UserId, AssetId)} is the explicit-actor overload {@code
 * ManualControlWebSocketHandler} uses, mirroring {@link CapabilityAssetAuthority}'s own {@code
 * mayFly(Authority, UserId, AssetId)} overload and for the identical reason — that caller's {@code
 * engage} frame is dispatched on a thread with no {@code SecurityContext}. It trusts the caller to
 * have already checked {@code mayFly} against that same explicit actor (which {@code
 * ManualControlWebSocketHandler} does, via {@link CapabilityAssetAuthority}'s own explicit-actor
 * overload, before ever reaching this class), rather than re-deriving authority from the wrong
 * ({@code CurrentUser}) source.
 */
@Component
public final class SeatAccess {

    private final SeatService seatService;
    private final AssetAuthority assetAuthority;
    private final CurrentUser currentUser;
    private final SeatSupport seatSupport;
    private final SeatAccessSettings settings;

    public SeatAccess(SeatService seatService, AssetAuthority assetAuthority, CurrentUser currentUser,
                       SeatSupport seatSupport, SeatAccessSettings settings) {
        this.seatService = Objects.requireNonNull(seatService, "seatService must not be null");
        this.assetAuthority = Objects.requireNonNull(assetAuthority, "assetAuthority must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
        this.seatSupport = Objects.requireNonNull(seatSupport, "seatSupport must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
    }

    // ------------------------------------------------------------------
    // Guards for the five guarded-verb families (§3.3)
    // ------------------------------------------------------------------

    /**
     * Guards a flight command (arm/disarm/mode/RTH/e-stop/aux) or session engage/disengage: takes or
     * renews the caller's FLIGHT seat.
     *
     * @param assetId the asset being commanded
     * @throws AccessDeniedException if the caller may not fly this asset ({@link
     *                                AssetAuthority#mayFly})
     * @throws IllegalStateException if the seat is held by someone else — the frozen conflict message
     *                                (maps to 409)
     */
    public void requireFlightSeat(AssetId assetId) {
        requireFlightSeat(currentUser.userId(), assetId, assetAuthority.mayFly(assetId));
    }

    /**
     * The explicit-actor overload for {@code ManualControlWebSocketHandler}'s {@code engage} — see
     * class javadoc "Two entry points for the flight seat". Trusts that {@code mayFly} was already
     * verified for this exact {@code actor}.
     *
     * @param actor   the caller, resolved elsewhere (handshake session attributes, not {@link
     *                CurrentUser})
     * @param assetId the asset being engaged
     * @throws IllegalStateException if the seat is held by someone else
     */
    public void requireFlightSeat(UserId actor, AssetId assetId) {
        requireFlightSeat(actor, assetId, true);
    }

    private void requireFlightSeat(UserId actor, AssetId assetId, boolean mayFly) {
        if (!settings.enabled()) {
            return;
        }
        if (!mayFly) {
            throw new AccessDeniedException("Asset " + assetId.value() + " may not be flown by you");
        }
        takeOrConflict(assetId, SeatKind.FLIGHT, actor);
    }

    /**
     * Guards a camera/CV verb (stream start/stop, {@code PATCH .../config} including the target
     * lock): takes or renews the caller's CAMERA seat, unless the caller already holds FLIGHT, in
     * which case it unconditionally preempts (rule 3).
     *
     * @param assetId the asset whose camera is being operated
     * @throws AccessDeniedException if the caller may not operate this asset's camera ({@link
     *                                AssetAuthority#mayOperateCamera}) — never thrown for the
     *                                FLIGHT-seat holder (rule 3 bypasses this check entirely)
     * @throws IllegalStateException if the seat is held by someone else and the caller is not the
     *                                FLIGHT-seat holder
     */
    public void requireCameraSeat(AssetId assetId) {
        if (!settings.enabled()) {
            return;
        }
        UserId actor = currentUser.userId();
        if (holds(assetId, SeatKind.FLIGHT, actor)) {
            seatService.preempt(assetId, SeatKind.CAMERA, actor); // rule 3: unconditional, self-audited
            return;
        }
        if (!assetAuthority.mayOperateCamera(assetId)) {
            throw new AccessDeniedException("Asset " + assetId.value() + " camera may not be operated by you");
        }
        takeOrConflict(assetId, SeatKind.CAMERA, actor);
    }

    /**
     * As {@link #requireCameraSeat(AssetId)}, resolved from a device — a no-op if the device belongs
     * to no asset (mirrors {@code StreamAccess}'s own forgiving idiom for an unresolvable target).
     *
     * @param deviceId the device about to stream
     */
    public void requireCameraSeat(DeviceId deviceId) {
        seatSupport.assetIdOf(deviceId).ifPresent(this::requireCameraSeat);
    }

    /**
     * As {@link #requireCameraSeat(AssetId)}, resolved from a currently-running stream — a no-op if
     * the id does not currently name one.
     *
     * @param streamId the stream being started/stopped/reconfigured
     */
    public void requireCameraSeat(StreamId streamId) {
        seatSupport.assetIdOf(streamId).ifPresent(this::requireCameraSeat);
    }

    // ------------------------------------------------------------------
    // SeatController's own collaborator surface
    // ------------------------------------------------------------------

    /**
     * The 404 visibility gate every {@code SeatController} handler runs first, unconditionally
     * (&sect;3.8 — this is not part of the pass-through).
     *
     * @param assetId the asset whose seats are being read/changed
     * @throws NoSuchElementException if the asset is unknown, or known but outside the caller's
     *                                 {@link CurrentUser#scope()} (existence hidden either way)
     */
    public void requireVisibleAsset(AssetId assetId) {
        Ownership ownership = seatSupport.ownershipOf(assetId); // NoSuchElementException if unknown
        if (!currentUser.scope().includes(assetId, ownership)) {
            throw new NoSuchElementException("Unknown asset: " + assetId.value());
        }
    }

    /**
     * {@code GET /api/assets/{id}/seats}.
     *
     * @param assetId the asset to read
     * @return both seats' current state plus the caller's own authority, per the frozen shape
     */
    public SeatsResponse seats(AssetId assetId) {
        if (!settings.enabled()) {
            return new SeatsResponse(assetId.value().toString(), settings.ttlMs(),
                    SeatHolderResponse.free(), SeatHolderResponse.free(), true, true, true);
        }
        UserId actor = currentUser.userId();
        return new SeatsResponse(assetId.value().toString(), settings.ttlMs(),
                holderResponse(assetId, SeatKind.FLIGHT, actor), holderResponse(assetId, SeatKind.CAMERA, actor),
                assetAuthority.mayFly(assetId), assetAuthority.mayOperateCamera(assetId),
                assetAuthority.mayForceSeat(assetId));
    }

    /**
     * {@code POST /api/assets/{id}/seats/{kind}} — take-or-renew, idempotent for the holder; the
     * heartbeat. See &sect;3.6 for the exact preemption/force rules this composes.
     *
     * @param assetId the asset
     * @param kind    which seat
     * @param force   the request body's {@code force} flag — honoured only when the caller may force
     *                and someone else currently holds the seat; otherwise ignored, not rejected
     * @return the resulting seat state (as {@link #seats(AssetId)})
     * @throws AccessDeniedException if the caller has no standing for this seat kind
     * @throws IllegalStateException if held by another and not (successfully) forced — 409
     */
    public SeatsResponse takeSeat(AssetId assetId, SeatKind kind, boolean force) {
        if (!settings.enabled()) {
            return seats(assetId);
        }
        UserId actor = currentUser.userId();
        if (kind == SeatKind.CAMERA && holds(assetId, SeatKind.FLIGHT, actor)) {
            seatService.preempt(assetId, SeatKind.CAMERA, actor); // rule 3
            return seats(assetId);
        }
        boolean mayTake = kind == SeatKind.FLIGHT ? assetAuthority.mayFly(assetId)
                : assetAuthority.mayOperateCamera(assetId);
        if (!mayTake) {
            throw new AccessDeniedException(
                    "Asset " + assetId.value() + " " + lower(kind) + " seat may not be taken by you");
        }
        Optional<Seat> live = seatService.holder(assetId, kind);
        boolean forcing = force && assetAuthority.mayForceSeat(assetId)
                && live.isPresent() && !live.get().holder().equals(actor);
        if (forcing) {
            seatService.forceRelease(assetId, kind); // fires onPreempted -- the RC-release hook for FLIGHT
            seatService.take(assetId, kind, actor);
            seatSupport.auditForce(actor, assetId, kind);
        } else {
            takeOrConflict(assetId, kind, actor);
        }
        return seats(assetId);
    }

    /**
     * {@code DELETE /api/assets/{id}/seats/{kind}} — idempotent: a no-op for a free seat or the
     * caller's own (released); a manager may evict another holder, which fires the same
     * {@code onPreempted} RC-release hook a forced {@link #takeSeat} does.
     *
     * @param assetId the asset
     * @param kind    which seat
     * @throws AccessDeniedException if held by another and the caller may not force it — 403
     */
    public void releaseSeat(AssetId assetId, SeatKind kind) {
        if (!settings.enabled()) {
            return;
        }
        UserId actor = currentUser.userId();
        Optional<Seat> live = seatService.holder(assetId, kind);
        if (live.isPresent() && !live.get().holder().equals(actor)) {
            if (!assetAuthority.mayForceSeat(assetId)) {
                throw new AccessDeniedException("Asset " + assetId.value() + " " + lower(kind)
                        + " seat is held by " + seatSupport.displayNameOrId(live.get().holder()));
            }
            seatService.forceRelease(assetId, kind);
            seatSupport.auditForce(actor, assetId, kind);
            return;
        }
        seatService.release(assetId, kind, actor);
    }

    // ------------------------------------------------------------------
    // Shared internals
    // ------------------------------------------------------------------

    private boolean holds(AssetId assetId, SeatKind kind, UserId actor) {
        return seatService.holder(assetId, kind).map(seat -> seat.holder().equals(actor)).orElse(false);
    }

    private SeatHolderResponse holderResponse(AssetId assetId, SeatKind kind, UserId actor) {
        return seatService.holder(assetId, kind)
                .map(seat -> new SeatHolderResponse(seat.holder().value().toString(),
                        seatSupport.displayNameOrId(seat.holder()), seat.acquiredAt(), seat.expiresAt(),
                        seat.holder().equals(actor)))
                .orElseGet(SeatHolderResponse::free);
    }

    /**
     * Takes {@code kind} for {@code actor}, or throws the frozen conflict message on a genuine hold
     * by someone else. {@link SeatService#take}'s own {@link IllegalStateException} names the holder
     * in the wrong shape for the wire (uppercase kind, raw {@link UserId}) — this rebuilds it from a
     * fresh read, retrying once if the seat was freed in the (nanosecond) race between the failed
     * {@code take} and this read, rather than surface a conflict for a seat that is no longer held.
     */
    private void takeOrConflict(AssetId assetId, SeatKind kind, UserId actor) {
        try {
            seatService.take(assetId, kind, actor);
        } catch (IllegalStateException first) {
            Optional<Seat> live = seatService.holder(assetId, kind);
            if (live.isEmpty()) {
                seatService.take(assetId, kind, actor); // freed in the race window; safe to retry once
                return;
            }
            seatSupport.auditDenied(actor, assetId, kind);
            throw new IllegalStateException("Asset " + assetId.value() + " " + lower(kind) + " seat is held by "
                    + seatSupport.displayNameOrId(live.get().holder()));
        }
    }

    private static String lower(SeatKind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }
}
