package com.drones.vision.app.devsupport;

import com.drones.vision.domain.model.SampleImage;
import com.drones.vision.domain.model.TrainingSampleId;
import com.drones.vision.domain.port.out.SampleImageStorePort;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link SampleImageStorePort} contract against the in-memory reference implementation
 * (docs/CV-TRAINING-PLAN.md §1/§C, Wave T3) — the same contract {@code JpaSampleImageStore} is
 * judged against in {@code adapter-persistence}'s Postgres tests.
 */
class InMemorySampleImageStoreTest {

    private final SampleImageStorePort store = new InMemorySampleImageStore();

    @Test
    void unknownSampleIdReturnsEmptyOptional() {
        assertTrue(store.findById(TrainingSampleId.random()).isEmpty());
    }

    @Test
    void savedImageRoundTripsBytesAndContentType() {
        TrainingSampleId id = TrainingSampleId.random();
        byte[] data = {1, 2, 3};

        store.save(id, new SampleImage(data, "image/jpeg"));

        Optional<SampleImage> found = store.findById(id);
        assertTrue(found.isPresent());
        assertArrayEquals(data, found.get().data());
        assertEquals("image/jpeg", found.get().contentType());
    }

    @Test
    void saveIsAnUpsert() {
        TrainingSampleId id = TrainingSampleId.random();
        store.save(id, new SampleImage(new byte[]{1}, "image/jpeg"));
        store.save(id, new SampleImage(new byte[]{2, 2}, "image/png"));

        Optional<SampleImage> found = store.findById(id);
        assertTrue(found.isPresent());
        assertArrayEquals(new byte[]{2, 2}, found.get().data());
        assertEquals("image/png", found.get().contentType());
    }

    @Test
    void deleteIsIdempotentAndRemovesTheImage() {
        TrainingSampleId id = TrainingSampleId.random();
        store.save(id, new SampleImage(new byte[]{9}, "image/png"));

        store.delete(id);
        assertTrue(store.findById(id).isEmpty());

        // second call on an already-absent id must not throw
        store.delete(id);
    }
}
