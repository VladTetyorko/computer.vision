package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetLockTest {

    @Test
    void rejectsNegativeLockSeq() {
        assertThrows(IllegalArgumentException.class, () -> new TargetLock(-1L, 7L, null, null, false));
    }

    @Test
    void acceptsTrackIdFormAlone() {
        TargetLock lock = new TargetLock(1L, 7L, null, null, false);

        assertTrue(lock.trackId() == 7L);
    }

    @Test
    void acceptsPointFormAlone() {
        TargetLock lock = new TargetLock(1L, null, 0.5, 0.5, false);

        assertTrue(lock.pointX() == 0.5 && lock.pointY() == 0.5);
    }

    @Test
    void acceptsReleaseFormAlone() {
        TargetLock lock = new TargetLock(1L, null, null, null, true);

        assertTrue(lock.release());
    }

    @Test
    void rejectsNoFormPresent() {
        assertThrows(IllegalArgumentException.class, () -> new TargetLock(1L, null, null, null, false));
    }

    @Test
    void rejectsTrackIdAndPointTogether() {
        assertThrows(IllegalArgumentException.class, () -> new TargetLock(1L, 7L, 0.5, 0.5, false));
    }

    @Test
    void rejectsTrackIdAndReleaseTogether() {
        assertThrows(IllegalArgumentException.class, () -> new TargetLock(1L, 7L, null, null, true));
    }

    @Test
    void rejectsPointAndReleaseTogether() {
        assertThrows(IllegalArgumentException.class, () -> new TargetLock(1L, null, 0.5, 0.5, true));
    }

    @Test
    void rejectsAllThreeFormsTogether() {
        assertThrows(IllegalArgumentException.class, () -> new TargetLock(1L, 7L, 0.5, 0.5, true));
    }

    @Test
    void rejectsPartialPoint() {
        assertThrows(IllegalArgumentException.class, () -> new TargetLock(1L, null, 0.5, null, false));
        assertThrows(IllegalArgumentException.class, () -> new TargetLock(1L, null, null, 0.5, false));
    }

    @Test
    void rejectsOutOfRangePoint() {
        assertThrows(IllegalArgumentException.class, () -> new TargetLock(1L, null, -0.01, 0.5, false));
        assertThrows(IllegalArgumentException.class, () -> new TargetLock(1L, null, 1.01, 0.5, false));
        assertThrows(IllegalArgumentException.class, () -> new TargetLock(1L, null, 0.5, -0.01, false));
        assertThrows(IllegalArgumentException.class, () -> new TargetLock(1L, null, 0.5, 1.01, false));
    }

    @Test
    void acceptsBoundaryPoint() {
        new TargetLock(1L, null, 0.0, 0.0, false);
        new TargetLock(1L, null, 1.0, 1.0, false);
    }
}
