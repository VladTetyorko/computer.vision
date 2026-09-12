package com.drones.vision.perception.application.pipeline;

import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.DetectionSource;
import com.drones.vision.perception.domain.model.DetectorReason;
import com.drones.vision.perception.domain.model.FollowState;
import com.drones.vision.perception.domain.model.FollowStatus;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.perception.domain.model.TargetLock;
import com.drones.vision.perception.domain.model.TrackRef;
import com.drones.vision.perception.domain.model.TrackState;
import com.drones.vision.perception.domain.model.TrackingTelemetry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every timestamp here comes from the result's own {@code capturedAt}, never {@code Instant.now()} —
 * {@link FollowTracker} has no clock by design, so these assertions are exact rather than tolerant.
 */
class FollowTrackerTest {

    private static final ModelRef MODEL = new ModelRef("yolo26n.pt", "latest");
    private static final Instant T0 = Instant.parse("2026-09-04T10:15:00Z");

    private final StreamId streamId = StreamId.random();

    private static TargetLock trackIdLock(long lockSeq, long trackId) {
        return new TargetLock(lockSeq, trackId, null, null, false);
    }

    private static TargetLock pointLock(long lockSeq) {
        return new TargetLock(lockSeq, null, 0.5, 0.5, false);
    }

    private static TargetLock releaseLock(long lockSeq) {
        return new TargetLock(lockSeq, null, null, null, true);
    }

    private static Detection tracked(String label, long trackId, TrackState state, DetectionSource source) {
        return new Detection(label, 0.9, new BoundingBox(0.1, 0.2, 0.1, 0.1), MODEL,
                new TrackRef(trackId, state, source, 0.0, 0.0, 1, false, 0.0, 0L));
    }

    private static Detection recovered(String label, long trackId, DetectionSource source,
                                        double identityConfidence, long dormantMillis) {
        return new Detection(label, 0.9, new BoundingBox(0.1, 0.2, 0.1, 0.1), MODEL,
                new TrackRef(trackId, TrackState.CONFIRMED, source, 0.0, 0.0, 1, false, identityConfidence,
                        dormantMillis));
    }

    private DetectionResult bound(Instant at, long lockedTrackId, Detection... detections) {
        TrackingTelemetry telemetry =
                new TrackingTelemetry(true, DetectorReason.ALWAYS, Duration.ZERO, "engine", lockedTrackId);
        return new DetectionResult(streamId, 0L, at, List.of(detections), Duration.ZERO, telemetry, null, List.of());
    }

    private DetectionResult unbound(Instant at) {
        TrackingTelemetry telemetry =
                new TrackingTelemetry(true, DetectorReason.NO_LOCK, Duration.ZERO, "engine", 0L);
        return new DetectionResult(streamId, 0L, at, List.of(), Duration.ZERO, telemetry, null, List.of());
    }

    private DetectionResult noTracking(Instant at) {
        return new DetectionResult(streamId, 0L, at, List.of(), Duration.ZERO, null, null, List.of());
    }

    // -- construction --------------------------------------------------------------------------

