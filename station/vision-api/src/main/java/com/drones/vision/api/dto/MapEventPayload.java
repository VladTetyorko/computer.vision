package com.drones.vision.api.dto;

import com.drones.vision.map.domain.model.Drawing;
import com.drones.vision.map.domain.model.MapEvent;
import com.drones.vision.map.domain.model.MapLayer;
import com.drones.vision.map.domain.model.Mark;
import com.drones.vision.map.domain.model.ProjectedTrack;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Locale;

/**
 * The payload of one envelope on the {@code map} SSE topic (docs/plans/done/MAP-REWORK-PLAN.md §4.3) —
 * replacing the {@code marks}-topic {@code MarkPayload} it supersedes, which carried marks only.
 * {@code track} (docs/plans/active/FIXED-CAMERA-GEO-PLAN.md §5/D11) rides the same topic, not a new
 * one — the existing per-connection {@code canView(layerId)} scoping in {@code
 * LiveUpdateRegistry#publishMapEvent} applies to a {@code TRACK} event exactly as it already does to
 * {@code MARK}/{@code DRAWING}/{@code LAYER}, so nothing there needed to change for this wave.
 *
 * <p>Exactly one of {@link #mark()}/{@link #drawing()}/{@link #layer()}/{@link #track()} is
 * non-null, selected by {@link #entity()}; {@code @JsonInclude(NON_NULL)} means the other three are
 * absent from the JSON rather than {@code null}. One topic carries all four entity types (and every
 * action) as fields rather than splitting into more topics, exactly as {@code detection-events}
 * carries OPEN/CLOSED in one topic — the codebase's own 1:1 {@code type}↔topic rule would otherwise
 * force a pile of near-duplicate topics for one concept.
 *
 * <p><strong>{@code layerId} is load-bearing, not decorative.</strong> It is what {@code
 * LiveUpdateRegistry} reads back off a buffered envelope to decide, per connection, whether that
 * viewer may see the event at all (§4.3's scoped delivery) — which is why it is a field on the
 * payload rather than only implied by the nested entity.
 *
 * @param entity  {@code "mark"}, {@code "drawing"}, {@code "layer"} or {@code "track"}
 * @param action  {@code "created"}, {@code "updated"}, {@code "cleared"} or {@code "deleted"}
 * @param layerId the layer the event belongs to, as a canonical UUID string
 * @param track   present only when {@code entity} is {@code "track"} — {@link
 *                ProjectedTrackResponse#live} for {@code created}/{@code updated}, {@link
 *                ProjectedTrackResponse#cleared} (assetId/trackId only) for {@code cleared}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MapEventPayload(String entity, String action, String layerId, MarkResponse mark,
                               DrawingResponse drawing, LayerResponse layer, ProjectedTrackResponse track) {

    /**
     * Maps a domain {@link MapEvent} to its wire payload.
     *
     * <p>A {@code LAYER} event's {@link LayerResponse} is built with {@link LayerResponse#forEvent}
     * — grants stripped, per-viewer fields zeroed — because one broadcast envelope reaches
     * connections at different access levels and there is no single correct answer for them.
     *
     * <p>A {@code TRACK} event's payload is always a {@link ProjectedTrack} regardless of {@code
     * action} (see {@code MapEvent}'s own compact-ctor invariant) — {@code CLEARED} maps it through
     * {@link ProjectedTrackResponse#cleared} (§5's stripped assetId/trackId-only shape) rather than
     * {@link ProjectedTrackResponse#live}, since a cleared track's last-known position is not part
     * of the wire contract.
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
                    MarkResponse.from((Mark) event.payload()), null, null, null);
            case DRAWING -> new MapEventPayload(entity, action, layerId,
                    null, DrawingResponse.from((Drawing) event.payload()), null, null);
            case LAYER -> new MapEventPayload(entity, action, layerId,
                    null, null, LayerResponse.forEvent((MapLayer) event.payload()), null);
            case TRACK -> {
                ProjectedTrack track = (ProjectedTrack) event.payload();
                ProjectedTrackResponse trackResponse = event.action() == MapEvent.Action.CLEARED
                        ? ProjectedTrackResponse.cleared(track.assetId(), track.trackId())
                        : ProjectedTrackResponse.live(track);
                yield new MapEventPayload(entity, action, layerId, null, null, null, trackResponse);
            }
        };
    }
}
