package com.drones.vision.app.devsupport;

import com.drones.vision.learning.domain.model.Dataset;
import com.drones.vision.learning.domain.model.DatasetId;
import com.drones.vision.learning.domain.port.DatasetRepositoryPort;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link DatasetRepositoryPort}: dev fallback with no durability across restarts
 * (docs/plans/done/CV-TRAINING-PLAN.md §1, Wave T3).
 *
 * <p>Replaced by {@code adapter-persistence}'s {@code JpaDatasetRepository} when {@code
 * vision.persistence.enabled=true}.
 */
public final class InMemoryDatasetRepository implements DatasetRepositoryPort {

    private final Map<DatasetId, Dataset> datasets = new ConcurrentHashMap<>();

    @Override
    public Dataset save(Dataset dataset) {
        datasets.put(dataset.id(), dataset);
        return dataset;
    }

    @Override
    public Optional<Dataset> findById(DatasetId id) {
        return Optional.ofNullable(datasets.get(id));
    }

    @Override
    public List<Dataset> findAll() {
        return List.copyOf(datasets.values());
    }

    @Override
    public void delete(DatasetId id) {
        datasets.remove(id);
    }
}
