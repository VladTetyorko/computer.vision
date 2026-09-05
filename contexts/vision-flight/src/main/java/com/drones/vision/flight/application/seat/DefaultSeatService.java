package com.drones.vision.flight.application.seat;

import com.drones.vision.flight.domain.model.Seat;
import com.drones.vision.flight.domain.model.SeatKind;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.platform.AuditTrailPort;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one implementation of {@link SeatService} — an in-heap, TTL'd registry of who holds each of
 * an asset's two seats (docs/plans/active/CREW-CONTROL-PLAN.md &sect;3.1).
 *
 * <h2>Storage and atomicity</h2>
 * Both of one asset's seats live together in a single {@link SeatPair}, keyed by {@link AssetId}
 * in {@link #seatsByAsset}. Every transition — {@link #take}, {@link #preempt}, {@link #release},
 * {@link #forceRelease} — goes through exactly one {@link ConcurrentHashMap#compute} call on that
 * key, so a concurrent take/release/preempt for the same asset can never interleave into a torn
 * state. The remapping function only ever reads the map's current value and local parameters and
 * writes its verdict into a same-thread, single-invocation local array — it never calls back into
 * this class — so audit writes and {@link #onPreempted} listener callbacks happen only after
 * {@code compute} returns, outside the map's per-bin lock; this avoids the {@code
 * IllegalStateException} ("Recursive update") {@code ConcurrentHashMap} raises if a remapping
 * function re-enters the same key, and keeps a slow audit write or a misbehaving listener from
 * blocking unrelated seat operations on the same asset.
 *
 * <h2>Lazy expiry</h2>
 * There is no sweeper thread and no scheduled executor. A seat past its {@code expiresAt} simply
 * reads as free the next time anything looks at it — {@link #holder}, {@link #take}, {@link
 * #release}, and {@link #forceRelease} all route through {@link #liveOrNull}, which treats
 * {@code now >= expiresAt} as "nobody". Expiry itself writes nothing to the audit trail — the next
 * real {@link #take} tells the story; this mirrors {@code GeofenceMonitor}'s own honest in-heap,
 * no-scheduler posture.
 *
 * <h2>Audit</h2>
 * {@link #take} (a genuine take, not a renewal), {@link #release}, and {@link #preempt} each write
 * one {@link AuditEntry} ({@link AuditAction#UPDATED}, target {@link AuditTargetType#ASSET}, attrs
 * {@code {assetId, command:"SEAT", result}}, {@code result} one of {@code TAKE:<KIND>}, {@code
 * RELEASE:<KIND>}, {@code PREEMPT:<KIND>}). A renewal — {@code take} called by the seat's current,
 * still-live holder — only re-stamps {@code expiresAt} and is deliberately <b>not</b> audited: a
 * five-second heartbeat is noise, not history. {@link #forceRelease} cannot be audited here at all:
 * its frozen, two-argument signature ({@code AssetId}, {@code SeatKind}) carries no {@code actor}
 * — {@link AuditEntry} requires one — so the {@code FORCE:<KIND>} entry &sect;3.6 names is the
 * caller's own responsibility (the enforcement layer, which resolved the forcing manager's identity
 * to authorize the call in the first place, before ever reaching this method).
 *
 * <h2>Threading</h2>
 * Safe for concurrent use from any thread. {@link #seatsByAsset} and {@link #preemptListeners} are
 * both {@link ConcurrentHashMap}s; a listener list is a {@link CopyOnWriteArrayList}, cheap to read
 * (the common case — firing) and rare to mutate (registering, which happens once per session).
 */
public final class DefaultSeatService implements SeatService {

    private static final String ATTR_ASSET_ID = "assetId";
    private static final String ATTR_COMMAND = "command";
    private static final String ATTR_RESULT = "result";
    private static final String COMMAND_SEAT = "SEAT";

    private final Clock clock;
    private final AuditTrailPort auditTrail;
    private final long ttlMs;

    private final ConcurrentHashMap<AssetId, SeatPair> seatsByAsset = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SeatListenerKey, CopyOnWriteArrayList<Runnable>> preemptListeners =
            new ConcurrentHashMap<>();

    /**
     * @param clock      the time source {@code acquiredAt}/{@code expiresAt} are stamped from, and
     *                   lazy expiry is checked against; a fixed/mutable clock in tests, {@link
     *                   Clock#systemUTC()} in production
     * @param auditTrail where {@link #take}, {@link #release}, and {@link #preempt} record their
     *                   outcomes (see class javadoc "Audit")
     * @param ttlMs      how long a seat survives without a renewal; must be positive
     */
    public DefaultSeatService(Clock clock, AuditTrailPort auditTrail, long ttlMs) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
        if (ttlMs <= 0) {
            throw new IllegalArgumentException("ttlMs must be positive");
        }
        this.ttlMs = ttlMs;
    }

    @Override
    public Optional<Seat> holder(AssetId assetId, SeatKind kind) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        SeatPair pair = seatsByAsset.get(assetId);
        if (pair == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(liveOrNull(pair.forKind(kind), clock.instant()));
    }

    @Override
    public Seat take(AssetId assetId, SeatKind kind, UserId actor) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Instant now = clock.instant();
        Seat[] stampedHolder = new Seat[1];
        boolean[] renewal = new boolean[1];
        seatsByAsset.compute(assetId, (id, existing) -> {
            SeatPair pair = existing == null ? SeatPair.EMPTY : existing;
            Seat live = liveOrNull(pair.forKind(kind), now);
            if (live != null && !live.holder().equals(actor)) {
                throw new IllegalStateException("Asset " + assetId.value() + " " + kind.name()
                        + " seat is held by " + live.holder().value());
            }
            renewal[0] = live != null; // live != null here implies live.holder().equals(actor)
            Instant acquiredAt = live != null ? live.acquiredAt() : now;
            Seat stamped = new Seat(assetId, kind, actor, acquiredAt, now.plusMillis(ttlMs));
            stampedHolder[0] = stamped;
            return pair.withKind(kind, stamped);
        });
        if (!renewal[0]) {
            audit(actor, assetId, "TAKE:" + kind.name());
        }
        return stampedHolder[0];
    }

    @Override
    public Seat preempt(AssetId assetId, SeatKind kind, UserId actor) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Instant now = clock.instant();
        Seat[] stampedHolder = new Seat[1];
        seatsByAsset.compute(assetId, (id, existing) -> {
            SeatPair pair = existing == null ? SeatPair.EMPTY : existing;
            Seat stamped = new Seat(assetId, kind, actor, now, now.plusMillis(ttlMs));
            stampedHolder[0] = stamped;
            return pair.withKind(kind, stamped);
        });
        firePreempted(assetId, kind);
        audit(actor, assetId, "PREEMPT:" + kind.name());
        return stampedHolder[0];
    }

    @Override
    public void release(AssetId assetId, SeatKind kind, UserId actor) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Instant now = clock.instant();
        boolean[] released = new boolean[1];
        seatsByAsset.compute(assetId, (id, existing) -> {
            if (existing == null) {
                return null; // nothing to release: idempotent no-op
            }
            Seat live = liveOrNull(existing.forKind(kind), now);
            if (live == null || !live.holder().equals(actor)) {
                return existing; // free, expired, or held by someone else: idempotent no-op
            }
            released[0] = true;
            SeatPair updated = existing.withKind(kind, null);
            return updated.isEmpty() ? null : updated;
        });
        if (released[0]) {
            audit(actor, assetId, "RELEASE:" + kind.name());
        }
    }

    @Override
    public void forceRelease(AssetId assetId, SeatKind kind) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Instant now = clock.instant();
        boolean[] released = new boolean[1];
        seatsByAsset.compute(assetId, (id, existing) -> {
            if (existing == null) {
                return null; // already free: idempotent no-op
            }
            Seat live = liveOrNull(existing.forKind(kind), now);
            if (live == null) {
                return existing; // already free or expired: idempotent no-op
            }
            released[0] = true;
            SeatPair updated = existing.withKind(kind, null);
            return updated.isEmpty() ? null : updated;
        });
        if (released[0]) {
            // No audit here -- see class javadoc "Audit": this method's frozen signature carries
            // no actor to attribute a FORCE:<KIND> entry to.
            firePreempted(assetId, kind);
        }
    }

    @Override
    public void onPreempted(AssetId assetId, SeatKind kind, Runnable listener) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        preemptListeners.computeIfAbsent(new SeatListenerKey(assetId, kind), key -> new CopyOnWriteArrayList<>())
                .add(listener);
    }

    private void firePreempted(AssetId assetId, SeatKind kind) {
        List<Runnable> listeners = preemptListeners.get(new SeatListenerKey(assetId, kind));
        if (listeners == null) {
            return;
        }
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    private void audit(UserId actor, AssetId assetId, String result) {
        Map<String, String> attributes = Map.of(
                ATTR_ASSET_ID, assetId.value().toString(),
                ATTR_COMMAND, COMMAND_SEAT,
                ATTR_RESULT, result);
        auditTrail.record(AuditEntry.of(actor, AuditAction.UPDATED, AuditTargetType.ASSET,
                assetId.value().toString(), "Seat " + result + " for asset " + assetId.value(), attributes));
    }

    /**
     * {@code now >= expiresAt} reads as free — this is the lazy-expiry check every read/mutation
     * routes through. {@code seat} itself may be {@code null} (never held yet).
     */
    private static Seat liveOrNull(Seat seat, Instant now) {
        if (seat == null || !now.isBefore(seat.expiresAt())) {
            return null;
        }
        return seat;
    }

    /**
     * Both of one asset's seats, held together so a single {@link ConcurrentHashMap#compute} call
     * can transition either one atomically. Either component may be {@code null} (that seat free).
     */
    private record SeatPair(Seat flight, Seat camera) {
        static final SeatPair EMPTY = new SeatPair(null, null);

        Seat forKind(SeatKind kind) {
            return kind == SeatKind.FLIGHT ? flight : camera;
        }

        SeatPair withKind(SeatKind kind, Seat seat) {
            return kind == SeatKind.FLIGHT ? new SeatPair(seat, camera) : new SeatPair(flight, seat);
        }

        boolean isEmpty() {
            return flight == null && camera == null;
        }
    }

    private record SeatListenerKey(AssetId assetId, SeatKind kind) {
    }
}
