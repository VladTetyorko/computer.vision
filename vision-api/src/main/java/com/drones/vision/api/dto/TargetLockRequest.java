package com.drones.vision.api.dto;

import com.drones.vision.domain.model.TargetLock;

/**
 * The {@code lock} object nested inside {@link TrackingConfigRequest} on {@code PATCH
 * /api/streams/{streamId}/config} (docs/plans/done/TRACKING-PLAN.md &sect;4.D) — which object {@code FOLLOW}
 * mode should hold.
 *
 * <p><b>Exactly one of three forms.</b> {@code {"trackId": 7}}, {@code {"pointX": .., "pointY": ..}}
 * or {@code {"release": true}}; two of the three present is a <b>400</b>, and so is none of them.
 * That rule is not re-implemented here — {@link TargetLock}'s own compact constructor is the single
 * arbiter, and its {@link IllegalArgumentException} surfaces as 400 through {@code
 * ApiExceptionHandler} exactly like every other domain-validated field in this module (the same
 * "map shapes, let the domain validate" idiom {@code GeofenceZoneRequest}/{@code CreateMarkRequest}
 * already follow).
 *
 * <p><b>There is deliberately no {@code lockSeq} field.</b> A client never allocates one:
 * {@link #toTargetLock()} leaves it at {@code 0} and {@code DefaultStreamService#updateConfig}
 * (vision-application) stamps a fresh value from the stream's own monotonic counter. That is what
 * stops a replayed stale lock from resurrecting an abandoned target, and it keeps the UI free of a
 * counter it has no business tracking (docs/extracts/TRACKING-ORCHESTRATION.md &sect;3.3).
 *
 * @param trackId lock onto an existing track by id — the track-id form
 * @param pointX  normalized [0,1] click point x — the point form; requires {@code pointY}
 * @param pointY  normalized [0,1] click point y — requires {@code pointX}
 * @param release {@code true} drops the current lock — the release form; {@code null}/{@code false}
 *                is simply "not this form", never a form of its own
 */
public record TargetLockRequest(Long trackId, Double pointX, Double pointY, Boolean release) {

    /**
     * Maps this request to the domain lock, with {@code lockSeq} left at {@code 0} for the
     * application layer to stamp.
     *
     * @return the requested lock
     * @throws IllegalArgumentException if this is not exactly one of the three forms, or a point is
     *                                  outside [0,1] (&rarr; 400)
     */
    public TargetLock toTargetLock() {
        return new TargetLock(0L, trackId, pointX, pointY, release != null && release);
    }
}
