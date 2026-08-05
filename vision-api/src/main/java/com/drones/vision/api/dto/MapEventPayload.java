package com.drones.vision.api.dto;

import com.drones.vision.domain.model.Drawing;
import com.drones.vision.domain.model.MapEvent;
import com.drones.vision.domain.model.MapLayer;
import com.drones.vision.domain.model.Mark;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Locale;

/**
 * The payload of one envelope on the {@code map} SSE topic (docs/MAP-REWORK-PLAN.md §4.3) —
 * replacing the {@code marks}-topic {@code MarkPayload} it supersedes, which carried marks only.
 *
 * <p>Exactly one of {@link #mark()}/{@link #drawing()}/{@link #layer()} is non-null, selected by
 * {@link #entity()}; {@code @JsonInclude(NON_NULL)} means the other two are absent from the JSON
 * rather than {@code null}. One topic carries all three entity types (and all four actions) as
 * fields rather than splitting into six topics, exactly as {@code detection-events} carries
 * OPEN/CLOSED in one topic — the codebase's own 1:1 {@code type}↔topic rule would otherwise force a
 * pile of near-duplicate topics for one concept.
 *
 * <p><strong>{@code layerId} is load-bearing, not decorative.</strong> It is what {@code
 * LiveUpdateRegistry} reads back off a buffered envelope to decide, per connection, whether that
 * viewer may see the event at all (§4.3's scoped delivery) — which is why it is a field on the
 * payload rather than only implied by the nested entity.
 *
 * @param entity  {@code "mark"}, {@code "drawing"} or {@code "layer"}
 * @param action  {@code "created"}, {@code "updated"}, {@code "cleared"} or {@code "deleted"}
 * @param layerId the layer the event belongs to, as a canonical UUID string
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MapEventPayload(String entity, String action, String layerId, MarkResponse mark,
                               DrawingResponse drawing, LayerResponse layer) {

    /**
     * Maps a domain {@link MapEvent} to its wire payload.
     *
     * <p>A {@code LAYER} event's {@link LayerResponse} is built with {@link LayerResponse#forEvent}
     * — grants stripped, per-viewer fields zeroed — because one broadcast envelope reaches
     * connections at different access levels and there is no single correct answer for them.
     *
     * @param event the event to map
     * @return the payload to broadcast
     */
    public static MapEventPayload from(MapEvent event) {
        String entity = event.entity().name().toLowerCase(Locale.ROOT);
        String action = event.action().name().toLowerCase(Locale.ROOT);
        String layerId = event.layerId().value().toString();
        return switch (event.entity()) {
            case MARK -> new MapEventPayload(entity, action, layerId,
                    MarkResponse.from((Mark) event.payload()), null, null);
            case DRAWING -> new MapEventPayload(entity, action, layerId,
                    null, DrawingResponse.from((Drawing) event.payload()), null);
            case LAYER -> new MapEventPayload(entity, action, layerId,
                    null, null, LayerResponse.forEvent((MapLayer) event.payload()));
        };
    }
}
