package com.drones.vision.api.dto;

import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.perception.domain.model.FollowState;
import com.drones.vision.perception.domain.model.FollowStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link FollowResponse#from}'s mapping for all five {@link FollowState} values
 * (docs/plans/active/TRACK-FOLLOW-PLAN.md &sect;3.1) — the wire shape {@code
 * StreamController#tracks} serves as the {@code "follow"} object. {@link FollowState#RELEASED} is
 * covered even though {@code FollowTracker} never actually returns one from {@code
 * StreamService#followStatus} (a release drops straight to {@link
 * java.util.Optional#empty()} instead) — this test only pins that the DTO maps whatever domain
 * value it is given, without assuming which values production code happens to produce today.
 */
class FollowResponseTest {

    private static final Instant SINCE = Instant.parse("2026-09-04T10:15:02.500Z");

    @Test
    void requestingStateCarriesZeroTrackIdAndNoLastSeen() {
        FollowStatus status = new FollowStatus(FollowState.REQUESTING, 0L, "", SINCE, null, null, false, 0L, 0.0);

        FollowResponse response = FollowResponse.from(status, SINCE);

        assertEquals(FollowState.REQUESTING, response.state());
        assertEquals(0L, response.trackId());
        assertEquals("", response.label());
        assertEquals(SINCE, response.since());
        assertNull(response.lastSeenAt(), "never bound yet -- there is no box to report an age for");
        assertNull(response.lastSeenAgeMillis());
        assertNull(response.lastBox());
        assertFalse(response.reacquirable());
        assertEquals(0L, response.recoveredAfterMillis());
        assertEquals(0.0, response.recoveryConfidence());
    }

    @Test
    void holdingStateCarriesTheBoundTrackAndComputesItsLastSeenAge() {
        Instant lastSeenAt = Instant.parse("2026-09-04T10:15:06.700Z");
        Instant now = lastSeenAt.plusMillis(4200);
        BoundingBox box = new BoundingBox(0.41, 0.32, 0.08, 0.19);
        FollowStatus status =
                new FollowStatus(FollowState.HOLDING, 7L, "person", SINCE, lastSeenAt, box, false, 0L, 0.0);

        FollowResponse response = FollowResponse.from(status, now);

        assertEquals(FollowState.HOLDING, response.state());
        assertEquals(7L, response.trackId());
        assertEquals("person", response.label());
        assertEquals(lastSeenAt, response.lastSeenAt());
        assertEquals(4200L, response.lastSeenAgeMillis());
        assertEquals(BoundingBoxResponse.from(box), response.lastBox());
    }

    @Test
    void coastingStateMapsLikeHoldingExceptForItsOwnStateName() {
        Instant lastSeenAt = Instant.parse("2026-09-04T10:15:06.700Z");
        BoundingBox box = new BoundingBox(0.41, 0.32, 0.08, 0.19);
        FollowStatus status =
                new FollowStatus(FollowState.COASTING, 7L, "person", SINCE, lastSeenAt, box, false, 0L, 0.0);

        FollowResponse response = FollowResponse.from(status, lastSeenAt);

        assertEquals(FollowState.COASTING, response.state());
        assertEquals(7L, response.trackId());
    }

    @Test
    void lostStateFreezesTheLastKnownBoxAndReportsReacquirability() {
        Instant lastSeenAt = Instant.parse("2026-09-04T10:15:06.700Z");
        Instant now = lastSeenAt.plusSeconds(4);
        BoundingBox box = new BoundingBox(0.41, 0.32, 0.08, 0.19);
        FollowStatus status = new FollowStatus(FollowState.LOST, 7L, "person", SINCE, lastSeenAt, box, true, 0L, 0.0);

        FollowResponse response = FollowResponse.from(status, now);

        assertEquals(FollowState.LOST, response.state());
        // trackId/label/lastBox stay at their last-bound value through LOST (D3/D5) so a re-acquire
        // affordance can still name and locate the target -- FollowResponse must not zero these out
        // itself; StreamController#tracks is the one place `lockedTrackId` is separately zeroed.
        assertEquals(7L, response.trackId());
        assertEquals("person", response.label());
        assertEquals(BoundingBoxResponse.from(box), response.lastBox());
        assertEquals(4000L, response.lastSeenAgeMillis());
        assertTrue(response.reacquirable());
    }

    @Test
    void releasedStateMapsWithoutErrorEvenThoughProductionCodeNeverProducesOne() {
        FollowStatus status = new FollowStatus(FollowState.RELEASED, 0L, "", SINCE, null, null, false, 0L, 0.0);

        FollowResponse response = FollowResponse.from(status, SINCE);

        assertEquals(FollowState.RELEASED, response.state());
    }

    @Test
    void recoveredBindCarriesTheRecoveryFieldsStraightThroughFromTheDomainRecord() {
        Instant lastSeenAt = Instant.parse("2026-09-04T10:15:06.700Z");
        BoundingBox box = new BoundingBox(0.41, 0.32, 0.08, 0.19);
        FollowStatus status =
                new FollowStatus(FollowState.HOLDING, 7L, "person", SINCE, lastSeenAt, box, false, 8200L, 0.71);

        FollowResponse response = FollowResponse.from(status, lastSeenAt);

        assertEquals(8200L, response.recoveredAfterMillis());
        assertEquals(0.71, response.recoveryConfidence());
    }

    @Test
    void aFreshAcquisitionCarriesZeroRecoveryFieldsRatherThanFabricatingOne() {
        Instant lastSeenAt = Instant.parse("2026-09-04T10:15:06.700Z");
        BoundingBox box = new BoundingBox(0.41, 0.32, 0.08, 0.19);
        FollowStatus status =
                new FollowStatus(FollowState.HOLDING, 7L, "person", SINCE, lastSeenAt, box, false, 0L, 0.0);

        FollowResponse response = FollowResponse.from(status, lastSeenAt);

        assertEquals(0L, response.recoveredAfterMillis());
        assertEquals(0.0, response.recoveryConfidence());
    }

    @Test
    void lastSeenAgeMillisClampsToZeroRatherThanGoingNegativeUnderClockSkew() {
        Instant lastSeenAt = Instant.parse("2026-09-04T10:15:06.700Z");
        Instant now = lastSeenAt.minusMillis(50); // "now" slightly behind the capture clock
        BoundingBox box = new BoundingBox(0.41, 0.32, 0.08, 0.19);
        FollowStatus status =
                new FollowStatus(FollowState.HOLDING, 7L, "person", SINCE, lastSeenAt, box, false, 0L, 0.0);

        FollowResponse response = FollowResponse.from(status, now);

        assertEquals(0L, response.lastSeenAgeMillis());
    }
}
