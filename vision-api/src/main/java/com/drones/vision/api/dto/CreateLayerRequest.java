package com.drones.vision.api.dto;

import com.drones.vision.api.support.EnumParsing;
import com.drones.vision.application.map.LayerSpec;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.LayerKind;

/**
 * Body of {@code POST /api/map/layers} (docs/plans/done/MAP-REWORK-PLAN.md §4.2).
 *
 * @param name    the layer's name, non-blank, at most {@code MapLayer.MAX_NAME_LENGTH}
 * @param kind    {@code "TEAM"} or {@code "PERSONAL"}; {@code "COP"} is rejected — the single COP
 *                layer is infrastructure, created by {@code MapLayerService#copLayerId()}, never by
 *                a caller
 * @param groupId the owning group, required for {@code TEAM}, ignored for {@code PERSONAL}
 */
public record CreateLayerRequest(String name, String kind, String groupId) {

    /**
     * @return the application-layer command
     * @throws IllegalArgumentException on an unrecognized {@code kind}, a malformed {@code groupId},
     *                                   a blank/over-long {@code name}, or a {@code TEAM} layer with
     *                                   no group (→ 400; the last three come from {@link LayerSpec}'s
     *                                   own compact constructor)
     */
    public LayerSpec toSpec() {
        LayerKind parsed = EnumParsing.require(LayerKind.class, "kind", kind);
        return new LayerSpec(name, parsed, groupId == null || groupId.isBlank() ? null : GroupId.of(groupId));
    }
}
