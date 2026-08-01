package com.drones.vision.app.devsupport;

import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.Mark;
import com.drones.vision.domain.model.MarkId;
import com.drones.vision.domain.model.MarkKind;
import com.drones.vision.domain.model.MarkSource;
import com.drones.vision.domain.model.MarkStatus;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.MarkRepositoryPort;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link MarkRepositoryPort} contract against the in-memory reference implementation
 * (docs/TACTICAL-MARKS-PLAN.md §3) — the same contract {@code JpaMarkRepository} is judged
 * against in {@code adapter-persistence}'s Postgres tests.
 */
class InMemoryMarkRepositoryTest {

    private final MarkRepositoryPort repository = new InMemoryMarkRepository();

    private static Mark mark(MarkId id, MarkStatus status) {
        return new Mark(id, new GeoPosition(50.45, 30.52, null), MarkKind.TARGET, "Bunker", null,
                new Ownership(UserId.random(), GroupId.random()), Instant.now(), status, MarkSource.MANUAL);
    }

    @Test
    void unknownIdReturnsEmptyOptional() {
        assertTrue(repository.findById(MarkId.random()).isEmpty());
    }

    @Test
    void saveThenFindByIdRoundTrips() {
        Mark mark = mark(MarkId.random(), MarkStatus.ACTIVE);

        repository.save(mark);

        Optional<Mark> found = repository.findById(mark.id());
        assertTrue(found.isPresent());
        assertEquals(mark, found.get());
    }

    @Test
    void saveIsAnUpsertPreservingId() {
        MarkId id = MarkId.random();
        repository.save(mark(id, MarkStatus.ACTIVE));
        Mark cleared = mark(id, MarkStatus.CLEARED);

        repository.save(cleared);

        Optional<Mark> found = repository.findById(id);
        assertTrue(found.isPresent());
        assertEquals(MarkStatus.CLEARED, found.get().status());
    }

    @Test
    void findAllReturnsEverySavedMark() {
        Mark first = mark(MarkId.random(), MarkStatus.ACTIVE);
        Mark second = mark(MarkId.random(), MarkStatus.ACTIVE);
        repository.save(first);
        repository.save(second);

        List<Mark> all = repository.findAll();
        assertTrue(all.contains(first));
        assertTrue(all.contains(second));
    }

    @Test
    void deleteByIdIsIdempotentAndRemovesTheMark() {
        Mark mark = mark(MarkId.random(), MarkStatus.ACTIVE);
        repository.save(mark);

        repository.deleteById(mark.id());
        assertTrue(repository.findById(mark.id()).isEmpty());

        // second call on an already-absent id must not throw
        repository.deleteById(mark.id());
    }
}
