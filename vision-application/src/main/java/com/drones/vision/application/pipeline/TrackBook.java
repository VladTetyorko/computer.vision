package com.drones.vision.application.pipeline;

import com.drones.vision.domain.model.Detection;
import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.model.TrackRef;
import com.drones.vision.domain.model.TrackState;
import com.drones.vision.domain.model.TrackedObject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The per-stream <b>track book</b> (docs/plans/done/TRACKING-PLAN.md &sect;5.E): a {@code Map<Long,
 * TrackedObject>} keyed by track id, maintaining each track's {@code firstSeen}/{@code lastSeen}
 * lifetime and expiring tracks that have ended.
 *
 * <p><b>It does no association.</b> Deciding which detection on this frame is the same object as
 * one on the previous frame is the tracker's job, inside cv-service, which stamps the answer onto
 * every {@link Detection} as a {@link TrackRef}. This class books what arrived, keyed by the id it
 * arrived with. There is no matching, no gating, no prediction and no id allocation here — that is
 * the difference between a book and a tracker, and it is what keeps this class ~120 lines instead
 * of a second, divergent implementation of the thing cv-service already does.
 *
 * <h2>This is a read model for any consumer, not the SSE topic's private cache</h2>
 * <b>Invariant P3</b> (docs/plans/done/TRACKING-PLAN.md &sect;3.4, TRACKING-ORCHESTRATION.md &sect;2.3): the
 * pipeline's product is a <i>sink-agnostic</i> track update stream, and this book is its
 * application-side read model. Three consumers are planned and all three read exactly this:
 * <ul>
 *   <li><b>UI</b> (today) — {@code GET /api/streams/{id}/tracks} and the live overlay: boxes, ids,
 *       trails, click-to-follow. Consumer-local knowledge it adds: screen size, canvas scale,
 *       colour palette.</li>
 *   <li><b>Geo / COP</b> (docs/main/TWO-TARGETS-PLAN.md &sect;S2) — box bottom-center plus camera pose
 *       &rarr; a ground coordinate &rarr; a map mark carrying the track id. Consumer-local
 *       knowledge it adds: camera lat/lon/AGL/yaw/pitch/FOV and {@code GeoProjection}.</li>
 *   <li><b>Guidance</b> (deferred and gated) — the locked track's offset from frame center as the
 *       error signal for a centering controller. Consumer-local knowledge it adds: the camera's
 *       FOV and the vehicle's mode/authority state.</li>
 * </ul>
 * Concretely, that invariant forbids three things in this file, and a reviewer can check them
 * mechanically: <b>no consumer-specific field</b> (no canvas position, no ground coordinate, no
 * center offset — every one of those is derivable by the consumer that owns the extra knowledge,
 * and none of them is derivable here); <b>no map / FOV / screen / camera-pose concept</b>; and
 * <b>no reference to SSE, a topic, a publisher or a DTO</b>. Adding any of them would couple this
 * book to whichever consumer asked first and force the next two to work around it.
 *
 * <h2>Expiry</h2>
 * Two rules, both evaluated at the end of every {@link #accept}:
 * <ul>
 *   <li><b>{@link TrackState#LOST} is terminal</b> — a track observed as {@code LOST} leaves the
 *       book immediately. Recovering the same id after an occlusion is cv-service's job (it holds
 *       the track in its own buffer for {@code maxAgeFrames}, docs/plans/done/TRACKING-PLAN.md &sect;3.2), and
 *       when it does recover it, the id simply arrives again and is re-booked. Keeping a dead track
 *       here so it could be "recognised" on return would be association by another name.</li>
 *   <li><b>Silence expires a track too</b> — anything unobserved for {@code retention} is dropped,
 *       measured against the newest {@code capturedAt} this book has seen. A track that stops
 *       arriving without ever being reported {@code LOST} (a cv-worker swap, a reconnect, tracking
 *       switched off mid-stream) has no terminal state to leave on, so without this rule the map
 *       would grow for the life of the stream.</li>
 * </ul>
 *
 * <h2>Time</h2>
 * Every timestamp comes from {@link DetectionResult#capturedAt()} — the frame's own capture time,
 * the same timebase {@link DetectionEventEngine} and {@link DetectionExtrapolator} already use —
 * never wall-clock time. No clock is injected because none is needed, and tests are therefore
 * exactly deterministic rather than merely tolerant.
 *
 * <h2>Threading</h2>
 * Every method is {@code synchronized}: {@link #accept} runs on whichever executor thread completed
 * an inference, while {@link #tracks()} is read from an unrelated HTTP thread. {@link #tracks()}
 * returns an immutable snapshot, so a caller iterating it can never see a concurrent modification.
 */
final class TrackBook {

    /** Default {@code retention}, used by the no-arg constructor. @see StreamPipelineSettings#trackRetention() */
    static final Duration DEFAULT_RETENTION = StreamPipelineSettings.defaults().trackRetention();

    private final Duration retention;

    private final Map<Long, TrackedObject> byTrackId = new HashMap<>();

    /** The newest {@link DetectionResult#capturedAt()} accepted so far — this book's notion of "now". */
    private Instant latestObservedAt;

    /** Uses {@link StreamPipelineSettings#defaults()}'s track retention. */
    TrackBook() {
        this(DEFAULT_RETENTION);
    }

    /**
     * @param retention how long a track that stops arriving stays in the book, measured against the
     *                  newest observed {@code capturedAt}; must be positive
     */
    TrackBook(Duration retention) {
        Objects.requireNonNull(retention, "retention must not be null");
        if (retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("retention must be positive, was " + retention);
        }
        this.retention = retention;
    }

    /**
     * Books every {@linkplain Detection#track() tracked} detection this result carries; untracked
     * detections ({@code track() == null} — tracking off for the stream, or not yet associated) are
     * ignored entirely, so a stream running {@code TrackingMode.OFF} leaves this book permanently
     * empty and costs one null check per detection.
     *
     * <p>{@code firstSeen} is preserved across observations of the same id and {@code lastSeen}
     * advances to this result's {@code capturedAt}. A result that lands out of order (an inference
     * completing after a later one, which the pipeline's own fan-out cannot prevent) never rewinds a
     * track: an observation older than the one already booked updates {@code firstSeen} if it is
     * genuinely earlier and is otherwise discarded, so the booked {@link TrackedObject#detection()}
     * is always the freshest one seen.
     *
     * @param result the completed result to book; never {@code null}
     */
    synchronized void accept(DetectionResult result) {
        Objects.requireNonNull(result, "result must not be null");
        Instant at = result.capturedAt();
        if (latestObservedAt == null || at.isAfter(latestObservedAt)) {
            latestObservedAt = at;
        }
        for (Detection detection : result.detections()) {
            TrackRef track = detection.track();
            if (track != null) {
                book(track.trackId(), detection, at);
            }
        }
        expire();
    }

    private void book(long trackId, Detection detection, Instant at) {
        TrackedObject existing = byTrackId.get(trackId);
        if (existing == null) {
            byTrackId.put(trackId, new TrackedObject(trackId, detection, at, at));
        } else if (!at.isBefore(existing.lastSeen())) {
            byTrackId.put(trackId, new TrackedObject(trackId, detection, existing.firstSeen(), at));
        } else if (at.isBefore(existing.firstSeen())) {
            // Out-of-order straggler that predates this track's booked lifetime: it widens the
            // lifetime but must not replace the fresher observation already booked.
            byTrackId.put(trackId, new TrackedObject(trackId, existing.detection(), at, existing.lastSeen()));
        }
    }

    /** @see TrackBook the class javadoc's "Expiry" section for both rules and why each exists */
    private void expire() {
        Instant staleBefore = latestObservedAt.minus(retention);
        for (Iterator<TrackedObject> it = byTrackId.values().iterator(); it.hasNext(); ) {
            TrackedObject tracked = it.next();
            TrackRef track = tracked.detection().track();
            boolean lost = track != null && track.state() == TrackState.LOST;
            if (lost || !tracked.lastSeen().isAfter(staleBefore)) {
                it.remove();
            }
        }
    }

    /**
     * Empties the book. Called by {@link StreamPipeline#updateConfig} on a model re-arm, for the
     * same reason the extrapolator is reset there: track ids are allocated by whatever the detector
     * was feeding, so carrying them across a model swap would attribute one model's objects to
     * another's ids.
     */
    synchronized void clear() {
        byTrackId.clear();
        latestObservedAt = null;
    }

    /**
     * @return an immutable snapshot of every currently booked track, ordered by {@code trackId}
     *         ascending (the order docs/plans/done/TRACKING-PLAN.md &sect;4.E freezes for {@code GET
     *         /api/streams/{id}/tracks}, so no consumer has to sort). Empty before the first tracked
     *         detection arrives, and permanently empty for a stream running {@code
     *         TrackingMode.OFF}. Never {@code null}, never throws.
     */
    synchronized List<TrackedObject> tracks() {
        List<TrackedObject> snapshot = new ArrayList<>(byTrackId.values());
        snapshot.sort(Comparator.comparingLong(TrackedObject::trackId));
        return List.copyOf(snapshot);
    }
}
