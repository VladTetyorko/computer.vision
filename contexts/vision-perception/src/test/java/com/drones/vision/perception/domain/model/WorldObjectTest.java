package com.drones.vision.perception.domain.model;

import com.drones.vision.kernel.StreamId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorldObjectTest {

    private static ObjectState state() {
        return new ObjectState(1L, ObjectLifecycle.CONFIRMED, StreamId.random(), null, null, null, null, null, null,
                null);
    }

    private static WorldObject.Operator operator() {
        return new WorldObject.Operator(false, false, null);
    }

    private static WorldObject.EventLink event() {
        return new WorldObject.EventLink(null);
    }

    private static WorldObject.Render render() {
        return new WorldObject.Render(RenderTier.T0);
    }

    @Test
    void rejectsNullState() {
        assertThrows(IllegalArgumentException.class, () -> new WorldObject(null, operator(), event(), render()));
    }

    @Test
    void rejectsNullOperator() {
        assertThrows(IllegalArgumentException.class, () -> new WorldObject(state(), null, event(), render()));
    }

    @Test
    void rejectsNullEvent() {
        assertThrows(IllegalArgumentException.class, () -> new WorldObject(state(), operator(), null, render()));
    }

    @Test
    void rejectsNullRender() {
        assertThrows(IllegalArgumentException.class, () -> new WorldObject(state(), operator(), event(), null));
    }

    @Test
    void acceptsWellFormedWorldObject() {
        assertDoesNotThrow(() -> new WorldObject(state(), operator(), event(), render()));
    }

    @Test
    void operatorFollowNullIsAnAbsenceOfARelationNotAnError() {
        WorldObject.Operator operator = assertDoesNotThrow(() -> new WorldObject.Operator(true, false, null));

        assertNull(operator.follow(), "no follow lock target: an absence, not a disabled feature");
    }

    @Test
    void eventLinkOpenEventIdNullIsAnAbsenceOfARelationNotAnError() {
        WorldObject.EventLink event = assertDoesNotThrow(() -> new WorldObject.EventLink(null));

        assertNull(event.openEventId(), "no DetectionEvent open for this object's label: an absence, not an error");
    }

    @Test
    void renderRejectsNullTier() {
        assertThrows(IllegalArgumentException.class, () -> new WorldObject.Render(null));
    }
}
