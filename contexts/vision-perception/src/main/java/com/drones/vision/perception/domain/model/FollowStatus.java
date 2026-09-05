package com.drones.vision.perception.domain.model;

import com.drones.vision.kernel.BoundingBox;

import java.time.Instant;

/**
 * The current state of an operator-issued {@code FOLLOW} lock (docs/plans/active/TRACK-FOLLOW-PLAN.md
 * &sect;3.1) — maintained by {@code FollowTracker} and read via {@code StreamService.followStatus}.
 *
 * <p>{@code lastSeenAt}/{@code lastBox} are frozen at their last bound value while {@link
 * #state()} is {@link FollowState#LOST} (D3/D5) rather than cleared — the whole point of this
 * type existing is that a coasting-then-lost target does not lose its label or last known
 * position the instant {@code lockedTrackId} drops to {@code 0}. {@code trackId} and {@code
 * label} likewise stay at their last bound value through {@link FollowState#LOST}, so a
 * "re-acquire" affordance can resend the same {@code trackId} and the UI can keep naming the
 * target it is trying to get back.
 *
 * <p>{@code recoveredAfterMillis}/{@code recoveryConfidence} are non-zero exactly on the frame a
 * bind is proven to be a recovery from cv-service's L4 follow memory ({@link
 * TrackRef#identityConfidence()} &gt; 0 on that bind) — stamped from {@link TrackRef#dormantMillis()}
 * and {@link TrackRef#identityConfidence()} respectively. A fresh acquisition, and every
 * subsequent observation of the same continuous bind, carries {@code 0}/{@code 0.0}: these two
 * fields describe the recovery event itself, not an ongoing property of the bind.
 *
 * <p>{@code lastSeenAgeMillis} is deliberately <b>not</b> a field here — it is a DTO-only value
 * derived from the request instant in {@code FollowResponse.from}, so this record stays a fact
 * ("last seen at this instant") rather than a snapshot that goes stale the moment it is computed.
 *
 * @param state                current lifecycle state of the lock
 * @param trackId              the bound (or last-bound, while {@link FollowState#LOST}) track id;
 *                             {@code 0} before any bind has ever occurred
 * @param label                the bound track's elected label; {@code ""} before any bind, never
 *                             {@code null}
 * @param since                when the current {@link #state()} began
 * @param lastSeenAt           capture time of the last frame carrying a fresh box for this bind;
 *                             {@code null} before any bind has ever occurred
 * @param lastBox              the last known bounding box for this bind; both this and {@code
 *                             lastSeenAt} are null together, or non-null together
 * @param reacquirable         whether cv-service's follow memory could plausibly still recover
 *                             this identity if the operator re-locks the same {@code trackId} now
 * @param recoveredAfterMillis how long this bind was dormant before being recovered from follow
 *                             memory; {@code 0} = not a recovery (this frame, or ever)
 * @param recoveryConfidence   follow-memory match score for a recovery, range [0,1]; {@code 0.0} =
 *                             not a recovery
 */
public record FollowStatus(FollowState state, long trackId, String label, Instant since, Instant lastSeenAt,
                            BoundingBox lastBox, boolean reacquirable, long recoveredAfterMillis,
                            double recoveryConfidence) {

    public FollowStatus {
        if (state == null) {
            throw new IllegalArgumentException("FollowStatus state must not be null");
        }
        if (trackId < 0) {
            throw new IllegalArgumentException("FollowStatus trackId must not be negative: " + trackId);
        }
        if (label == null) {
            throw new IllegalArgumentException("FollowStatus label must not be null");
        }
        if (since == null) {
            throw new IllegalArgumentException("FollowStatus since must not be null");
        }
        if ((lastSeenAt == null) != (lastBox == null)) {
            throw new IllegalArgumentException(
                    "FollowStatus lastSeenAt and lastBox must both be null or both be non-null");
        }
        if (recoveredAfterMillis < 0) {
            throw new IllegalArgumentException(
                    "FollowStatus recoveredAfterMillis must not be negative: " + recoveredAfterMillis);
        }
        if (Double.isNaN(recoveryConfidence) || recoveryConfidence < 0.0 || recoveryConfidence > 1.0) {
            throw new IllegalArgumentException(
                    "FollowStatus recoveryConfidence must be within [0,1]: " + recoveryConfidence);
        }
    }
}
