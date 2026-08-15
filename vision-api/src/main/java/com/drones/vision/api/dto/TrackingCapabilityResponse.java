package com.drones.vision.api.dto;

import com.drones.vision.perception.domain.model.TrackingCapability;

/**
 * The nested {@code "capability"} object on {@link FrameTrackingResponse}
 * (docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md &sect;2) — what the capability ladder <b>actually
 * served</b> on this frame, as opposed to {@link TrackingConfigRequest#capabilityLevel()}, which is
 * only ever a request's ceiling.
 *
 * <p>Absent (never a fabricated zero-level instance) whenever {@link
 * com.drones.vision.perception.domain.model.TrackingTelemetry#capability()} is {@code null} — a
 * pre-V3 cv-service reported no level at all — via {@link FrameTrackingResponse}'s own {@code
 * @JsonInclude(NON_NULL)}.
 *
 * <p><b>Render this, never the request, as what happened</b> (invariant B5): {@code levelServed} can
 * only ever be less than or equal to what was requested, and a client that shows a request's
 * {@code capabilityLevel} as though it were this field is showing a demand that was never honored as
 * a fact.
 *
 * @param levelServed the capability level that actually ran on this frame, within [1,5] — never the
 *                     ladder's own {@code 0} ("auto-probe") sentinel, since this object only exists
 *                     once a level was genuinely reported
 * @param reason       why the served level was capped below what was requested; never {@code null};
 *                     {@code ""} means "served exactly as requested"
 */
public record TrackingCapabilityResponse(int levelServed, String reason) {

    /**
     * Maps a domain {@link TrackingCapability} to its wire representation.
     *
     * @param capability the capability facts to map; never {@code null}
     * @return the nested {@code "capability"} object
     */
    public static TrackingCapabilityResponse from(TrackingCapability capability) {
        return new TrackingCapabilityResponse(capability.levelServed(), capability.reason());
    }
}
