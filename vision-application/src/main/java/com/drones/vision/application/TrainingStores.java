package com.drones.vision.application;

import com.drones.vision.domain.port.out.DatasetExportPort;
import com.drones.vision.domain.port.out.DatasetRepositoryPort;
import com.drones.vision.domain.port.out.SampleImageStorePort;
import com.drones.vision.domain.port.out.TrainingSampleRepositoryPort;

import java.util.Objects;

/**
 * The four Wave-T1 CV-training ports (docs/CV-TRAINING-PLAN.md §1), bundled into one constructor
 * parameter for {@link DefaultLabelingService} (java-clean-code SKILL.md §3 — "bundle
 * collaborators rather than sprawl"): each port is a genuine, independently-substitutable
 * dependency in its own right (a JPA implementation and an in-memory fallback both exist per
 * port), so none of the four is speculative — but {@link DefaultLabelingService} also needs
 * {@link StreamService} and {@link com.drones.vision.domain.port.out.AssetRepositoryPort} to
 * resolve a capture's source stream/asset, which would push its constructor past the
 * five-parameter ceiling if all six were listed individually. Grouping the four that land
 * together, as one T1 wave, keeps every one of them a real (not speculative) collaborator while
 * keeping the constructor small.
 *
 * <p>{@link DefaultDatasetService} does not need this bundle — it only ever touches {@link
 * #datasets()}, so it takes a plain {@link DatasetRepositoryPort} directly.
 *
 * @param datasets dataset CRUD
 * @param samples  training-sample CRUD/query
 * @param images   sample image byte storage
 * @param exports  YOLO dataset export sink
 */
public record TrainingStores(DatasetRepositoryPort datasets, TrainingSampleRepositoryPort samples,
                              SampleImageStorePort images, DatasetExportPort exports) {

    public TrainingStores {
        Objects.requireNonNull(datasets, "datasets must not be null");
        Objects.requireNonNull(samples, "samples must not be null");
        Objects.requireNonNull(images, "images must not be null");
        Objects.requireNonNull(exports, "exports must not be null");
    }
}
