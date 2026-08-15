package com.drones.vision.app.devsupport;

import com.drones.vision.learning.domain.model.SampleImage;
import com.drones.vision.learning.domain.model.TrainingSampleId;
import com.drones.vision.learning.domain.port.SampleImageStorePort;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link SampleImageStorePort}: dev fallback with no durability across restarts
 * (docs/plans/done/CV-TRAINING-PLAN.md §1/§C, Wave T3).
 *
 * <p>Replaced by {@code adapter-persistence}'s {@code JpaSampleImageStore} when {@code
 * vision.persistence.enabled=true}.
 */
public final class InMemorySampleImageStore implements SampleImageStorePort {

    private final Map<TrainingSampleId, SampleImage> images = new ConcurrentHashMap<>();

    @Override
    public void save(TrainingSampleId id, SampleImage image) {
        images.put(id, image);
    }

    @Override
    public Optional<SampleImage> findById(TrainingSampleId id) {
        return Optional.ofNullable(images.get(id));
    }

    @Override
    public void delete(TrainingSampleId id) {
        images.remove(id);
    }
}
