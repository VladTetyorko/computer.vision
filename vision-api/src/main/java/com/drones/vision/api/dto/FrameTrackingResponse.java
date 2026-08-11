package com.drones.vision.api.dto;

import com.drones.vision.domain.model.TrackingTelemetry;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The nested {@code "tracking"} object on {@link DetectionResultResponse} (docs/TRACKING-PLAN.md
 * &sect;4.G) — one frame's duty-cycle facts, so a client can render a tracker-only frame honestly
 * instead of pretending the detector ran.
 *
 * <p>{@code @JsonInclude(NON_NULL)} for one field only: <b>{@code detectorReason} is present iff
 * {@code detectorRan} is {@code true}</b> (&sect;4.G — it answers <i>why</i> the pass ran, so it has
 * no meaning on a frame where none did; {@link TrackingTelemetry} enforces the same pairing
 * domain-side). "What was the last reason" is a different question, answered by {@code
 * stats.lastDetectorReason} on {@code GET /api/streams/{streamId}/tracks}, which is a window over
 * frames rather than a fact about this one.
 *
 * <p>{@code trackerMillis} is a fractional millisecond count on purpose — the per-frame tracker cost
 * this whole design exists to make small is ~0.4 ms (docs/TRACKING-PLAN.md &sect;8), and a {@code
 * Duration#toMillis()} would report every one of them as {@code 0}.
 *
 * @param detectorRan    {@code false} means this frame was tracker-only — the duty cycle's whole point
 * @param detectorReason why the detector pass ran ({@code ALWAYS}/{@code CADENCE}/{@code
 *                       TRACKER_FAILED}/{@code NO_LOCK}/{@code BOX_INVALID}/{@code COASTED_OUT}),
 *                       absent when none did
 * @param trackerMillis  the per-frame tracker cost in milliseconds, fractional; {@code 0} when no
 *                       tracker ran on this frame
 * @param engineId       the engine that actually served this frame ({@code ""} = none) — not
 *                       necessarily the one requested, see docs/TRACKING-PLAN.md R11
 * @param lockedTrackId  the track {@code FOLLOW} is currently holding; {@code 0} = none
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FrameTrackingResponse(boolean detectorRan, String detectorReason, double trackerMillis,
                                     String engineId, long lockedTrackId) {

    private static final double NANOS_PER_MILLI = 1_000_000.0;

    /**
     * Maps a domain {@link TrackingTelemetry} to its wire representation.
     *
     * @param telemetry the per-frame tracking facts to map; never {@code null}
     * @return the nested {@code "tracking"} object
     */
    public static FrameTrackingResponse from(TrackingTelemetry telemetry) {
        return new FrameTrackingResponse(telemetry.detectorRan(),
                telemetry.detectorRan() ? telemetry.reason().name() : null,
                telemetry.trackerLatency().toNanos() / NANOS_PER_MILLI, telemetry.engineId(),
                telemetry.lockedTrackId());
    }
}
