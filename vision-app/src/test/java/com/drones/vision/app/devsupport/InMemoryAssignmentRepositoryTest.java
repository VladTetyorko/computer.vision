package com.drones.vision.app.devsupport;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.identity.domain.port.AssignmentRepositoryPort;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pilot&rarr;asset assignment port contract against the in-memory reference implementation
 * (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2, feature 2) — the same contract {@code JpaAssignmentRepository}
 * is judged against in {@code adapter-persistence}'s Postgres tests.
 */
class InMemoryAssignmentRepositoryTest {

    private final AssignmentRepositoryPort repository = new InMemoryAssignmentRepository();

    @Test
    void assignIsIdempotentAndQueryableBothDirections() {
        UserId pilot = UserId.random();
        AssetId asset = AssetId.random();

        repository.assign(pilot, asset);
        repository.assign(pilot, asset); // idempotent — no duplicate

        assertTrue(repository.isAssigned(pilot, asset));
        assertEquals(Set.of(asset), repository.assetsForPilot(pilot));
        assertEquals(Set.of(pilot), repository.pilotsForAsset(asset));
    }

    @Test
    void unassignIsIdempotentRemoval() {
        UserId pilot = UserId.random();
        AssetId asset = AssetId.random();
        repository.assign(pilot, asset);

        repository.unassign(pilot, asset);
        repository.unassign(pilot, asset); // idempotent — removing a missing link is a no-op

        assertFalse(repository.isAssigned(pilot, asset));
        assertTrue(repository.assetsForPilot(pilot).isEmpty());
        assertTrue(repository.pilotsForAsset(asset).isEmpty());
    }

    @Test
    void tracksMultiplePilotsAndAssetsIndependently() {
        UserId alice = UserId.random();
        UserId bob = UserId.random();
        AssetId drone1 = AssetId.random();
        AssetId drone2 = AssetId.random();

        repository.assign(alice, drone1);
        repository.assign(alice, drone2);
        repository.assign(bob, drone1);

        assertEquals(Set.of(drone1, drone2), repository.assetsForPilot(alice));
        assertEquals(Set.of(drone1), repository.assetsForPilot(bob));
        assertEquals(Set.of(alice, bob), repository.pilotsForAsset(drone1));
        assertEquals(Set.of(alice), repository.pilotsForAsset(drone2));
    }

    @Test
    void unknownPilotOrAssetYieldsEmptySets() {
        assertTrue(repository.assetsForPilot(UserId.random()).isEmpty());
        assertTrue(repository.pilotsForAsset(AssetId.random()).isEmpty());
        assertFalse(repository.isAssigned(UserId.random(), AssetId.random()));
    }
}