    @Test
    void rejectsNonPositiveMemoryTtl() {
        assertThrows(IllegalArgumentException.class, () -> new FollowTracker(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new FollowTracker(Duration.ofSeconds(-1)));
    }

    @Test
    void rejectsNullMemoryTtl() {
        assertThrows(NullPointerException.class, () -> new FollowTracker(null));
    }

    @Test
    void rejectsNullLockAndNullResult() {
        FollowTracker tracker = new FollowTracker();
        assertThrows(NullPointerException.class, () -> tracker.lockRequested(null));
        assertThrows(NullPointerException.class, () -> tracker.accept(null));
    }

    // -- never requested / tracking off --------------------------------------------------------

    @Test
    void neverRequestedReadsEmpty() {
        FollowTracker tracker = new FollowTracker();
        assertTrue(tracker.status().isEmpty());

        tracker.accept(unbound(T0));

        assertTrue(tracker.status().isEmpty());
    }

    @Test
    void resultWithNoTrackingTelemetryIsIgnored() {
        FollowTracker tracker = new FollowTracker();
        tracker.lockRequested(trackIdLock(1, 5));

        tracker.accept(noTracking(T0));

        assertTrue(tracker.status().isEmpty());
    }

    // -- REQUESTING -----------------------------------------------------------------------------

    @Test
    void requestingWhileLockedTrackIdStillZero() {
        FollowTracker tracker = new FollowTracker();
        tracker.lockRequested(trackIdLock(1, 5));

        tracker.accept(unbound(T0));

        FollowStatus status = tracker.status().orElseThrow();
        assertEquals(FollowState.REQUESTING, status.state());
        assertEquals(0L, status.trackId());
        assertEquals(T0, status.since());
        assertFalse(status.reacquirable());
    }

    @Test
    void requestingSinceStaysStableAcrossRepeatedUnboundFrames() {
        FollowTracker tracker = new FollowTracker();
        tracker.lockRequested(trackIdLock(1, 5));

        tracker.accept(unbound(T0));
        tracker.accept(unbound(T0.plusSeconds(1)));

        assertEquals(T0, tracker.status().orElseThrow().since());
    }

    // -- HOLDING / COASTING -----------------------------------------------------------------------

    @Test
    void holdingAfterRequestingWhenBoundToDetectorSourcedTrack() {
        FollowTracker tracker = new FollowTracker();
        tracker.lockRequested(trackIdLock(1, 5));
        tracker.accept(unbound(T0));

        Instant t1 = T0.plusSeconds(1);
        tracker.accept(bound(t1, 5, tracked("person", 5, TrackState.CONFIRMED, DetectionSource.DETECTOR)));

        FollowStatus status = tracker.status().orElseThrow();
        assertEquals(FollowState.HOLDING, status.state());
        assertEquals(5L, status.trackId());
        assertEquals("person", status.label());
        assertEquals(t1, status.since());
        assertEquals(t1, status.lastSeenAt());
        assertEquals(new BoundingBox(0.1, 0.2, 0.1, 0.1), status.lastBox());
        assertEquals(0L, status.recoveredAfterMillis());
        assertEquals(0.0, status.recoveryConfidence());
    }

    @Test
    void holdingImmediatelyWithNoPriorRequestingObservation() {
        FollowTracker tracker = new FollowTracker();
        tracker.lockRequested(trackIdLock(1, 5));

        tracker.accept(bound(T0, 5, tracked("person", 5, TrackState.CONFIRMED, DetectionSource.DETECTOR)));

        FollowStatus status = tracker.status().orElseThrow();
        assertEquals(FollowState.HOLDING, status.state());
        assertEquals(T0, status.since());
    }

    @Test
    void coastingWhenNewestTrackRefIsTrackerSourced() {
        FollowTracker tracker = holdingTracker();

        Instant t2 = T0.plusSeconds(2);
        tracker.accept(bound(t2, 5, tracked("person", 5, TrackState.COASTING, DetectionSource.TRACKER)));

        FollowStatus status = tracker.status().orElseThrow();
        assertEquals(FollowState.COASTING, status.state());
        assertEquals(5L, status.trackId());
        assertEquals("person", status.label());
        assertEquals(t2, status.since(), "since restamps on every state transition, not only on (re)bind");
        assertEquals(t2, status.lastSeenAt());
    }

    @Test
    void holdingResumesAfterCoastingWithoutBeingMistakenForANewBind() {
        FollowTracker tracker = holdingTracker();
        Instant t2 = T0.plusSeconds(2);
        tracker.accept(bound(t2, 5, tracked("person", 5, TrackState.COASTING, DetectionSource.TRACKER)));

        Instant t3 = T0.plusSeconds(3);
        tracker.accept(bound(t3, 5, tracked("person", 5, TrackState.CONFIRMED, DetectionSource.DETECTOR)));

        FollowStatus status = tracker.status().orElseThrow();
        assertEquals(FollowState.HOLDING, status.state());
        assertEquals(t3, status.since());
        assertEquals(0L, status.recoveredAfterMillis(), "re-confirming after a coast is not a memory recovery");
    }

    @Test
    void stickyOnAFrameWhereTheBoundDetectionWasFilteredOut() {
        FollowTracker tracker = holdingTracker();
        Instant t1 = T0.plusSeconds(1);
        FollowStatus before = tracker.status().orElseThrow();

        // lockedTrackId still reports the bind, but the label-filtered result carries no matching
        // Detection for it (StreamPipeline.applyLabelFilters dropped it before this call).
        tracker.accept(bound(t1, 5));

        FollowStatus status = tracker.status().orElseThrow();
        assertEquals(before.state(), status.state());
        assertEquals(before.label(), status.label());
        assertEquals(before.lastSeenAt(), status.lastSeenAt());
        assertEquals(before.lastBox(), status.lastBox());
        assertEquals(before.since(), status.since());
    }

    // -- LOST -------------------------------------------------------------------------------------

    @Test
    void lostFreezesLastSeenAtAndLastBoxAndKeepsLabelAndTrackId() {
        FollowTracker tracker = holdingTracker();
        Instant lastBoundAt = T0.plusSeconds(1);

        Instant lostAt = T0.plusSeconds(2);
        tracker.accept(unbound(lostAt));

        FollowStatus status = tracker.status().orElseThrow();
        assertEquals(FollowState.LOST, status.state());
        assertEquals(5L, status.trackId(), "trackId stays at its last bound value so re-acquire can resend it");
        assertEquals("person", status.label(), "label is not cleared on loss (D3/D5)");
        assertEquals(lostAt, status.since());
        assertEquals(lastBoundAt, status.lastSeenAt(), "lastSeenAt freezes, it does not advance to lostAt");

        Instant stillLostAt = T0.plusSeconds(30);
        tracker.accept(unbound(stillLostAt));

        FollowStatus stillLost = tracker.status().orElseThrow();
        assertEquals(FollowState.LOST, stillLost.state());
        assertEquals(lostAt, stillLost.since(), "since does not restamp while still LOST");
        assertEquals(lastBoundAt, stillLost.lastSeenAt(), "lastSeenAt/lastBox stay frozen across further LOST frames");
        assertEquals(status.lastBox(), stillLost.lastBox());
    }

    // -- RELEASED ---------------------------------------------------------------------------------

    @Test
    void releaseDropsFollowStatusToEmpty() {
        FollowTracker tracker = holdingTracker();

        tracker.lockRequested(releaseLock(2));

        assertTrue(tracker.status().isEmpty());
    }

    @Test
    void releaseWhileLostAlsoDropsToEmpty() {
        FollowTracker tracker = holdingTracker();
        tracker.accept(unbound(T0.plusSeconds(2)));
        assertEquals(FollowState.LOST, tracker.status().orElseThrow().state());

        tracker.lockRequested(releaseLock(2));

        assertTrue(tracker.status().isEmpty());
    }

    @Test
    void afterReleaseAFurtherUnboundFrameStaysEmptyNotRequesting() {
        FollowTracker tracker = holdingTracker();
        tracker.lockRequested(releaseLock(2));

        tracker.accept(unbound(T0.plusSeconds(5)));

        assertTrue(tracker.status().isEmpty());
    }

    // -- recovery (L4) ------------------------------------------------------------------------------

    @Test
    void coastThenRecoverKeepsTheSameTrackIdAndLabel() {
        FollowTracker tracker = new FollowTracker();
        tracker.lockRequested(trackIdLock(1, 9));

        Instant t1 = T0.plusSeconds(1);
        tracker.accept(bound(t1, 9, tracked("dog", 9, TrackState.CONFIRMED, DetectionSource.DETECTOR)));
        Instant t2 = T0.plusSeconds(2);
        tracker.accept(bound(t2, 9, tracked("dog", 9, TrackState.COASTING, DetectionSource.TRACKER)));
        assertEquals(FollowState.COASTING, tracker.status().orElseThrow().state());

        Instant lostAt = T0.plusSeconds(3);
        tracker.accept(unbound(lostAt));
        FollowStatus lost = tracker.status().orElseThrow();
        assertEquals(FollowState.LOST, lost.state());
        assertEquals(9L, lost.trackId());
        assertEquals("dog", lost.label());

        Instant recoveredAt = T0.plusSeconds(11);
        tracker.accept(bound(recoveredAt, 9, recovered("dog", 9, DetectionSource.DETECTOR, 0.71, 8200)));

        FollowStatus recoveredStatus = tracker.status().orElseThrow();
        assertEquals(FollowState.HOLDING, recoveredStatus.state(),
                "a recovery goes straight back to HOLDING, not a fresh acquire flow");
        assertEquals(9L, recoveredStatus.trackId());
        assertEquals("dog", recoveredStatus.label());
        assertEquals(recoveredAt, recoveredStatus.since());
        assertEquals(8200L, recoveredStatus.recoveredAfterMillis());
        assertEquals(0.71, recoveredStatus.recoveryConfidence());
    }

    @Test
    void freshAcquisitionHasZeroRecoveryFields() {
        FollowTracker tracker = new FollowTracker();
        tracker.lockRequested(trackIdLock(1, 5));

        tracker.accept(bound(T0, 5, tracked("person", 5, TrackState.CONFIRMED, DetectionSource.DETECTOR)));

        FollowStatus status = tracker.status().orElseThrow();
        assertEquals(0L, status.recoveredAfterMillis());
        assertEquals(0.0, status.recoveryConfidence());
    }

    // -- reacquirable --------------------------------------------------------------------------------

    @Test
    void reacquirableTrueWithinMemoryTtlForATrackIdLock() {
        FollowTracker tracker = new FollowTracker(Duration.ofSeconds(30));
        tracker.lockRequested(trackIdLock(1, 5));
        tracker.accept(bound(T0, 5, tracked("person", 5, TrackState.CONFIRMED, DetectionSource.DETECTOR)));

        tracker.accept(unbound(T0.plusSeconds(5)));

        assertTrue(tracker.status().orElseThrow().reacquirable());
    }

    @Test
    void reacquirableFalseOnceMemoryTtlElapses() {
        FollowTracker tracker = new FollowTracker(Duration.ofSeconds(30));
        tracker.lockRequested(trackIdLock(1, 5));
        tracker.accept(bound(T0, 5, tracked("person", 5, TrackState.CONFIRMED, DetectionSource.DETECTOR)));

        tracker.accept(unbound(T0.plusSeconds(31)));

        assertFalse(tracker.status().orElseThrow().reacquirable());
    }

    @Test
    void reacquirableFalseForAPointFormLockEvenImmediatelyAfterLoss() {
        FollowTracker tracker = new FollowTracker(Duration.ofSeconds(30));
        tracker.lockRequested(pointLock(1));
        tracker.accept(bound(T0, 5, tracked("person", 5, TrackState.CONFIRMED, DetectionSource.DETECTOR)));

        tracker.accept(unbound(T0.plusSeconds(1)));

        assertFalse(tracker.status().orElseThrow().reacquirable());
    }

    @Test
    void reacquirableFalseWhileHoldingOrCoasting() {
        FollowTracker tracker = holdingTracker();
        assertFalse(tracker.status().orElseThrow().reacquirable());
    }

    // -- clear (model re-arm / gate close) --------------------------------------------------------

    @Test
    void clearResetsToNeverRequested() {
        FollowTracker tracker = holdingTracker();

        tracker.clear();

        assertTrue(tracker.status().isEmpty());

        // requestPending must also have been reset -- otherwise the next unbound frame would
        // wrongly resurrect REQUESTING for a lock this pipeline no longer remembers.
        tracker.accept(unbound(T0.plusSeconds(9)));
        assertTrue(tracker.status().isEmpty());
    }

    // -- all five states enumerated end-to-end ------------------------------------------------------

    @Test
    void allFiveStatesAreReachableInSequence() {
        FollowTracker tracker = new FollowTracker();

        tracker.lockRequested(trackIdLock(1, 5));
        tracker.accept(unbound(T0));
        assertEquals(FollowState.REQUESTING, tracker.status().orElseThrow().state());

        Instant t1 = T0.plusSeconds(1);
        tracker.accept(bound(t1, 5, tracked("person", 5, TrackState.CONFIRMED, DetectionSource.DETECTOR)));
        assertEquals(FollowState.HOLDING, tracker.status().orElseThrow().state());

        Instant t2 = T0.plusSeconds(2);
        tracker.accept(bound(t2, 5, tracked("person", 5, TrackState.COASTING, DetectionSource.TRACKER)));
        assertEquals(FollowState.COASTING, tracker.status().orElseThrow().state());

        Instant t3 = T0.plusSeconds(3);
        tracker.accept(unbound(t3));
        assertEquals(FollowState.LOST, tracker.status().orElseThrow().state());

        tracker.lockRequested(releaseLock(2));
        assertTrue(tracker.status().isEmpty(), "RELEASED is a transition, not an observable resting state");
    }

    private FollowTracker holdingTracker() {
        FollowTracker tracker = new FollowTracker();
        tracker.lockRequested(trackIdLock(1, 5));
        tracker.accept(unbound(T0));
        tracker.accept(bound(T0.plusSeconds(1), 5, tracked("person", 5, TrackState.CONFIRMED, DetectionSource.DETECTOR)));
        return tracker;
    }
}
