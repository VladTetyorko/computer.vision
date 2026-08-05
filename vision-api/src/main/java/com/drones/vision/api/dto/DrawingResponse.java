package com.drones.vision.api.dto;

import com.drones.vision.domain.model.Drawing;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * A line/polygon/arrow/text annotation on the wire (docs/MAP-REWORK-PLAN.md §4.2) — the response
 * body for every {@code /api/map/drawings} endpoint and the {@code drawing} field inside {@link
 * MapEventPayload} on the {@code map} SSE topic.
 *
 * @param kind       {@code LINE}/{@code POLYGON}/{@code ARROW}/{@code TEXT}
 * @param label      free text; required for {@code TEXT}, optional (and absent) otherwise
 * @param colorToken a design-system token name ({@code "accent"}, {@code "danger"}), never a hex
 *                   value — the client resolves it against the current theme; absent if unset
 * @param points     the geometry, in draw order
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DrawingResponse(String drawingId, String layerId, String kind, String label, String colorToken,
                               List<PositionDto> points, String createdByUserId, Instant createdAt) {

    /**
     * @param drawing the drawing to map
     * @return its wire form
     */
    public static DrawingResponse from(Drawing drawing) {
        return new DrawingResponse(
                drawing.id().value().toString(),
                drawing.layerId().value().toString(),
                drawing.kind().name(),
                drawing.label(),
                drawing.colorToken(),
                drawing.points().stream().map(PositionDto::from).toList(),
                drawing.ownership().ownerId().value().toString(),
                drawing.createdAt());
    }
}
