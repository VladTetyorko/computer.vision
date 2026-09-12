package com.drones.vision.perception.application.pipeline;

import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.DetectionEventId;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.EvidenceSource;
import com.drones.vision.perception.domain.model.ObjectLifecycle;
import com.drones.vision.perception.domain.model.ObjectState;
import com.drones.vision.perception.domain.model.RenderTier;
import com.drones.vision.perception.domain.model.WorldObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The half of {@link WorldModel} that is new in wave W2 (docs/plans/active/CV-ORCHESTRATION-PLAN.md
 * &sect;4.6): the {@link WorldObject} fold, and in particular {@code render.tier} — the server-side
 * port of the client overlay's {@code detectionTiers}.
 *
 * <p>Every timestamp comes from the result's own {@code capturedAt}: {@link WorldModel} has no clock.
 */
class WorldModelFoldTest {

    private static final Instant T0 = Instant.parse("2026-09-12T10:00:00Z");
    private static final Duration RETENTION = Duration.ofSeconds(5);

    private final StreamId streamId = StreamId.random();

    private WorldModel model(RenderTierSettings tiers, Map<String, DetectionEventId> openEvents) {
        return new WorldModel(RETENTION, WorldModel.DEFAULT_MEMORY_TTL, tiers, openEvents::get);
    }

    private WorldModel model() {
        return model(RenderTierSettings.defaults(), Map.of());
    }

    private DetectionResult result(Instant at, List<ObjectState> objects) {
        return new DetectionResult(streamId, 0L, at, List.of(), Duration.ZERO, null, null, objects,
                Optional.empty());
    }

    /**
     * @param size  box width and height (normalized) — the sub-scale test reads both
     * @param moved normalized box-centre displacement on both axes since the previous update
     */
    private ObjectState object(long id, String label, double size, double moved, double confidence,
                                boolean locked) {
        ObjectState.Kinematics kinematics = new ObjectState.Kinematics(new BoundingBox(0.4, 0.4, size, size),
                null, null, null, 0L, 0.0, 0.0, moved, 0.0, false);
        return new ObjectState(id, ObjectLifecycle.CONFIRMED, streamId,
                new ObjectState.Identity(label, label, List.of(), 1), kinematics,
                new ObjectState.Belief(confidence, confidence, confidence, 0L),
                new ObjectState.Provenance(EvidenceSource.DETECTOR, List.of(), 0.0, false),
                new ObjectState.MemoryFacts(false, 0.0, 0L, 0, 0.0),
                new ObjectState.LockFacts(locked, 0L),
                new ObjectState.Timing(0L, 0L, 0L, 1, 1, 0));
    }

    private static WorldObject byId(List<WorldObject> world, long id) {
        return world.stream().filter(object -> object.state().id() == id).findFirst().orElseThrow();
    }

    // -- render tiers ------------------------------------------------------------------------------

    @Test
    void theFollowLockedObjectIsT0WhateverItsSizeOrMotion() {
        WorldModel model = model();

        // small and still: would be T3 on size alone, but the lock wins first, exactly as the client
        // overlay's own priority order does.
        model.accept(result(T0, List.of(object(1L, "person", 0.005, 0.0, 0.9, true))), List.of());

        assertEquals(RenderTier.T0, byId(model.objects(), 1L).render().tier());
    }

    @Test
    void aSubScaleBoxIsT3EvenWhenItIsMovingFast() {
        WorldModel model = model();

        model.accept(result(T0, List.of(object(1L, "person", 0.005, 0.9, 0.99, false))), List.of());

        assertEquals(RenderTier.T3, byId(model.objects(), 1L).render().tier());
    }

    @Test
    void aMovingObjectIsPromotedToT1() {
        WorldModel model = model();

        model.accept(result(T0, List.of(object(1L, "person", 0.2, 0.5, 0.1, false))), List.of());

        assertEquals(RenderTier.T1, byId(model.objects(), 1L).render().tier());
    }

    @Test
    void theRemainderIsRankedByAreaTimesConfidenceAndTheRestAreT2() {
        WorldModel model = model(new RenderTierSettings(1, 0.02, 0.01), Map.of());

        model.accept(result(T0, List.of(
                object(1L, "person", 0.3, 0.0, 0.9, false),
                object(2L, "person", 0.1, 0.0, 0.1, false))), List.of());

        List<WorldObject> world = model.objects();
        assertEquals(RenderTier.T1, byId(world, 1L).render().tier());
        assertEquals(RenderTier.T2, byId(world, 2L).render().tier());
    }

