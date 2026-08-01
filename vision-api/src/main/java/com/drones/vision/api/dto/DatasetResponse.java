package com.drones.vision.api.dto;

import com.drones.vision.domain.model.Dataset;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Wire representation of a {@link Dataset} (docs/CV-TRAINING-PLAN.md §3's frozen wire contract).
 *
 * @param id             typed dataset identity, as its canonical UUID string
 * @param name           human-readable name
 * @param targetCategory the target {@link com.drones.vision.domain.model.CategoryId} slug, absent
 *                        if this dataset is not tied to one category
 * @param classes        the ordered YOLO class list
 * @param status         {@code OPEN}/{@code EXPORTING}/{@code ARCHIVED}
 * @param createdAt      when this dataset was created
 * @param sampleCounts   this dataset's sample count broken down by {@code PENDING}/{@code
 *                       LABELED}/{@code DISCARDED} — every key always present, even at zero (not a
 *                       {@link Dataset} field — computed by the controller from {@link
 *                       com.drones.vision.domain.port.out.TrainingSampleRepositoryPort#countByDataset})
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DatasetResponse(String id, String name, String targetCategory, List<String> classes, String status,
                               Instant createdAt, Map<String, Integer> sampleCounts) {

    /**
     * Maps a domain {@link Dataset} plus its precomputed sample counts to its wire representation.
     *
     * @param dataset      the dataset to map
     * @param sampleCounts this dataset's sample counts by status (see {@link #sampleCounts()})
     * @return the response body for {@code dataset}
     */
    public static DatasetResponse from(Dataset dataset, Map<String, Integer> sampleCounts) {
        return new DatasetResponse(dataset.id().value().toString(), dataset.name(),
                dataset.targetCategory() == null ? null : dataset.targetCategory().slug(), dataset.classes(),
                dataset.status().name(), dataset.createdAt(), sampleCounts);
    }
}
