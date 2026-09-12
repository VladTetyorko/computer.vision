package com.drones.vision.api.live;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.port.TraceDemandPort;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * {@code vision-api}'s implementation of {@link TraceDemandPort} (docs/plans/active/
 * CV-ORCHESTRATION-PLAN.md §4.4, wave W2) — the concrete adapter {@link TraceDemandPort}'s own
 * javadoc names as "wave W2's own later station/vision-api step". Collapses the same two driving
 * protocols {@link LiveAndPollDetectionDemand} does, applied to the warm trace tier instead of
 * ordinary detection output:
 *
 * <ul>
 *   <li><b>SSE half</b> — {@code assetId != null && watchingTrace.test(assetId)}: is any open
 *       {@code /api/live} connection subscribed to {@code cv-trace:<assetId>} right now?</li>
 *   <li><b>Poll half</b> — has {@link #touched(StreamId)} been called for this stream within
 *       {@code pollTtl}? {@code StreamController}'s {@code GET /api/streams/{id}/cv/trace}
 *       endpoint calls it on every request.</li>
 * </ul>
 *
 * <h2>Fail CLOSED, not open — the deliberate deviation from {@link LiveAndPollDetectionDemand}</h2>
 * {@link #traceWanted} never throws (the same never-throws contract {@link TraceDemandPort}'s own
 * javadoc states), but unlike {@link LiveAndPollDetectionDemand#detectionWanted}, an internal
 * failure here answers {@code false}, not {@code true}. {@link TraceDemandPort}'s own javadoc says
 * why: over-answering "yes" for trace is not free the way it is for ordinary detection demand —
 * the warm tier's cost is real (extra cv-service contributor bookkeeping, a per-frame ledger this
 * JVM decodes and stores), and "unknown" defaulting to "keep paying for it" would turn a rare
 * lookup bug into a standing cost nobody asked for. Detection demand's fail-open stance protects an
 * operator who is actually watching from being told a lie ({@code IDLE_NO_VIEWERS}); trace has no
 * equivalent operator-facing lie to avoid, since a stream's ordinary boxes are unaffected either
 * way — only the (already-opt-in, already-expensive) trace tier is at stake.
 *
 * <p>A {@code null} {@code assetId} is, as in {@link LiveAndPollDetectionDemand}, the ordinary
 * "this stream has no owning asset" case, not a failure — it simply skips the SSE half.
 *
 * <h2>Why a {@code Predicate<AssetId>}, not {@link LiveUpdateRegistry} itself</h2>
 * Same reasoning as {@link LiveAndPollDetectionDemand}'s own javadoc: this class needs exactly one
 * capability off the registry, {@link LiveUpdateRegistry#watchingTrace(AssetId)}, taken as a
 * narrow functional seam.
 */
public final class LiveAndPollTraceDemand implements TraceDemandPort {

    private static final System.Logger LOG = System.getLogger(LiveAndPollTraceDemand.class.getName());

    private final Predicate<AssetId> watchingTrace;
    private final Duration pollTtl;
    private final Supplier<Instant> clock;
    private final ConcurrentHashMap<StreamId, Instant> polledAt = new ConcurrentHashMap<>();

    public LiveAndPollTraceDemand(Predicate<AssetId> watchingTrace, Duration pollTtl) {
        this(watchingTrace, pollTtl, Instant::now);
    }

    /** Test seam: an injectable clock so tests can move time without sleeping. */
    LiveAndPollTraceDemand(Predicate<AssetId> watchingTrace, Duration pollTtl, Supplier<Instant> clock) {
        this.watchingTrace = Objects.requireNonNull(watchingTrace, "watchingTrace must not be null");
        this.pollTtl = Objects.requireNonNull(pollTtl, "pollTtl must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Stamps {@code streamId} as polled just now — called by {@code StreamController}'s {@code
     * GET /api/streams/{id}/cv/trace} handler on every request, so a debug console polling that
     * endpoint counts as trace demand for {@link #pollTtl} afterward.
     *
     * @param streamId the stream that was just polled for its trace
     */
    public void touched(StreamId streamId) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        polledAt.put(streamId, clock.get());
    }

    @Override
    public boolean traceWanted(StreamId streamId, AssetId assetId) {
        try {
            return (assetId != null && watchingTrace.test(assetId)) || recentlyPolled(streamId);
        } catch (RuntimeException e) {
            // Fail CLOSED -- see the class javadoc's own section. Unlike detection demand, an
            // unknown answer here must not default to "keep paying the trace tier's cost."
            LOG.log(System.Logger.Level.WARNING,
                    "trace-demand lookup failed for stream " + streamId.value()
                            + "; assuming no demand (fail-closed) rather than over-answering yes", e);
            return false;
        }
    }

    private boolean recentlyPolled(StreamId streamId) {
        Instant now = clock.get();
        polledAt.entrySet().removeIf(entry -> isExpired(entry.getValue(), now));
        return polledAt.containsKey(streamId);
    }

    private boolean isExpired(Instant lastPolledAt, Instant now) {
        return Duration.between(lastPolledAt, now).compareTo(pollTtl) >= 0;
    }
}
