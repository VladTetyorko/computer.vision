package com.drones.vision.api.live;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.port.DetectionDemandPort;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * {@code vision-api}'s implementation of {@link DetectionDemandPort} (docs/plans/active/CV-DEMAND-PLAN.md
 * &sect;3.5) — collapses the two driving protocols the frontend actually uses into the one fact
 * {@code DefaultStreamService}'s demand-poll task wants:
 *
 * <ul>
 *   <li><b>SSE half</b> — {@code assetId != null && watchingDetections.test(assetId)}: is any open
 *       {@code /api/live} connection subscribed to {@code detections:<assetId>} right now? That
 *       topic is opt-in and, today, subscribed only by the Fly cockpit — literally "a cockpit is
 *       open on this asset."</li>
 *   <li><b>Poll half</b> — has {@link #touched(StreamId)} been called for this stream within {@code
 *       pollTtl}? {@code StreamController}'s detections-read endpoint calls it on every request, so
 *       a Wall/Live page polling {@code GET /api/streams/{id}/detections} (no asset id on that
 *       path) counts as demand too.</li>
 * </ul>
 *
 * <h2>Why a {@code Predicate<AssetId>}, not {@link LiveUpdateRegistry} itself</h2>
 * This class needs exactly one capability off the registry — {@link
 * LiveUpdateRegistry#watchingDetections(AssetId)} — so it takes that capability as a narrow
 * functional seam instead of the whole (concrete, {@code final}) class. Same idiom {@code
 * ApplicationServiceWiring#usageTracker} already uses for {@code GeofenceMonitor::evaluate} rather
 * than the monitor itself: it keeps this class trivially testable with a plain lambda (including a
 * throwing one, for the never-throws contract below) without needing to construct or mock {@link
 * LiveUpdateRegistry} at all.
 *
 * <h2>Contract</h2>
 * {@link #detectionWanted} never throws, per {@link DetectionDemandPort}'s own contract: a
 * periodically-scheduled caller must never have one failing evaluation take down every later one.
 *
 * <p><b>An internal failure fails <i>open</i> — it answers "wanted", never "not wanted."</b> This is
 * the one decision in this class worth stating outright, because the swallowing catch makes the
 * opposite equally easy to write. An exception in {@code watchingDetections} means this class does
 * not <i>know</i> whether anyone is watching, and "unknown" must not be reported as the confident
 * negative "nobody is watching": that answer would gate detection off for every stream at once, and
 * — since the gate is what {@code DetectionState} reports — would additionally surface as {@code
 * IDLE_NO_VIEWERS} to an operator who is, in fact, sitting right there watching. A wrong answer that
 * costs CPU is recoverable and visible; a wrong answer that silently stops detection and then
 * explains itself with a falsehood is neither. Every other failure decision in this feature points
 * the same way (docs/plans/active/CV-DEMAND-PLAN.md §3.2/§3.3: an absent port gates nothing, and the
 * pipeline's own demand flag initialises {@code true}).
 *
 * <p>A {@code null} {@code assetId} is <i>not</i> a failure and is not covered by the above — it is
 * the ordinary "this stream has no owning asset" case, which simply skips the SSE half and lets the
 * poll half answer on its own.
 */
public final class LiveAndPollDetectionDemand implements DetectionDemandPort {

    private static final System.Logger LOG = System.getLogger(LiveAndPollDetectionDemand.class.getName());

    private final Predicate<AssetId> watchingDetections;
    private final Duration pollTtl;
    private final Supplier<Instant> clock;
    private final ConcurrentHashMap<StreamId, Instant> polledAt = new ConcurrentHashMap<>();

    public LiveAndPollDetectionDemand(Predicate<AssetId> watchingDetections, Duration pollTtl) {
        this(watchingDetections, pollTtl, Instant::now);
    }

    /** Test seam: an injectable clock so tests can move time without sleeping. */
    LiveAndPollDetectionDemand(Predicate<AssetId> watchingDetections, Duration pollTtl, Supplier<Instant> clock) {
        this.watchingDetections = Objects.requireNonNull(watchingDetections, "watchingDetections must not be null");
        this.pollTtl = Objects.requireNonNull(pollTtl, "pollTtl must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Stamps {@code streamId} as polled just now — called by {@code StreamController}'s detections
     * read endpoint on every {@code GET /api/streams/{id}/detections}, so a stream fed by that
     * endpoint counts as demanded for {@link #pollTtl} afterward.
     *
     * @param streamId the stream that was just polled
     */
    public void touched(StreamId streamId) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        polledAt.put(streamId, clock.get());
    }

    @Override
    public boolean detectionWanted(StreamId streamId, AssetId assetId) {
        try {
            return (assetId != null && watchingDetections.test(assetId)) || recentlyPolled(streamId);
        } catch (RuntimeException e) {
            // Fail OPEN -- see the class javadoc's Contract section. "We could not determine this"
            // is not "nobody is watching", and reporting it as the latter would stop detection
            // everywhere while telling a watching operator IDLE_NO_VIEWERS.
            LOG.log(System.Logger.Level.WARNING,
                    "detection-demand lookup failed for stream " + streamId.value()
                            + "; assuming demand (fail-open) rather than gating detection off", e);
            return true;
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
