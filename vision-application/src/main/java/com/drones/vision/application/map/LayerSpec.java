package com.drones.vision.application.map;

import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.LayerKind;
import com.drones.vision.domain.model.MapLayer;

/**
 * Everything needed to create a {@link LayerKind#TEAM} or {@link LayerKind#PERSONAL} {@link
 * MapLayer} (docs/MAP-REWORK-PLAN.md §3) — the single {@link LayerKind#COP} layer is never created
 * through this record; see {@link MapLayerService#copLayerId()}.
 *
 * <p>Duplicates {@link MapLayer}'s own name-blank/length invariants so a malformed request fails
 * fast with a spec-specific message before touching the repository, mirroring {@code
 * GeofenceZoneSpec}'s own duplication of {@code GeofenceZone}'s invariants; also rejects {@link
 * LayerKind#COP} and a missing {@code groupId} on a {@link LayerKind#TEAM} spec here, since both are
 * genuine shape problems with the spec itself, not authorization decisions {@code
 * DefaultMapLayerService} needs a {@link MapAccessPolicy.Viewer} to make.
 *
 * @param name    human-readable layer name; must not be blank, at most {@link
 *                MapLayer#MAX_NAME_LENGTH} chars
 * @param kind    {@link LayerKind#TEAM} or {@link LayerKind#PERSONAL}
 * @param groupId required (non-null) when {@code kind} is {@link LayerKind#TEAM} — the team this
 *                layer belongs to; optional for {@link LayerKind#PERSONAL} (a well-formed fallback
 *                is derived from the acting viewer when omitted, see {@link
 *                LayerResolver#homeGroupOf})
 */
public record LayerSpec(String name, LayerKind kind, GroupId groupId) {

    public LayerSpec {
        if (kind == null) {
            throw new IllegalArgumentException("LayerSpec kind must not be null");
        }
        if (kind == LayerKind.COP) {
            throw new IllegalArgumentException(
                    "LayerSpec kind must be TEAM or PERSONAL; the COP layer is created via MapLayerService#copLayerId");
        }
        if (kind == LayerKind.TEAM && groupId == null) {
            throw new IllegalArgumentException("LayerSpec groupId is required when kind is TEAM");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("LayerSpec name must not be blank");
        }
        if (name.length() > MapLayer.MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "LayerSpec name must be at most " + MapLayer.MAX_NAME_LENGTH + " characters: " + name.length());
        }
    }
}
