package com.drones.vision.api.dto;

import com.drones.vision.perception.domain.model.TrackingTelemetry;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The nested {@code "tracking"} object on {@link DetectionResultResponse} (docs/plans/done/TRACKING-PLAN.md
 * &sect;4.G) — one frame's duty-cycle facts, so a client can render a tracker-only frame honestly
 * instead of pretending the detector ran.
 *
 * <p>{@code @JsonInclude(NON_NULL)} covers two fields. <b>{@code detectorReason} is present iff
 * {@code detectorRan} is {@code true}</b> (&sect;4.G — it answers <i>why</i> the pass ran, so it has
 * no meaning on a frame where none did; {@link TrackingTelemetry} enforces the same pairing
 * domain-side). "What was the last reason" is a different question, answered by {@code
 * stats.lastDetectorReason} on {@code GET /api/streams/{streamId}/tracks}, which is a window over
 * frames rather than a fact about this one. <b>{@code capability} is present only when cv-service
 * actually reported a served level</b> (docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md &sect;2,
 * invariant B3) — absent means the responding cv-service predates the capability ladder, the same
 * "absent means absent" shape {@link TrackingTelemetry#capability()} already documents domain-side.
 *
 * <p>{@code trackerMillis} is a fractional millisecond count on purpose — the per-frame tracker cost
 * this whole design exists to make small is ~0.4 ms (docs/plans/done/TRACKING-PLAN.md &sect;8), and a {@code
 * Duration#toMillis()} would report every one of them as {@code 0}. {@code detectionLagMillis}/{@code
 * reupdateMillis} stay whole milliseconds instead — the wire itself carries them as {@code int64}
 * milliseconds (proto {@code detection_lag_millis}/{@code reupdate_millis}), so there is no
 * sub-millisecond precision to lose by rounding.
 *
 * <p><b>{@code capability.levelServed} is what happened, never what was requested</b> (invariant
 * B5). The stream's own {@code TrackingConfig#capabilityLevel()}/the request's {@code
 * TrackingConfigRequest#capabilityLevel()} are a <i>ceiling</i> — cv-service may silently serve
 * less, and this field is the only place that says how much less. Never substitute the request for
 * this field, and never let a UI reader confuse "what I asked for" with "what I got" — see {@link
 * TrackingCapabilityResponse}'s own javadoc.
 *
 * @param detectorRan        {@code false} means this frame was tracker-only — the duty cycle's whole point
 * @param detectorReason     why the detector pass ran ({@code ALWAYS}/{@code CADENCE}/{@code
 *                           TRACKER_FAILED}/{@code NO_LOCK}/{@code BOX_INVALID}/{@code COASTED_OUT}),
 *                           absent when none did
 * @param trackerMillis      the per-frame tracker cost in milliseconds, fractional; {@code 0} when no
 *                           tracker ran on this frame
 * @param engineId           the engine that actually served this frame ({@code ""} = none) — not
 *                           necessarily the one requested, see docs/plans/done/TRACKING-PLAN.md R11
 * @param lockedTrackId      the track {@code FOLLOW} is currently holding; {@code 0} = none
 * @param detectionLagMillis measured capture&rarr;association lag in milliseconds; {@code 0} =
 *                           unknown
 * @param reupdateMillis     ORU (Observation-Centric Re-Update) cost this frame in milliseconds;
 *                           {@code 0} = none ran
 * @param reupdatedTracks    tracks backfilled by ORU this frame; {@code 0} = none
 * @param capability         what the capability ladder actually served this frame — see the class
 *                           javadoc — or absent when the responding server reported no level at all
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FrameTrackingResponse(boolean detectorRan, String detectorReason, double trackerMillis,
                                     String engineId, long lockedTrackId, long detectionLagMillis,
                                     long reupdateMillis, int reupdatedTracks,
                                     TrackingCapabilityResponse capability) {

    private static final double NANOS_PER_MILLI = 1_000_000.0;

    /**
     * Convenience constructor for callers before docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md added
     * the four V3 fields — defaults {@link #detectionLagMillis()}/{@link #reupdateMillis()} to
     * {@code 0}, {@link #reupdatedTracks()} to {@code 0}, and {@link #capability()} to {@code null}
     * (absent on the wire), the same idiom {@link TrackingTelemetry}'s own convenience constructor
     * uses. Every pre-existing 5-arg call site compiles and behaves unchanged.
     */
    public FrameTrackingResponse(boolean detectorRan, String detectorReason, double trackerMillis, String engineId,
                                  long lockedTrackId) {
        this(detectorRan, detectorReason, trackerMillis, engineId, lockedTrackId, 0L, 0L, 0, null);
    }

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
                telemetry.lockedTrackId(), telemetry.detectionLag().toMillis(),
                telemetry.reupdateLatency().toMillis(), telemetry.reupdatedTracks(),
                telemetry.capability() == null ? null : TrackingCapabilityResponse.from(telemetry.capability()));
    }
}
