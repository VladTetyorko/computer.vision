package com.drones.vision.api.dto;

import com.drones.vision.flight.domain.model.LinkView;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response body for one link inside a {@link LinkGroupResponse} (LINK-PAIRING-PLAN.md §3.4 frozen
 * contract, {@code LinkView} in {@code station/vision-web}).
 *
 * <p>{@code heartbeatAgeSeconds} is whole seconds, not an ISO-8601 duration string — the frozen web
 * contract's own field name and type (a plain {@code number}), not this module's usual {@code
 * java.time} pass-through.
 *
 * @param id                  this link's stable identity
 * @param carrier             {@code "UDP"} or {@code "SERIAL"} (the enum name)
 * @param serialRole          {@code "NONE"}, {@code "GROUND_RADIO"} or {@code "BENCH"} (the enum name)
 * @param label               a short, human-facing name
 * @param active              {@code true} iff this is the group's current ACTIVE link
 * @param receiving           {@code true} iff this link has heard the vehicle recently
 * @param heartbeatAgeSeconds time since this link last delivered a frame from the vehicle, in whole seconds
 * @param quality             last known radio-quality reading, or absent if none has ever arrived
 * @param deviceId            additive beyond the frozen contract (LINK-PAIRING-PLAN.md §4 row L3
 *                            task brief): the paired telemetry device this link belongs to, so a
 *                            multi-device asset's links can be told apart on the wire too — see
 *                            {@link LinkView#deviceId()}'s own javadoc
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LinkViewResponse(String id, String carrier, String serialRole, String label, boolean active,
                                boolean receiving, long heartbeatAgeSeconds, LinkQualityResponse quality,
                                String deviceId) {

    /**
     * Maps a domain {@link LinkView} to its wire representation.
     *
     * @param link the link to map
     * @return the response body for {@code link}
     */
    public static LinkViewResponse from(LinkView link) {
        return new LinkViewResponse(link.id().value(), link.carrier().name(), link.serialRole().name(),
                link.label(), link.active(), link.receiving(), link.heartbeatAge().getSeconds(),
                LinkQualityResponse.from(link.quality()), link.deviceId().value().toString());
    }
}
