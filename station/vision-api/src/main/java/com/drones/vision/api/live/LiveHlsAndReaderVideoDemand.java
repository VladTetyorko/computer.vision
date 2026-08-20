package com.drones.vision.api.live;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.port.VideoDemandPort;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * {@code vision-api}'s implementation of {@link VideoDemandPort}
 * (docs/plans/active/STREAM-STATE-PLAN.md &sect;3.2) &mdash; the union of every way this deployment can
 * observe that somebody is watching a stream's video. Structurally the sibling of {@link
 * LiveAndPollDetectionDemand}, deliberately: same narrow-{@code Predicate} seams, same never-throws
 * contract, same fail-open direction.
 *
 * <h2>The three terms, cheapest first</h2>
 * <ol>
 *   <li><b>SSE</b> &mdash; {@link LiveUpdateRegistry#watchingAsset(AssetId)}: an open {@code /api/live}
 *       connection carries a topic scoped to this stream's asset, i.e. a cockpit is open on it.</li>
 *   <li><b>Recent request</b> &mdash; {@link #touched(StreamId)} within {@code touchTtl}. Called by
 *       {@code HlsProxyController} on every HLS segment/playlist fetch through the app's own proxy,
 *       and by {@code StreamController}'s snapshot endpoint. Both are somebody looking at this
 *       stream's pixels.</li>
 *   <li><b>mediamtx readers</b> &mdash; {@code MediamtxReaderProbe#hasReaders}. Last because it is the
 *       only term that does network I/O, so the two in-memory terms short-circuit it whenever they
 *       can.</li>
 * </ol>
 *
 * <h2>Why the third term is not optional</h2>
 * <b>WHEP/WebRTC viewers connect straight to mediamtx.</b> They open no SSE connection and send no
 * request through this application &mdash; the first two terms are blind to them entirely. Without the
 * reader probe, the idle policy would confidently stop streams being watched over WebRTC, which is
 * this product's lowest-latency viewing path. When no probe is wired (no mediamtx in this
 * deployment), the seam defaults to {@code streamId -> false} and the first two terms answer alone.
 *
 * <h2>Contract</h2>
 * Never throws. <b>Any internal failure fails open &mdash; it answers "watched".</b> The stakes are
 * strictly higher than {@link LiveAndPollDetectionDemand}'s identical decision: a wrong {@code false}
 * there gates a detector, a wrong {@code false} here <i>stops the stream</i>, in front of an operator
 * using it, attributing it to nobody. A wrong {@code true} costs CPU until the next sweep and is
 * visible exactly where it is wrong.
 */
public final class LiveHlsAndReaderVideoDemand implements VideoDemandPort {

    private static final System.Logger LOG = System.getLogger(LiveHlsAndReaderVideoDemand.class.getName());

    private final Predicate<AssetId> watchingAsset;
    private final Duration touchTtl;
    private final Predicate<StreamId> hasReaders;
    private final Supplier<Instant> clock;
    private final ConcurrentHashMap<StreamId, Instant> touchedAt = new ConcurrentHashMap<>();

    /**
     * @param watchingAsset whether any live connection carries a topic scoped to an asset
     * @param touchTtl      how long a {@link #touched} stream counts as demanded afterward — must
     *                      comfortably exceed the SPA's own HLS segment cadence, or a viewer would
     *                      flicker in and out of being counted between segment fetches
     * @param hasReaders    whether mediamtx reports at least one reader on the stream's path, or
     *                      {@code streamId -> false} when no mediamtx is wired
     */
    public LiveHlsAndReaderVideoDemand(Predicate<AssetId> watchingAsset, Duration touchTtl,
                                        Predicate<StreamId> hasReaders) {
        this(watchingAsset, touchTtl, hasReaders, Instant::now);
    }

    /** Test seam: an injectable clock so tests move time without sleeping. */
    LiveHlsAndReaderVideoDemand(Predicate<AssetId> watchingAsset, Duration touchTtl,
                                 Predicate<StreamId> hasReaders, Supplier<Instant> clock) {
        this.watchingAsset = Objects.requireNonNull(watchingAsset, "watchingAsset must not be null");
        this.touchTtl = Objects.requireNonNull(touchTtl, "touchTtl must not be null");
        this.hasReaders = Objects.requireNonNull(hasReaders, "hasReaders must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Stamps {@code streamId} as requested just now.
     *
     * @param streamId the stream whose video was just fetched
     */
    public void touched(StreamId streamId) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        touchedAt.put(streamId, clock.get());
    }

    @Override
    public boolean videoWanted(StreamId streamId, AssetId assetId) {
        try {
            return (assetId != null && watchingAsset.test(assetId))
                    || recentlyTouched(streamId)
                    || hasReaders.test(streamId);
        } catch (RuntimeException e) {
            // Fail OPEN -- see the class javadoc's Contract section. Notably this is also what
            // happens when mediamtx is unreachable: no stream is reaped while we cannot see who is
            // watching, which is the correct way for this policy to break.
            LOG.log(System.Logger.Level.WARNING,
                    "video-demand lookup failed for stream " + streamId.value()
                            + "; assuming watched (fail-open) rather than stopping it", e);
            return true;
        }
    }

    private boolean recentlyTouched(StreamId streamId) {
        Instant now = clock.get();
        touchedAt.entrySet().removeIf(entry -> isExpired(entry.getValue(), now));
        return touchedAt.containsKey(streamId);
    }

    private boolean isExpired(Instant lastTouchedAt, Instant now) {
        return Duration.between(lastTouchedAt, now).compareTo(touchTtl) >= 0;
    }
}
