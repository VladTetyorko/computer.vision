package com.drones.vision.perception.application.pipeline;

import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.DetectionSource;
import com.drones.vision.perception.domain.model.FollowState;
import com.drones.vision.perception.domain.model.FollowStatus;
import com.drones.vision.perception.domain.model.TargetLock;
import com.drones.vision.perception.domain.model.TrackRef;
import com.drones.vision.perception.domain.model.TrackingTelemetry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The per-stream <b>follow tracker</b> (docs/plans/active/TRACK-FOLLOW-PLAN.md &sect;3.1/W2): a
 * package-private peer of {@link TrackBook}/{@link TrackingStatsWindow}, fed the same fan-out, that
 * turns raw {@link TrackingTelemetry#lockedTrackId()} bounces into an honest {@link FollowStatus}
 * lifecycle for whichever {@code FOLLOW} lock the operator currently holds.
 *
 * <p><b>Why this exists</b> (D3/D5): {@code lockedTrackId} alone cannot tell a UI whether a target
 * that just dropped to {@code 0} was released, lost, or is still being requested, nor can it carry
 * a label or last-known box once the id is gone. This class is the one place that turns the frame-
 * by-frame wire fact into the five-state lifecycle {@link FollowState} names, so every consumer
 * (today: {@code StreamService.followStatus}) reads the same answer.
 *
 * <h2>State machine</h2>
 * <ul>
 *   <li>{@link #lockRequested} — called whenever {@link StreamPipeline#updateConfig} observes the
 *       resolved {@link com.drones.vision.perception.domain.model.TrackingConfig#lock()} genuinely
 *       change (a fresh {@code lockSeq}, which {@code TrackingConfigPatch.foldOnto} stamps on every
 *       real lock action, including a same-id re-acquire). A release form drops straight to
 *       {@link FollowState#RELEASED} and then to nothing — {@link #status()} reads empty from the
 *       very next call. Any other form arms {@link FollowState#REQUESTING}.</li>
 *   <li>{@link #accept} — called on every {@link DetectionResult}, exactly where {@link TrackBook}
 *       and {@link TrackingStatsWindow} are also fed. {@code lockedTrackId() == 0} means unbound:
 *       still {@link FollowState#REQUESTING} while a request is pending, otherwise a bound target
 *       that just dropped freezes into {@link FollowState#LOST}. {@code lockedTrackId() != 0} means
 *       bound: the matching {@link Detection}'s {@link TrackRef#source()} decides {@link
 *       FollowState#HOLDING} vs {@link FollowState#COASTING}, and a bind whose {@link
 *       TrackRef#identityConfidence()} is positive is stamped as a recovery from cv-service's L4
 *       follow memory — coming out of {@link FollowState#LOST} with the identical {@code trackId}
 *       counts as exactly this, never as a fresh loss-then-acquire cycle.</li>
 * </ul>
 *
 * <h2>Time</h2>
 * Every instant comes from {@link DetectionResult#capturedAt()} — the same timebase {@link
 * TrackBook}, {@link DetectionEventEngine} and {@link DetectionExtrapolator} already use — never
 * wall-clock time. No clock is injected because none is needed.
 *
 * <h2>Threading</h2>
 * Every method is {@code synchronized}: {@link #accept}/{@link #lockRequested} run on whichever
 * thread completed an inference or applied a config patch, while {@link #status()} is read from an
 * unrelated HTTP thread.
 */
final class FollowTracker {

    /**
     * Default follow-memory re-acquisition window, mirroring cv-service's own default (session.py /
     * {@code memory.py:83}, docs/plans/active/TRACK-FOLLOW-PLAN.md &sect;3.1).
     *
     * <p><b>Not yet wired to a per-stream override.</b> The wire carries a per-stream {@code
     * TrackingConfig.memory_ttl_millis} (proto/vision/v1/cv.proto field 10), but nothing on the Java
     * side decodes it — this module's own {@link com.drones.vision.perception.domain.model.TrackingConfig}
     * has no such component. Threading the real per-stream value through therefore needs a
     * domain-layer wave to add it there first; until then, this constant is the honest current
     * answer, injected through the constructor (never inlined into the comparison itself) so that
     * future wave changes exactly one call site ({@link StreamPipeline}'s construction of this
     * class), not this class's own logic.
     */
    static final Duration DEFAULT_MEMORY_TTL = Duration.ofSeconds(30);

    private final Duration memoryTtl;

    private FollowStatus status;
    private boolean requestPending;
    private boolean lockIsTrackIdForm;

    /** Uses {@link #DEFAULT_MEMORY_TTL}. */
    FollowTracker() {
        this(DEFAULT_MEMORY_TTL);
    }

    /**
     * @param memoryTtl how long after {@code lastSeenAt} a {@link FollowState#LOST} bind is still
     *                  considered {@link FollowStatus#reacquirable()}; must be positive
     */
    FollowTracker(Duration memoryTtl) {
        Objects.requireNonNull(memoryTtl, "memoryTtl must not be null");
        if (memoryTtl.isZero() || memoryTtl.isNegative()) {
            throw new IllegalArgumentException("memoryTtl must be positive, was " + memoryTtl);
        }
        this.memoryTtl = memoryTtl;
    }

    /**
     * Registers a genuine lock action — called only when the resolved lock actually changed (a
     * release, a fresh acquire, or a re-acquire), never for a config patch that left the lock alone.
     *
     * <p>A release form drops {@link #status()} to empty immediately: {@link FollowState#RELEASED}
     * is a transition, not an observable resting state, per docs/plans/active/TRACK-FOLLOW-PLAN.md
     * &sect;3.1 ("after which {@code followStatus()} reads empty"). Any other form arms {@link
     * FollowState#REQUESTING}, which the next {@link #accept} call turns into a stamped status, and
     * remembers whether this lock was issued in {@code trackId} form — {@link
     * FollowStatus#reacquirable()} is gated to that form only (cv-service's memory path never
     * applies to a box/point lock).
     *
     * @param lock the newly folded lock; never {@code null}
     */
    synchronized void lockRequested(TargetLock lock) {
        Objects.requireNonNull(lock, "lock must not be null");
        if (lock.release()) {
            status = null;
            requestPending = false;
            lockIsTrackIdForm = false;
            return;
        }
        requestPending = true;
        lockIsTrackIdForm = lock.trackId() != null;
    }

    /**
     * Folds one completed result into this bind's lifecycle. Results carrying no tracking telemetry
     * at all ({@link DetectionResult#tracking()} {@code == null} — tracking off for the stream) are
     * ignored, exactly like {@link TrackingStatsWindow#accept}.
     *
     * @param result the completed result to fold in; never {@code null}
     */
    synchronized void accept(DetectionResult result) {
        Objects.requireNonNull(result, "result must not be null");
        TrackingTelemetry telemetry = result.tracking();
        if (telemetry == null) {
            return;
        }
        Instant at = result.capturedAt();
        long lockedTrackId = telemetry.lockedTrackId();
        if (lockedTrackId == 0L) {
            acceptUnbound(at);
        } else {
            acceptBound(result.detections(), lockedTrackId, at);
        }
    }

    private void acceptUnbound(Instant at) {
        if (requestPending) {
            if (status == null || status.state() != FollowState.REQUESTING) {
                status = new FollowStatus(FollowState.REQUESTING, 0L, "", at, null, null, false, 0L, 0.0);
            }
            return;
        }
        if (status == null) {
            return; // never requested, or already released: nothing to report
        }
        if (status.state() == FollowState.LOST) {
            // still lost: only the age-derived reacquirable flag may have moved
            status = new FollowStatus(FollowState.LOST, status.trackId(), status.label(), status.since(),
                    status.lastSeenAt(), status.lastBox(), computeReacquirable(status.lastSeenAt(), at),
                    status.recoveredAfterMillis(), status.recoveryConfidence());
            return;
        }
        // was HOLDING/COASTING and just dropped with no pending re-request: freshly lost. Freeze
        // lastSeenAt/lastBox/label at their bound value (D3/D5) -- only `since`/`state` change here.
        status = new FollowStatus(FollowState.LOST, status.trackId(), status.label(), at,
                status.lastSeenAt(), status.lastBox(), computeReacquirable(status.lastSeenAt(), at),
                status.recoveredAfterMillis(), status.recoveryConfidence());
    }

    private void acceptBound(List<Detection> detections, long lockedTrackId, Instant at) {
        requestPending = false;
        Detection matched = findDetection(detections, lockedTrackId);
        FollowState previousState = status == null ? null : status.state();
        boolean continuedSameBind = (previousState == FollowState.HOLDING || previousState == FollowState.COASTING)
                && status.trackId() == lockedTrackId;

        FollowState state;
        if (matched != null) {
            state = matched.track().source() == DetectionSource.DETECTOR ? FollowState.HOLDING : FollowState.COASTING;
        } else if (continuedSameBind) {
            // No fresh geometry this frame (e.g. this bind's label was just deny-filtered away by
            // StreamPipeline.applyLabelFilters) -- stay in whichever state was already true rather
            // than fabricating one.
            state = previousState;
        } else {
            state = FollowState.COASTING;
        }

        String label = matched != null ? matched.label() : (continuedSameBind ? status.label() : "");
        Instant lastSeenAt = matched != null ? at : (continuedSameBind ? status.lastSeenAt() : null);
        BoundingBox lastBox = matched != null ? matched.box() : (continuedSameBind ? status.lastBox() : null);
        Instant since = (!continuedSameBind || state != previousState) ? at : status.since();

        long recoveredAfterMillis;
        double recoveryConfidence;
        if (continuedSameBind) {
            recoveredAfterMillis = status.recoveredAfterMillis();
            recoveryConfidence = status.recoveryConfidence();
        } else if (matched != null && matched.track().identityConfidence() > 0.0) {
            recoveredAfterMillis = matched.track().dormantMillis();
            recoveryConfidence = matched.track().identityConfidence();
        } else {
            recoveredAfterMillis = 0L;
            recoveryConfidence = 0.0;
        }

        status = new FollowStatus(state, lockedTrackId, label, since, lastSeenAt, lastBox, false,
                recoveredAfterMillis, recoveryConfidence);
    }

    private static Detection findDetection(List<Detection> detections, long trackId) {
        for (Detection detection : detections) {
            TrackRef track = detection.track();
            if (track != null && track.trackId() == trackId) {
                return detection;
            }
        }
        return null;
    }

    private boolean computeReacquirable(Instant lastSeenAt, Instant at) {
        if (lastSeenAt == null || !lockIsTrackIdForm) {
            return false;
        }
        return Duration.between(lastSeenAt, at).toMillis() < memoryTtl.toMillis();
    }

    /**
     * Resets to "never requested". Called by {@link StreamPipeline#clearDetectionDerivedState()} on
     * a model re-arm or a detection-gate close, for the same reason {@link TrackBook#clear()} is:
     * whatever this lock was bound to described the pipeline's previous state, not its next one.
     */
    synchronized void clear() {
        status = null;
        requestPending = false;
        lockIsTrackIdForm = false;
    }

    /**
     * @return the current follow status, or {@link Optional#empty()} if no lock has ever been
     *         issued on this stream, or the most recent lock action was a release.
     */
    synchronized Optional<FollowStatus> status() {
        return Optional.ofNullable(status);
    }
}
