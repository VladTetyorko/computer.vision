package com.drones.vision.app.devsupport;

import com.drones.vision.map.domain.model.Affiliation;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.map.domain.model.Mark;
import com.drones.vision.map.domain.model.MarkId;
import com.drones.vision.map.domain.model.MarkKind;
import com.drones.vision.map.domain.model.MarkSource;
import com.drones.vision.map.domain.model.MarkStatus;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.map.domain.model.Verification;
import com.drones.vision.map.domain.model.Verification.VerificationState;
import com.drones.vision.map.domain.port.MarkRepositoryPort;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link MarkRepositoryPort} contract against the in-memory reference implementation
 * (docs/plans/done/TACTICAL-MARKS-PLAN.md §3, reworked by docs/plans/done/MAP-REWORK-PLAN.md §2.2) — the same contract
 * {@code JpaMarkRepository} is judged against in {@code adapter-persistence}'s Postgres tests, so
 * the two stay behavior-compatible.
 *
 * <p>The port itself is unchanged by the map rework; what changed is the {@link Mark} flowing
 * through it, which now carries a {@link LayerId}, an {@link Affiliation} and a {@link Verification}.
 * The upsert case below exercises exactly those, since promotion ({@code withLayer}) and review
 * ({@code withVerification}) are both save-over-the-same-id operations.
 */
class InMemoryMarkRepositoryTest {

    private final MarkRepositoryPort repository = new InMemoryMarkRepository();

    private static Mark mark(MarkId id, MarkStatus status) {
        return mark(id, status, LayerId.random(), Verification.unverified());
    }

    private static Mark mark(MarkId id, MarkStatus status, LayerId layerId, Verification verification) {
        return new Mark(id, layerId, new GeoPosition(50.45, 30.52, null), MarkKind.TARGET,
                Affiliation.HOSTILE, "Bunker", null, new Ownership(UserId.random(), GroupId.random()),
                Instant.now(), status, MarkSource.MANUAL, verification);
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
    void saveOverwritesTheLayerAndVerificationOfAPromotedMark() {
        MarkId id = MarkId.random();
        LayerId team = LayerId.random();
        LayerId cop = LayerId.random();
        UserId reviewer = UserId.random();
        repository.save(mark(id, MarkStatus.ACTIVE, team, Verification.unverified()));

        repository.save(mark(id, MarkStatus.ACTIVE, cop,
                new Verification(VerificationState.CONFIRMED, reviewer, Instant.now())));

        Optional<Mark> found = repository.findById(id);
        assertTrue(found.isPresent());
        assertEquals(cop, found.get().layerId(), "promotion moves the mark to the target layer");
        assertEquals(VerificationState.CONFIRMED, found.get().verification().state());
        assertEquals(reviewer, found.get().verification().verifiedBy());
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
