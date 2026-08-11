package com.drones.vision.application.training;

import com.drones.vision.domain.model.CategoryId;

import java.util.List;

/**
 * {@link DatasetService#create}'s command record (docs/plans/done/CV-TRAINING-PLAN.md §2/§3) — the wire
 * shape for {@code POST /api/datasets}.
 *
 * <p>Duplicates {@link com.drones.vision.domain.model.Dataset}'s own blank-name check so a
 * malformed request fails fast before ever reaching the repository, the same reasoning {@code
 * GeofenceZoneSpec}/{@code MarkSpec} already apply to their own domain counterparts.
 *
 * @param name           human-readable name; must not be blank
 * @param targetCategory the {@link CategoryId} this dataset aims to improve detection of, or
 *                       {@code null} if not tied to one category
 * @param classes        the ordered YOLO class list; must not be {@code null} (may be empty — a
 *                       dataset can start with no classes and grow its vocabulary later, though
 *                       nothing in this wave re-opens an existing dataset's classes for edit);
 *                       defensively copied, order preserved
 */
public record DatasetSpec(String name, CategoryId targetCategory, List<String> classes) {

    public DatasetSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("DatasetSpec name must not be blank");
        }
        if (classes == null) {
            throw new IllegalArgumentException("DatasetSpec classes must not be null");
        }
        classes = List.copyOf(classes);
    }
}
