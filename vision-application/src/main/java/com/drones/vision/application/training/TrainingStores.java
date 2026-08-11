package com.drones.vision.application.training;

import com.drones.vision.domain.port.out.DatasetRepositoryPort;
import com.drones.vision.domain.port.out.DatasetUploadPort;
import com.drones.vision.domain.port.out.SampleImageStorePort;
import com.drones.vision.domain.port.out.TrainingSampleRepositoryPort;

import java.util.Objects;

/**
 * The four Wave-T1 CV-training ports (docs/plans/done/CV-TRAINING-PLAN.md §1) — {@code exports} replaced by
 * {@link DatasetUploadPort} (docs/plans/done/CV-TRAINING-V2-PLAN.md §3/§4: delivery to the training host now
 * rides a gRPC upload instead of a filesystem export) — bundled into one constructor parameter for
 * {@link DefaultLabelingService} (java-clean-code SKILL.md §3 — "bundle collaborators rather than
 * sprawl"): each port is a genuine, independently-substitutable dependency in its own right (a JPA
 * implementation and an in-memory fallback both exist per repository port, and {@link
 * DatasetUploadPort} has its own real gRPC implementation), so none of the four is speculative —
 * but {@link DefaultLabelingService} also needs {@link com.drones.vision.application.stream.StreamService}, {@link
 * com.drones.vision.domain.port.out.AssetRepositoryPort}, {@link com.drones.vision.application.replay.ReplaySources}, and {@link
 * com.drones.vision.domain.port.out.AuditTrailPort}, which would push its constructor past the
 * five-parameter ceiling if all seven were listed individually. Grouping the four that land
 * together, as one T1 wave, keeps every one of them a real (not speculative) collaborator while
 * keeping the constructor small.
 *
 * <p>{@link DefaultDatasetService} does not need this bundle — it only ever touches {@link
 * #datasets()}, so it takes a plain {@link DatasetRepositoryPort} directly.
 *
 * @param datasets dataset CRUD
 * @param samples  training-sample CRUD/query
 * @param images   sample image byte storage
 * @param uploads  YOLO dataset upload sink (the training-host delivery transport)
 */
public record TrainingStores(DatasetRepositoryPort datasets, TrainingSampleRepositoryPort samples,
                              SampleImageStorePort images, DatasetUploadPort uploads) {

    public TrainingStores {
        Objects.requireNonNull(datasets, "datasets must not be null");
        Objects.requireNonNull(samples, "samples must not be null");
        Objects.requireNonNull(images, "images must not be null");
        Objects.requireNonNull(uploads, "uploads must not be null");
    }
}
