package com.drones.vision.perception.domain.model;

import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.kernel.StreamId;
import java.util.List;

/**
 * Shared {@link ObjectState} fixtures for this wave's tests.
 *
 * <p>{@link #everyFieldDistinct()} is deliberately reused beyond {@code ObjectStateTest} — a
 * later step of docs/plans/active/CV-ORCHESTRATION-PLAN.md wave W1 round-trips this exact
 * instance proto→Java→JSON→TS. Every numeric leaf value across the whole tree is pairwise
 * distinct (including across nested groups), so a codec that swapped two same-typed fields — say
 * {@code kinematics.velocityX}/{@code velocityY}, or {@code timing.hits}/{@code misses} — fails
 * that round trip instead of passing by coincidence.
 */
public final class ObjectStateFixtures {

    private ObjectStateFixtures() {
    }

    public static ObjectState everyFieldDistinct() {
        ObjectState.Kinematics kinematics = new ObjectState.Kinematics(
                new BoundingBox(0.01, 0.02, 0.03, 0.04),
                new BoundingBox(0.05, 0.06, 0.07, 0.08),
                new BoundingBox(0.09, 0.10, 0.11, 0.12),
                new BoundingBox(0.13, 0.14, 0.15, 0.16),
                170L, 0.21, 0.22, 0.23, 0.24, true);

        ObjectState.Belief belief = new ObjectState.Belief(0.31, 0.32, 0.33, 340L);

        ObjectState.Provenance provenance = new ObjectState.Provenance(
                EvidenceSource.MEMORY, List.of("detect.full", "assoc.cost"), 0.41, true);

        ObjectState.MemoryFacts memory = new ObjectState.MemoryFacts(true, 0.51, 520L, 53, 0.52);

        ObjectState.LockFacts lock = new ObjectState.LockFacts(false, 60L);

        ObjectState.Timing timing = new ObjectState.Timing(700L, 800L, 900L, 71, 72, 73);

        ObjectState.Identity identity = new ObjectState.Identity("elected-label", "raw-label",
                List.of(new ObjectState.LabelCandidate("person", 0.81),
                        new ObjectState.LabelCandidate("dog", 0.19)),
                82);

        return new ObjectState(101L, ObjectLifecycle.COASTING, StreamId.random(), identity, kinematics, belief,
                provenance, memory, lock, timing);
    }
}
