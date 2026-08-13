package com.drones.vision.app.devsupport;

import com.drones.vision.kernel.CategoryId;
import com.drones.vision.learning.domain.model.Dataset;
import com.drones.vision.learning.domain.model.DatasetId;
import com.drones.vision.learning.domain.model.DatasetStatus;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.learning.domain.port.DatasetRepositoryPort;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link DatasetRepositoryPort} contract against the in-memory reference implementation
 * (docs/plans/done/CV-TRAINING-PLAN.md §1, Wave T3) — the same contract {@code JpaDatasetRepository} is
 * judged against in {@code adapter-persistence}'s Postgres tests.
 */
class InMemoryDatasetRepositoryTest {

    private final DatasetRepositoryPort repository = new InMemoryDatasetRepository();

    private static Dataset dataset(DatasetId id, DatasetStatus status) {
        return new Dataset(id, "Buildings", new CategoryId("building"), List.of("building", "tower"),
                new Ownership(UserId.random(), GroupId.random()), status, Instant.now());
    }

    @Test
    void unknownIdReturnsEmptyOptional() {
        assertTrue(repository.findById(DatasetId.random()).isEmpty());
    }

    @Test
    void saveThenFindByIdRoundTrips() {
        Dataset dataset = dataset(DatasetId.random(), DatasetStatus.OPEN);

        repository.save(dataset);

        Optional<Dataset> found = repository.findById(dataset.id());
        assertTrue(found.isPresent());
        assertEquals(dataset, found.get());
    }

    @Test
    void saveIsAnUpsertPreservingId() {
        DatasetId id = DatasetId.random();
        repository.save(dataset(id, DatasetStatus.OPEN));
        Dataset archived = dataset(id, DatasetStatus.ARCHIVED);

        repository.save(archived);

        Optional<Dataset> found = repository.findById(id);
        assertTrue(found.isPresent());
        assertEquals(DatasetStatus.ARCHIVED, found.get().status());
    }

    @Test
    void findAllReturnsEverySavedDataset() {
        Dataset first = dataset(DatasetId.random(), DatasetStatus.OPEN);
        Dataset second = dataset(DatasetId.random(), DatasetStatus.OPEN);
        repository.save(first);
        repository.save(second);

        List<Dataset> all = repository.findAll();
        assertTrue(all.contains(first));
        assertTrue(all.contains(second));
    }

    @Test
    void deleteIsIdempotentAndRemovesTheDataset() {
        Dataset dataset = dataset(DatasetId.random(), DatasetStatus.OPEN);
        repository.save(dataset);

        repository.delete(dataset.id());
        assertTrue(repository.findById(dataset.id()).isEmpty());

        // second call on an already-absent id must not throw
        repository.delete(dataset.id());
    }
}
