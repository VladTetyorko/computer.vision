package com.drones.vision.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * A named accumulation of labeled frames aimed at improving detection of one target
 * (docs/plans/done/CV-TRAINING-PLAN.md §1) — the unit an operator captures samples into and eventually
 * exports as a YOLO dataset.
 *
 * <p>{@code classes} is the <b>ordered</b> YOLO class list: an annotation's class index on export
 * is its label's position in this list (docs/plans/done/CV-TRAINING-PLAN.md §5). It is free-form strings, not
 * {@link CategoryId} slugs — open-vocab detection labels aren't categories (see {@link
 * Annotation}'s own javadoc) — and is defensively copied, preserving order. Every {@link
 * TrainingSample}'s {@link Annotation#label()} must be a member of its dataset's {@code classes},
 * but that membership check is an application-layer concern ({@code LabelingService.label}), not
 * this record's.
 *
 * <p>{@code ownership} scopes a dataset like every other owned platform datum (mirrors {@link
 * Asset#ownership()}); scope filtering over a repository's full dataset snapshot is the
 * application layer's job, the same "no scope filtering baked into the repository" convention
 * every port in this module follows.
 *
 * @param id             typed dataset identity
 * @param name           human-readable name; must not be blank
 * @param targetCategory the {@link CategoryId} this dataset aims to improve detection of (e.g.
 *                       {@code "building"}), or {@code null} if not tied to one category
 * @param classes        the ordered YOLO class list; defensively copied, order preserved
 * @param ownership      who owns this dataset and which group it belongs to
 * @param status         this dataset's lifecycle state
 * @param createdAt      when this dataset was created
 */
public record Dataset(DatasetId id, String name, CategoryId targetCategory, List<String> classes,
                       Ownership ownership, DatasetStatus status, Instant createdAt) {

    public Dataset {
        if (id == null) {
            throw new IllegalArgumentException("Dataset id must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Dataset name must not be blank");
        }
        if (classes == null) {
            throw new IllegalArgumentException("Dataset classes must not be null");
        }
        if (ownership == null) {
            throw new IllegalArgumentException("Dataset ownership must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("Dataset status must not be null");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("Dataset createdAt must not be null");
        }
        classes = List.copyOf(classes);
    }
}
