package com.drones.vision.api.dto;

import com.drones.vision.application.map.LayerView;
import com.drones.vision.map.domain.model.AccessLevel;
import com.drones.vision.map.domain.model.MapLayer;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * A map layer as the caller sees it (docs/plans/done/MAP-REWORK-PLAN.md §4.2) — the layer itself, the caller's
 * own effective access level, and how much is on it.
 *
 * <p><strong>{@code grants} is {@code null} unless {@code myAccess == MANAGE}</strong> (and always
 * {@code null} over SSE — see {@link #forEvent}). Who else can see a layer is itself privileged
 * information; only a manager of the layer gets the access list back. {@code @JsonInclude(NON_NULL)}
 * omits the field entirely rather than sending {@code "grants": null}.
 *
 * @param markCount    {@code ACTIVE} marks on this layer that the caller can see — matching what
 *                     {@code GET /api/map/marks} returns for them; a {@code CLEARED} mark is not
 *                     counted, exactly as it is not listed
 * @param drawingCount drawings on this layer that the caller can see
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LayerResponse(String layerId, String name, String kind, String ownerUserId, String groupId,
                             String myAccess, List<GrantDto> grants, int markCount, int drawingCount,
                             Instant createdAt) {

    /**
     * Maps a visible layer for {@code GET /api/map/layers} and every layer-returning mutation.
     *
     * @param view         the layer plus the caller's own access level
     * @param markCount    how many visible {@code ACTIVE} marks sit on it
     * @param drawingCount how many visible drawings sit on it
     * @return the response body
     */
    public static LayerResponse from(LayerView view, int markCount, int drawingCount) {
        MapLayer layer = view.layer();
        boolean manages = view.myAccess() == AccessLevel.MANAGE;
        return new LayerResponse(
                layer.id().value().toString(),
                layer.name(),
                layer.kind().name(),
                layer.ownership().ownerId().value().toString(),
                layer.ownership().groupId().value().toString(),
                view.myAccess().name(),
                manages ? layer.grants().stream().map(GrantDto::from).toList() : null,
                markCount,
                drawingCount,
                layer.createdAt());
    }

    /**
     * The SSE form (docs/plans/done/MAP-REWORK-PLAN.md §4.3): a layer carried inside a {@code map} event.
     *
     * <p>Two fields are deliberately not what a REST read would carry, because the broadcast side
     * has neither piece of information available per recipient:
     * <ul>
     *   <li>{@code grants} is always {@code null} — the plan freezes this ("layer events over SSE
     *       never include grants"): one broadcast envelope reaches many connections at different
     *       access levels, so there is no single correct answer, and the safe one is to send none.</li>
     *   <li>{@code myAccess} is {@code null} and {@code markCount}/{@code drawingCount} are {@code 0}
     *       — all three are per-viewer facts. A client treats a layer event as "refetch {@code GET
     *       /api/map/layers}", not as a complete replacement row.</li>
     * </ul>
     *
     * @param layer the layer the event is about
     * @return the SSE-safe response body
     */
    public static LayerResponse forEvent(MapLayer layer) {
        return new LayerResponse(
                layer.id().value().toString(),
                layer.name(),
                layer.kind().name(),
                layer.ownership().ownerId().value().toString(),
                layer.ownership().groupId().value().toString(),
                null,
                null,
                0,
                0,
                layer.createdAt());
    }
}
