package com.drones.vision.perception.domain.model;

import com.drones.vision.kernel.BoundingBox;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FollowStatusTest {

    private static final Instant SINCE = Instant.parse("2026-09-04T10:15:00Z");

    @Test
    void acceptsEveryFollowStateWithNoBind() {
        for (FollowState state : FollowState.values()) {
            FollowStatus status = new FollowStatus(state, 0L, "", SINCE, null, null, false, 0L, 0.0);
            assertEquals(state, status.state());
        }
    }

    @Test
    void acceptsABoundState() {
        BoundingBox box = new BoundingBox(0.1, 0.2, 0.3, 0.4);
        FollowStatus status = new FollowStatus(FollowState.HOLDING, 7L, "person", SINCE, SINCE, box, false, 0L, 0.0);

        assertEquals(7L, status.trackId());
        assertEquals("person", status.label());
        assertEquals(box, status.lastBox());
    }

    @Test
    void rejectsNullState() {
        assertThrows(IllegalArgumentException.class,
                () -> new FollowStatus(null, 0L, "", SINCE, null, null, false, 0L, 0.0));
    }

    @Test
    void rejectsNegativeTrackId() {
        assertThrows(IllegalArgumentException.class,
                () -> new FollowStatus(FollowState.HOLDING, -1L, "", SINCE, null, null, false, 0L, 0.0));
    }

    @Test
    void rejectsNullLabel() {
        assertThrows(IllegalArgumentException.class,
                () -> new FollowStatus(FollowState.REQUESTING, 0L, null, SINCE, null, null, false, 0L, 0.0));
    }

    @Test
    void rejectsNullSince() {
        assertThrows(IllegalArgumentException.class,
                () -> new FollowStatus(FollowState.REQUESTING, 0L, "", null, null, null, false, 0L, 0.0));
    }

    @Test
    void rejectsLastSeenAtWithoutLastBox() {
        assertThrows(IllegalArgumentException.class,
                () -> new FollowStatus(FollowState.HOLDING, 1L, "person", SINCE, SINCE, null, false, 0L, 0.0));
    }

    @Test
    void rejectsLastBoxWithoutLastSeenAt() {
        BoundingBox box = new BoundingBox(0.1, 0.2, 0.3, 0.4);
        assertThrows(IllegalArgumentException.class,
                () -> new FollowStatus(FollowState.HOLDING, 1L, "person", SINCE, null, box, false, 0L, 0.0));
    }

    @Test
    void rejectsNegativeRecoveredAfterMillis() {
        assertThrows(IllegalArgumentException.class,
                () -> new FollowStatus(FollowState.HOLDING, 1L, "person", SINCE, SINCE,
                        new BoundingBox(0.1, 0.2, 0.3, 0.4), false, -1L, 0.0));
    }

    @Test
    void rejectsOutOfRangeRecoveryConfidence() {
        assertThrows(IllegalArgumentException.class,
                () -> new FollowStatus(FollowState.HOLDING, 1L, "person", SINCE, SINCE,
                        new BoundingBox(0.1, 0.2, 0.3, 0.4), false, 0L, -0.01));
        assertThrows(IllegalArgumentException.class,
                () -> new FollowStatus(FollowState.HOLDING, 1L, "person", SINCE, SINCE,
                        new BoundingBox(0.1, 0.2, 0.3, 0.4), false, 0L, 1.01));
    }
}
