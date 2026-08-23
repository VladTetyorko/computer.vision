package com.drones.vision.platform;

import java.time.Instant;

/**
 * One subsystem's current status, as reported by a {@link SubsystemStatusPort} and served verbatim
 * (mapped 1:1 onto JSON) by {@code SystemStatusController}'s {@code GET /api/system/status}
 * (docs/plans/done/SYSTEM-STATUS-PLAN.md §4.1/§4.3).
 *
 * @param id     stable machine identifier, e.g. {@code "cv-service"} — used as a UI/API key, never
 *               shown to an operator directly
 * @param label  short human-readable name, e.g. {@code "CV inference"}
 * @param health current {@link Health}
 * @param detail one operator-facing sentence explaining {@code health} — what is actually
 *               happening, not a code; must not be blank
 * @param since  when the current {@code health} value started, if known; {@code null} when not
 *               tracked (e.g. a provider that only has a point-in-time read)
 * @param hint   an optional operator action, e.g. {@code "Check cv-service is running"}; {@code
 *               null} when {@code health} is {@link Health#OK} or there is nothing actionable to say
 */
public record SubsystemStatus(String id, String label, Health health, String detail, Instant since, String hint) {

    public SubsystemStatus {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("SubsystemStatus id must not be blank");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("SubsystemStatus label must not be blank");
        }
        if (health == null) {
            throw new IllegalArgumentException("SubsystemStatus health must not be null");
        }
        if (detail == null || detail.isBlank()) {
            throw new IllegalArgumentException("SubsystemStatus detail must not be blank");
        }
    }
}
