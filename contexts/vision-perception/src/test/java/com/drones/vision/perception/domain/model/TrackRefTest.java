package com.drones.vision.perception.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackRefTest {

    @Test
    void rejectsUntrackedSentinelTrackId() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrackRef(0L, TrackState.CONFIRMED, DetectionSource.DETECTOR, 0.0, 0.0, 0));
    }

    @Test
    void rejectsNegativeTrackId() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrackRef(-1L, TrackState.CONFIRMED, DetectionSource.DETECTOR, 0.0, 0.0, 0));
    }

    @Test
    void rejectsNegativeAgeFrames() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrackRef(1L, TrackState.CONFIRMED, DetectionSource.DETECTOR, 0.0, 0.0, -1));
    }

    @Test
    void rejectsNullStateOrSource() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrackRef(1L, null, DetectionSource.DETECTOR, 0.0, 0.0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new TrackRef(1L, TrackState.CONFIRMED, null, 0.0, 0.0, 0));
    }

    @Test
    void rejectsNonFiniteVelocity() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrackRef(1L, TrackState.CONFIRMED, DetectionSource.DETECTOR, Double.NaN, 0.0, 0));
        assertThrows(IllegalArgumentException.class, () -> new TrackRef(
                1L, TrackState.CONFIRMED, DetectionSource.DETECTOR, Double.POSITIVE_INFINITY, 0.0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new TrackRef(1L, TrackState.CONFIRMED, DetectionSource.DETECTOR, 0.0, Double.NaN, 0));
    }

    @Test
    void threeArgConvenienceConstructorDefaultsVelocityAndAgeToZero() {
        TrackRef ref = new TrackRef(1L, TrackState.TENTATIVE, DetectionSource.TRACKER);

        assertEquals(0.0, ref.velocityX());
        assertEquals(0.0, ref.velocityY());
        assertEquals(0, ref.ageFrames());
    }

    @Test
    void acceptsWellFormedTrackRef() {
        TrackRef ref = new TrackRef(7L, TrackState.COASTING, DetectionSource.TRACKER, 0.012, -0.001, 143);

        assertEquals(7L, ref.trackId());
        assertEquals(TrackState.COASTING, ref.state());
        assertEquals(DetectionSource.TRACKER, ref.source());
        assertEquals(0.012, ref.velocityX());
        assertEquals(-0.001, ref.velocityY());
        assertEquals(143, ref.ageFrames());
    }

    @Test
    void sixArgConvenienceConstructorDefaultsReupdatedToFalse() {
        TrackRef ref = new TrackRef(7L, TrackState.COASTING, DetectionSource.TRACKER, 0.012, -0.001, 143);

        assertFalse(ref.reupdated());
    }

    @Test
    void threeArgConvenienceConstructorDefaultsReupdatedToFalse() {
        TrackRef ref = new TrackRef(1L, TrackState.TENTATIVE, DetectionSource.TRACKER);

        assertFalse(ref.reupdated());
    }

    @Test
    void acceptsAnExplicitlyReupdatedTrack() {
        TrackRef ref = new TrackRef(7L, TrackState.CONFIRMED, DetectionSource.TRACKER, 0.0, 0.0, 5, true, 0.0, 0L);

        assertTrue(ref.reupdated());
    }

    @Test
    void sixArgConvenienceConstructorDefaultsRecoveryFactsToZero() {
        TrackRef ref = new TrackRef(7L, TrackState.COASTING, DetectionSource.TRACKER, 0.012, -0.001, 143);

        assertEquals(0.0, ref.identityConfidence());
        assertEquals(0L, ref.dormantMillis());
    }

    @Test
    void threeArgConvenienceConstructorDefaultsRecoveryFactsToZero() {
        TrackRef ref = new TrackRef(1L, TrackState.TENTATIVE, DetectionSource.TRACKER);

        assertEquals(0.0, ref.identityConfidence());
        assertEquals(0L, ref.dormantMillis());
    }

    @Test
    void acceptsARecoveredTrackWithIdentityConfidenceAndDormantMillis() {
        TrackRef ref = new TrackRef(7L, TrackState.CONFIRMED, DetectionSource.TRACKER, 0.0, 0.0, 5, true, 0.71, 8200L);

        assertEquals(0.71, ref.identityConfidence());
        assertEquals(8200L, ref.dormantMillis());
    }

    @Test
    void rejectsIdentityConfidenceOutsideZeroToOne() {
        assertThrows(IllegalArgumentException.class, () -> new TrackRef(
                1L, TrackState.CONFIRMED, DetectionSource.DETECTOR, 0.0, 0.0, 0, false, -0.01, 0L));
        assertThrows(IllegalArgumentException.class, () -> new TrackRef(
                1L, TrackState.CONFIRMED, DetectionSource.DETECTOR, 0.0, 0.0, 0, false, 1.01, 0L));
        assertThrows(IllegalArgumentException.class, () -> new TrackRef(
                1L, TrackState.CONFIRMED, DetectionSource.DETECTOR, 0.0, 0.0, 0, false, Double.NaN, 0L));
    }

    @Test
    void rejectsNegativeDormantMillis() {
        assertThrows(IllegalArgumentException.class, () -> new TrackRef(
                1L, TrackState.CONFIRMED, DetectionSource.DETECTOR, 0.0, 0.0, 0, false, 0.0, -1L));
    }
}
