package com.drones.vision.api.dto;

import com.drones.vision.platform.Health;
import com.drones.vision.platform.SubsystemStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * One subsystem's entry in {@code GET /api/system/status} (docs/plans/done/SYSTEM-STATUS-PLAN.md
 * §4.3's frozen wire contract) — mirrors {@link SubsystemStatus} field-for-field, plus {@code
 * @JsonInclude(NON_NULL)} for {@code since}/{@code hint}, which are genuinely absent (not merely
 * blank) when unknown/not applicable.
 *
 * @param id     stable machine identifier, e.g. {@code "cv-service"}
 * @param label  short human-readable name, e.g. {@code "CV inference"}
 * @param health current health, on the wire as {@link Health}'s enum name
 * @param detail one operator-facing sentence explaining {@code health}
 * @param since  when the current {@code health} value started, if known
 * @param hint   an optional operator action; absent when there is nothing actionable to say
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubsystemStatusResponse(String id, String label, Health health, String detail, Instant since,
                                       String hint) {

    /**
     * Maps a domain {@link SubsystemStatus} to its wire representation.
     *
     * @param status the resolved status
     * @return the response body
     */
    public static SubsystemStatusResponse from(SubsystemStatus status) {
        return new SubsystemStatusResponse(status.id(), status.label(), status.health(), status.detail(),
                status.since(), status.hint());
    }
}
