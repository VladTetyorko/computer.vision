package com.drones.vision.application.mark;

import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.MarkKind;
import com.drones.vision.application.asset.AssetSpec;
import com.drones.vision.application.geofence.GeofenceZoneSpec;

/**
 * Everything needed to create a {@code MANUAL} {@link com.drones.vision.domain.model.Mark} (a map
 * click) — docs/TACTICAL-MARKS-PLAN.md §2.
 *
 * <p>A top-level record rather than a type nested in {@link MarkService}, so callers can name their
 * input without importing the service, and so the wire DTO in {@code …api.dto} maps to one plain
 * value — same reasoning as {@link GeofenceZoneSpec}/{@link AssetSpec}.
 *
 * <p>Duplicates {@link com.drones.vision.domain.model.Mark}'s own {@code label}/{@code note}
 * invariants so a malformed request fails fast with a spec-specific message before touching the
 * repository, mirroring {@link GeofenceZoneSpec}'s own duplication of {@code GeofenceZone}'s
 * invariants.
 *
 * @param kind     the tactical category
 * @param label    short human-readable label; must not be blank
 * @param note     optional free-text detail; blank normalizes to {@code null}
 * @param position where to drop the mark
 */
public record MarkSpec(MarkKind kind, String label, String note, GeoPosition position) {

    public MarkSpec {
        if (kind == null) {
            throw new IllegalArgumentException("MarkSpec kind must not be null");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("MarkSpec label must not be blank");
        }
        if (note != null && note.isBlank()) {
            note = null;
        }
        if (position == null) {
            throw new IllegalArgumentException("MarkSpec position must not be null");
        }
    }
}