    @Test
    void movementAndTopKShareOneBudgetRatherThanEachHavingTheirOwn() {
        WorldModel model = model(new RenderTierSettings(2, 0.02, 0.01), Map.of());

        model.accept(result(T0, List.of(
                object(1L, "person", 0.05, 0.5, 0.1, false),   // promoted by movement
                object(2L, "person", 0.3, 0.0, 0.9, false),    // best of the remainder
                object(3L, "person", 0.2, 0.0, 0.8, false))),  // would be T1 with two free slots
                List.of());

        List<WorldObject> world = model.objects();
        assertEquals(RenderTier.T1, byId(world, 1L).render().tier());
        assertEquals(RenderTier.T1, byId(world, 2L).render().tier());
        assertEquals(RenderTier.T2, byId(world, 3L).render().tier(),
                "the moving object consumed one of the two notable slots");
    }

    // -- operator relations ------------------------------------------------------------------------

    @Test
    void aSuppressedObjectIsHiddenAndDeniedButNeverReachesTheViewerMirror() {
        WorldModel model = model();
        ObjectState denied = object(2L, "car", 0.2, 0.0, 0.9, false);

        model.accept(result(T0, List.of(object(1L, "person", 0.2, 0.0, 0.9, false))), List.of(denied));

        WorldObject hidden = byId(model.objects(), 2L);
        assertEquals(RenderTier.HIDDEN, hidden.render().tier());
        assertTrue(hidden.operator().denied());
        assertFalse(hidden.operator().followed());
        assertEquals(List.of(1L), model.latestObjects().stream().map(ObjectState::id).toList(),
                "latestObjects() is the viewer mirror: a denied label must not reappear under it");
    }

    @Test
    void anObjectDeniedFromItsVeryFirstFrameStillAppearsInTheTrace() {
        WorldModel model = model();

        model.accept(result(T0, List.of()), List.of(object(9L, "car", 0.2, 0.0, 0.9, false)));

        assertEquals(RenderTier.HIDDEN, byId(model.objects(), 9L).render().tier());
    }

    @Test
    void theLockFactOnTheObjectIsWhatFollowedReports() {
        WorldModel model = model();

        model.accept(result(T0, List.of(object(1L, "person", 0.2, 0.0, 0.9, true))), List.of());

        assertTrue(byId(model.objects(), 1L).operator().followed());
    }

    // -- event link --------------------------------------------------------------------------------

    @Test
    void theOpenEventForThisObjectsLabelIsStampedOnIt() {
        DetectionEventId open = DetectionEventId.random();
        WorldModel model = model(RenderTierSettings.defaults(), Map.of("person", open));

        model.accept(result(T0, List.of(
                object(1L, "person", 0.2, 0.0, 0.9, false),
                object(2L, "car", 0.2, 0.0, 0.9, false))), List.of());

        List<WorldObject> world = model.objects();
        assertEquals(open, byId(world, 1L).event().openEventId());
        assertNull(byId(world, 2L).event().openEventId(),
                "no event open for this label is an absent relation, not a disabled feature");
    }

    // -- lifecycle ----------------------------------------------------------------------------------

    @Test
    void clearDropsEveryFoldedObject() {
        WorldModel model = model();
        model.accept(result(T0, List.of(object(1L, "person", 0.2, 0.0, 0.9, false))), List.of());

        model.clear();

        assertTrue(model.objects().isEmpty());
        assertTrue(model.latestObjects().isEmpty());
    }

    @Test
    void anObjectThatStopsArrivingIsDroppedOnceRetentionElapses() {
        WorldModel model = model();
        model.accept(result(T0, List.of(object(1L, "person", 0.2, 0.0, 0.9, false))), List.of());

        model.accept(result(T0.plus(RETENTION), List.of(object(2L, "person", 0.2, 0.0, 0.9, false))),
                List.of());

        assertEquals(List.of(2L), model.objects().stream().map(object -> object.state().id()).toList());
    }
}
