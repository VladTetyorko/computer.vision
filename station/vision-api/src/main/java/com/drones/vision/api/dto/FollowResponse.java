package com.drones.vision.api.dto;

import com.drones.vision.perception.domain.model.FollowState;
import com.drones.vision.perception.domain.model.FollowStatus;

import java.time.Duration;
import java.time.Instant;

/**
 * The {@code "follow"} object on {@code GET /api/streams/{streamId}/tracks} (docs/plans/active/TRACK-FOLLOW-PLAN.md
 * &sect;3.1's frozen wire contract) — the operator-issued {@code FOLLOW} lock's own lifecycle, wire
 * form of {@link FollowStatus}.
 *
 * <p><b>Omitted from the parent response entirely</b> (not serialized as {@code null}) whenever
 * {@code StreamService#followStatus} reads empty — no lock has ever been issued on the stream, or
 * the most recent lock action was a release ({@code &sect;3.1} decision 3). That omission is the
 * caller's ({@link com.drones.vision.api.controller.StreamController#tracks}) job via {@code
 * @JsonInclude(NON_NULL)} on {@link StreamTracksResponse#follow()}; this record carries no such
 * annotation of its own, so once the object <i>is</i> present its individual fields serialize
 * literal {@code null} rather than omitting themselves — {@code lastSeenAt}/{@code
 * lastSeenAgeMillis}/{@code lastBox} really can be "known to be absent" (a lock still being
 * acquired has never seen a box yet), which is a different fact from the whole object being absent.
 *
 * @param state                current lifecycle state of the lock
 * @param trackId              the bound (or last-bound, while {@link FollowState#LOST}) track id;
 *                             {@code 0} before any bind has ever occurred
 * @param label                the bound track's elected label; {@code ""} before any bind, never
 *                             {@code null}
 * @param since                when the current {@link #state()} began
 * @param lastSeenAt           capture time of the last frame carrying a fresh box for this bind;
 *                             {@code null} before any bind has ever occurred
 * @param lastSeenAgeMillis    {@code now - lastSeenAt} in milliseconds, computed server-side so the
 *                             UI never re-derives it from a clock it does not share with the
 *                             pipeline; {@code null} exactly when {@code lastSeenAt} is
 * @param lastBox              the last known bounding box for this bind, normalized [0,1]; {@code
 *                             null} exactly when {@code lastSeenAt} is
 * @param reacquirable         whether cv-service's follow memory could plausibly still recover this
 *                             identity if the operator re-locks the same {@code trackId} now
 * @param recoveredAfterMillis how long this bind was dormant before being recovered from follow
 *                             memory; {@code 0} = not a recovery (this frame, or ever)
 * @param recoveryConfidence   follow-memory match score for a recovery, range [0,1]; {@code 0.0} =
 *                             not a recovery. Always {@code < 1.0} for a genuine recovery
 *                             (cv-service never resolves an appearance extractor in {@code FOLLOW}) —
 *                             render as a tie-breaker fact, never a percentage bar expected to fill
 */
public record FollowResponse(FollowState state, long trackId, String label, Instant since, Instant lastSeenAt,
                              Long lastSeenAgeMillis, BoundingBoxResponse lastBox, boolean reacquirable,
                              long recoveredAfterMillis, double recoveryConfidence) {

    /**
     * Maps a domain {@link FollowStatus} to its wire representation.
     *
     * @param status the status to map
     * @param now    the instant to compute {@code lastSeenAgeMillis} against — the request instant,
     *               never re-derived from {@code status} itself, so the record stays a plain
     *               snapshot rather than a value that goes stale the moment it is computed
     * @return the response body for {@code status}
     */
    public static FollowResponse from(FollowStatus status, Instant now) {
        Instant lastSeenAt = status.lastSeenAt();
        Long ageMillis = lastSeenAt == null ? null : Math.max(0L, Duration.between(lastSeenAt, now).toMillis());
        BoundingBoxResponse box = status.lastBox() == null ? null : BoundingBoxResponse.from(status.lastBox());
        return new FollowResponse(status.state(), status.trackId(), status.label(), status.since(), lastSeenAt,
                ageMillis, box, status.reacquirable(), status.recoveredAfterMillis(), status.recoveryConfidence());
    }
}
