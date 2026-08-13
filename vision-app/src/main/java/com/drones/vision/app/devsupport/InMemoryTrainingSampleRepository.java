package com.drones.vision.app.devsupport;

import com.drones.vision.learning.domain.model.DatasetId;
import com.drones.vision.learning.domain.model.SampleStatus;
import com.drones.vision.learning.domain.model.TrainingSample;
import com.drones.vision.learning.domain.model.TrainingSampleId;
import com.drones.vision.learning.domain.port.TrainingSampleRepositoryPort;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * In-memory {@link TrainingSampleRepositoryPort}: dev fallback with no durability across
 * restarts (docs/plans/done/CV-TRAINING-PLAN.md §1, Wave T3).
 *
 * <p>{@link #findByDataset} orders newest-captured-first before bounding to {@code limit} — the
 * same concrete, deterministic order {@code JpaTrainingSampleRepository} (adapter-persistence)
 * picks for what the port's javadoc otherwise leaves implementation-defined, so the two
 * implementations stay behavior-compatible.
 *
 * <p>Replaced by {@code adapter-persistence}'s {@code JpaTrainingSampleRepository} when {@code
 * vision.persistence.enabled=true}.
 */
public final class InMemoryTrainingSampleRepository implements TrainingSampleRepositoryPort {

    private final Map<TrainingSampleId, TrainingSample> samples = new ConcurrentHashMap<>();

    @Override
    public TrainingSample save(TrainingSample sample) {
        samples.put(sample.id(), sample);
        return sample;
    }

    @Override
    public Optional<TrainingSample> findById(TrainingSampleId id) {
        return Optional.ofNullable(samples.get(id));
    }

    @Override
    public List<TrainingSample> findByDataset(DatasetId datasetId, SampleStatus statusOrNull, int limit) {
        return matching(datasetId, statusOrNull)
                .sorted(Comparator.comparing(TrainingSample::capturedAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public int countByDataset(DatasetId datasetId, SampleStatus statusOrNull) {
        return (int) matching(datasetId, statusOrNull).count();
    }

    @Override
    public void delete(TrainingSampleId id) {
        samples.remove(id);
    }

    private Stream<TrainingSample> matching(DatasetId datasetId, SampleStatus statusOrNull) {
        return samples.values().stream()
                .filter(sample -> sample.datasetId().equals(datasetId))
                .filter(sample -> statusOrNull == null || sample.status() == statusOrNull);
    }
}
