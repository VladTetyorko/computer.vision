package com.drones.vision.domain.model;

/**
 * An immutable, versioned reference to a CV model.
 *
 * <p>Models are hot-swapped by updating a stream's {@link PipelineConfig}
 * to point at a different {@code ModelRef}, and rolled back by switching
 * back to a previous version — the reference itself never changes identity,
 * only which one a pipeline currently points at.
 *
 * @param id      model identifier; must not be blank
 * @param version model version; must not be blank
 */
public record ModelRef(String id, String version) {

    public ModelRef {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ModelRef id must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("ModelRef version must not be blank");
        }
    }
}
