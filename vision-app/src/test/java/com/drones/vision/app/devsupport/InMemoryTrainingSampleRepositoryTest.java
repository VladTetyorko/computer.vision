package com.drones.vision.app.devsupport;

import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.Annotation;
import com.drones.vision.domain.model.AnnotationSource;
import com.drones.vision.domain.model.BoundingBox;
import com.drones.vision.domain.model.DatasetId;
import com.drones.vision.domain.model.SampleStatus;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.TrainingSample;
import com.drones.vision.domain.model.TrainingSampleId;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.TrainingSampleRepositoryPort;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link TrainingSampleRepositoryPort} contract against the in-memory reference
 * implementation (docs/CV-TRAINING-PLAN.md §1, Wave T3) — the same contract {@code
 * JpaTrainingSampleRepository} is judged against in {@code adapter-persistence}'s Postgres tests.
 */
class InMemoryTrainingSampleRepositoryTest {

    private final TrainingSampleRepositoryPort repository = new InMemoryTrainingSampleRepository();

    private static TrainingSample sample(TrainingSampleId id, DatasetId datasetId, SampleStatus status) {
        List<Annotation> annotations = List.of(
                new Annotation("building", new BoundingBox(0.1, 0.2, 0.3, 0.25), AnnotationSource.MODEL));
        return new TrainingSample(id, datasetId, StreamId.random(), AssetId.random(), Instant.now(), 1920,
                1080, annotations, status, null, null);
    }

    @Test
    void unknownIdReturnsEmptyOptional() {
        assertTrue(repository.findById(TrainingSampleId.random()).isEmpty());
    }

    @Test
    void saveThenFindByIdRoundTrips() {
        TrainingSample sample = sample(TrainingSampleId.random(), DatasetId.random(), SampleStatus.PENDING);

        repository.save(sample);

        Optional<TrainingSample> found = repository.findById(sample.id());
        assertTrue(found.isPresent());
        assertEquals(sample, found.get());
    }

    @Test
    void saveIsAnUpsertMovingPendingToLabeled() {
        TrainingSampleId id = TrainingSampleId.random();
        DatasetId datasetId = DatasetId.random();
        repository.save(sample(id, datasetId, SampleStatus.PENDING));

        UserId reviewer = UserId.random();
        Instant now = Instant.now();
        TrainingSample labeled = new TrainingSample(id, datasetId, StreamId.random(), AssetId.random(), now,
                1920, 1080, List.of(), SampleStatus.LABELED, reviewer, now);
        repository.save(labeled);

        Optional<TrainingSample> found = repository.findById(id);
        assertTrue(found.isPresent());
        assertEquals(SampleStatus.LABELED, found.get().status());
        assertEquals(reviewer, found.get().labeledBy());
    }

    @Test
    void findByDatasetFiltersByStatusAndBoundsByLimit() {
        DatasetId datasetId = DatasetId.random();
        repository.save(sample(TrainingSampleId.random(), datasetId, SampleStatus.PENDING));
        repository.save(sample(TrainingSampleId.random(), datasetId, SampleStatus.LABELED));
        repository.save(sample(TrainingSampleId.random(), datasetId, SampleStatus.LABELED));
        repository.save(sample(TrainingSampleId.random(), DatasetId.random(), SampleStatus.LABELED));

        List<TrainingSample> labeled = repository.findByDataset(datasetId, SampleStatus.LABELED, 10);
        assertEquals(2, labeled.size());

        List<TrainingSample> everyStatus = repository.findByDataset(datasetId, null, 10);
        assertEquals(3, everyStatus.size());

        List<TrainingSample> bounded = repository.findByDataset(datasetId, null, 1);
        assertEquals(1, bounded.size());
    }

    @Test
    void countByDatasetMatchesFindByDatasetsFilter() {
        DatasetId datasetId = DatasetId.random();
        repository.save(sample(TrainingSampleId.random(), datasetId, SampleStatus.PENDING));
        repository.save(sample(TrainingSampleId.random(), datasetId, SampleStatus.LABELED));

        assertEquals(2, repository.countByDataset(datasetId, null));
        assertEquals(1, repository.countByDataset(datasetId, SampleStatus.PENDING));
    }

    @Test
    void findByDatasetAndCountByDatasetOnAnUnknownDatasetAreEmpty() {
        assertTrue(repository.findByDataset(DatasetId.random(), null, 10).isEmpty());
        assertEquals(0, repository.countByDataset(DatasetId.random(), null));
    }

    @Test
    void deleteIsIdempotentAndRemovesTheSample() {
        TrainingSample sample = sample(TrainingSampleId.random(), DatasetId.random(), SampleStatus.PENDING);
        repository.save(sample);

        repository.delete(sample.id());
        assertTrue(repository.findById(sample.id()).isEmpty());

        // second call on an already-absent id must not throw
        repository.delete(sample.id());
    }
}
