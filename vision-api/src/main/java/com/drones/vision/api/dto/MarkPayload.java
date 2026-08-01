package com.drones.vision.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Payload of the {@code marks} {@code GET /api/live} topic (docs/TACTICAL-MARKS-PLAN.md §5) — the
 * one always-on topic carrying every mark lifecycle event, with the specific lifecycle riding in
 * {@link #action()} rather than as three separate topic kinds (mirrors how {@code
 * detection-events} carries OPEN/CLOSED in one topic instead of two).
 *
 * @param action {@code "created"} (a map-click create or a cockpit geolocate), {@code "updated"}
 *               (an annotation/drag-to-correct with no status change), or {@code "cleared"} (a
 *               status &rarr; {@code CLEARED} transition, or a delete)
 * @param mark   the mark's resulting state; for {@code "cleared"} this is always {@code status:
 *               "CLEARED"}, even when the underlying delete removed a mark that was still {@code
 *               ACTIVE} at the moment it was removed (docs/TACTICAL-MARKS-PLAN.md §5) — so clients
 *               can resolve which pin to drop without a second lookup
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MarkPayload(String action, MarkResponse mark) {
}
