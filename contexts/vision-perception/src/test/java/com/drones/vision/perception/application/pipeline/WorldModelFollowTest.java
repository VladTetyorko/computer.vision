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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The former {@code FollowTrackerTest}, ported verbatim onto {@link WorldModel} when the follow lock
 * moved into it (docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.6, wave W2) — same scenarios,
 * same assertions, so the move is provably behaviour-preserving.
 *
 * <p>Every timestamp here comes from the result's own {@code capturedAt}, never {@code
 * Instant.now()} — {@link WorldModel} has no clock by design, so these assertions are exact rather
 * than tolerant.
 */
class WorldModelFollowTest {

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

    /** A {@link WorldModel} whose follow half is configured exactly as {@code FollowTracker} was. */
    private static WorldModel follower(Duration memoryTtl) {
        return new WorldModel(Duration.ofSeconds(5), memoryTtl, RenderTierSettings.defaults(), label -> null);
    }

    private DetectionResult bound(Instant at, long lockedTrackId, Detection... detections) {
        TrackingTelemetry telemetry =
                new TrackingTelemetry(true, DetectorReason.ALWAYS, Duration.ZERO, "engine", lockedTrackId);
        return new DetectionResult(streamId, 0L, at, List.of(detections), Duration.ZERO, telemetry, null, List.of(), Optional.empty());
    }

    private DetectionResult unbound(Instant at) {
        TrackingTelemetry telemetry =
                new TrackingTelemetry(true, DetectorReason.NO_LOCK, Duration.ZERO, "engine", 0L);
        return new DetectionResult(streamId, 0L, at, List.of(), Duration.ZERO, telemetry, null, List.of(), Optional.empty());
    }

    private DetectionResult noTracking(Instant at) {
        return new DetectionResult(streamId, 0L, at, List.of(), Duration.ZERO, null, null, List.of(), Optional.empty());
    }

    // -- construction --------------------------------------------------------------------------

    @Test
    void rejectsNonPositiveMemoryTtl() {
        assertThrows(IllegalArgumentException.class, () -> follower(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> follower(Duration.ofSeconds(-1)));
    }

    @Test
    void rejectsNullMemoryTtl() {
        assertThrows(NullPointerException.class, () -> follower(null));
    }

    @Test
    void rejectsNullLockAndNullResult() {
        WorldModel tracker = follower(WorldModel.DEFAULT_MEMORY_TTL);
        assertThrows(NullPointerException.class, () -> tracker.lockRequested(null));
        assertThrows(NullPointerException.class, () -> tracker.accept(null, List.of()));
    }

    // -- never requested / tracking off --------------------------------------------------------

    @Test
    void neverRequestedReadsEmpty() {
        WorldModel tracker = follower(WorldModel.DEFAULT_MEMORY_TTL);
        assertTrue(tracker.followStatus().isEmpty());

        tracker.accept(unbound(T0), List.of());

        assertTrue(tracker.followStatus().isEmpty());
    }

    @Test
    void resultWithNoTrackingTelemetryIsIgnored() {
        WorldModel tracker = follower(WorldModel.DEFAULT_MEMORY_TTL);
        tracker.lockRequested(trackIdLock(1, 5));

        tracker.accept(noTracking(T0), List.of());

        assertTrue(tracker.followStatus().isEmpty());
    }

    // -- REQUESTING -----------------------------------------------------------------------------

    @Test
    void requestingWhileLockedTrackIdStillZero() {
        WorldModel tracker = follower(WorldModel.DEFAULT_MEMORY_TTL);
        tracker.lockRequested(trackIdLock(1, 5));

        tracker.accept(unbound(T0), List.of());

        FollowStatus status = tracker.followStatus().orElseThrow();
        assertEquals(FollowState.REQUESTING, status.state());
        assertEquals(0L, status.trackId());
        assertEquals(T0, status.since());
        assertFalse(status.reacquirable());
    }

    @Test
    void requestingSinceStaysStableAcrossRepeatedUnboundFrames() {
        WorldModel tracker = follower(WorldModel.DEFAULT_MEMORY_TTL);
        tracker.lockRequested(trackIdLock(1, 5));

        tracker.accept(unbound(T0), List.of());
        tracker.accept(unbound(T0.plusSeconds(1)), List.of());

        assertEquals(T0, tracker.followStatus().orElseThrow().since());
    }

    // -- HOLDING / COASTING -----------------------------------------------------------------------

    @Test
    void holdingAfterRequestingWhenBoundToDetectorSourcedTrack() {
        WorldModel tracker = follower(WorldModel.DEFAULT_MEMORY_TTL);
        tracker.lockRequested(trackIdLock(1, 5));
        tracker.accept(unbound(T0), List.of());

        Instant t1 = T0.plusSeconds(1);
        tracker.accept(bound(t1, 5, tracked("person", 5, TrackState.CONFIRMED, DetectionSource.DETECTOR)), List.of());

        FollowStatus status = tracker.followStatus().orElseThrow();
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
        WorldModel tracker = follower(WorldModel.DEFAULT_MEMORY_TTL);
        tracker.lockRequested(trackIdLock(1, 5));

        tracker.accept(bound(T0, 5, tracked("person", 5, TrackState.CONFIRMED, DetectionSource.DETECTOR)), List.of());

        FollowStatus status = tracker.followStatus().orElseThrow();
        assertEquals(FollowState.HOLDING, status.state());
        assertEquals(T0, status.since());
    }

    @Test
    void coastingWhenNewestTrackRefIsTrackerSourced() {
        WorldModel tracker = holdingTracker();

        Instant t2 = T0.plusSeconds(2);
        tracker.accept(bound(t2, 5, tracked("person", 5, TrackState.COASTING, DetectionSource.TRACKER)), List.of());

        FollowStatus status = tracker.followStatus().orElseThrow();
        assertEquals(FollowState.COASTING, status.state());
        assertEquals(5L, status.trackId());
        assertEquals("person", status.label());
        assertEquals(t2, status.since(), "since restamps on every state transition, not only on (re)bind");
        assertEquals(t2, status.lastSeenAt());
    }

    @Test
    void holdingResumesAfterCoastingWithoutBeingMistakenForANewBind() {
        WorldModel tracker = holdingTracker();
        Instant t2 = T0.plusSeconds(2);
        tracker.accept(bound(t2, 5, tracked("person", 5, TrackState.COASTING, DetectionSource.TRACKER)), List.of());

        Instant t3 = T0.plusSeconds(3);
        tracker.accept(bound(t3, 5, tracked("person", 5, TrackState.CONFIRMED, DetectionSource.DETECTOR)), List.of());

        FollowStatus status = tracker.followStatus().orElseThrow();
        assertEquals(FollowState.HOLDING, status.state());
        assertEquals(t3, status.since());
        assertEquals(0L, status.recoveredAfterMillis(), "re-confirming after a coast is not a memory recovery");
    }

    @Test
    void stickyOnAFrameWhereTheBoundDetectionWasFilteredOut() {
        WorldModel tracker = holdingTracker();
        Instant t1 = T0.plusSeconds(1);
        FollowStatus before = tracker.followStatus().orElseThrow();

        // lockedTrackId still reports the bind, but the label-filtered result carries no matching
        // Detection for it (StreamPipeline.applyLabelFilters dropped it before this call).
        tracker.accept(bound(t1, 5), List.of());

        FollowStatus status = tracker.followStatus().orElseThrow();
        assertEquals(before.state(), status.state());
        assertEquals(before.label(), status.label());
        assertEquals(before.lastSeenAt(), status.lastSeenAt());
        assertEquals(before.lastBox(), status.lastBox());
        assertEquals(before.since(), status.since());
    }

    // -- LOST -------------------------------------------------------------------------------------

    @Test
    void lostFreezesLastSeenAtAndLastBoxAndKeepsLabelAndTrackId() {
        WorldModel tracker = holdingTracker();
        Instant lastBoundAt = T0.plusSeconds(1);

        Instant lostAt = T0.plusSeconds(2);
        tracker.accept(unbound(lostAt), List.of());

        FollowStatus status = tracker.followStatus().orElseThrow();
        assertEquals(FollowState.LOST, status.state());
        assertEquals(5L, status.trackId(), "trackId stays at its last bound value so re-acquire can resend it");
        assertEquals("person", status.label(), "label is not cleared on loss (D3/D5)");
        assertEquals(lostAt, status.since());
        assertEquals(lastBoundAt, status.lastSeenAt(), "lastSeenAt freezes, it does not advance to lostAt");

        Instant stillLostAt = T0.plusSeconds(30);
        tracker.accept(unbound(stillLostAt), List.of());

        FollowStatus stillLost = tracker.followStatus().orElseThrow();
        assertEquals(FollowState.LOST, stillLost.state());
        assertEquals(lostAt, stillLost.since(), "since does not restamp while still LOST");
        assertEquals(lastBoundAt, stillLost.lastSeenAt(), "lastSeenAt/lastBox stay frozen across further LOST frames");
        assertEquals(status.lastBox(), stillLost.lastBox());
    }

    // -- RELEASED ---------------------------------------------------------------------------------

    @Test
    void releaseDropsFollowStatusToEmpty() {
        WorldModel tracker = holdingTracker();

        tracker.lockRequested(releaseLock(2));

        assertTrue(tracker.followStatus().isEmpty());
    }

    @Test
    void releaseWhileLostAlsoDropsToEmpty() {
        WorldModel tracker = holdingTracker();
        tracker.accept(unbound(T0.plusSeconds(2)), List.of());
        assertEquals(FollowState.LOST, tracker.followStatus().orElseThrow().state());

        tracker.lockRequested(releaseLock(2));

        assertTrue(tracker.followStatus().isEmpty());
    }

    @Test
    void afterReleaseAFurtherUnboundFrameStaysEmptyNotRequesting() {
        WorldModel tracker = holdingTracker();
        tracker.lockRequested(releaseLock(2));

        tracker.accept(unbound(T0.plusSeconds(5)), List.of());

        assertTrue(tracker.followStatus().isEmpty());
    }

    // -- recovery (L4) ------------------------------------------------------------------------------

    @Test
    void coastThenRecoverKeepsTheSameTrackIdAndLabel() {
        WorldModel tracker = follower(WorldModel.DEFAULT_MEMORY_TTL);
        tracker.lockRequested(trackIdLock(1, 9));

        Instant t1 = T0.plusSeconds(1);
        tracker.accept(bound(t1, 9, tracked("dog", 9, TrackState.CONFIRMED, DetectionSource.DETECTOR)), List.of());
        Instant t2 = T0.plusSeconds(2);
        tracker.accept(bound(t2, 9, tracked("dog", 9, TrackState.COASTING, DetectionSource.TRACKER)), List.of());
        assertEquals(FollowState.COASTING, tracker.followStatus().orElseThrow().state());

        Instant lostAt = T0.plusSeconds(3);
        tracker.accept(unbound(lostAt), List.of());
        FollowStatus lost = tracker.followStatus().orElseThrow();
        assertEquals(FollowState.LOST, lost.state());
        assertEquals(9L, lost.trackId());
        assertEquals("dog", lost.label());

        Instant recoveredAt = T0.plusSeconds(11);
        tracker.accept(bound(recoveredAt, 9, recovered("dog", 9, DetectionSource.DETECTOR, 0.71, 8200)), List.of());

        FollowStatus recoveredStatus = tracker.followStatus().orElseThrow();
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
        WorldModel tracker = follower(WorldModel.DEFAULT_MEMORY_TTL);
        tracker.lockRequested(trackIdLock(1, 5));

        tracker.accept(bound(T0, 5, tracked("person", 5, TrackState.CONFIRMED, DetectionSource.DETECTOR)), List.of());

        FollowStatus status = tracker.followStatus().orElseThrow();
        assertEquals(0L, status.recoveredAfterMillis());
        assertEquals(0.0, status.recoveryConfidence());
    }

    // -- reacquirable --------------------------------------------------------------------------------

    @Test
    void reacquirableTrueWithinMemoryTtlForATrackIdLock() {
        WorldModel tracker = follower(Duration.ofSeconds(30));
        tracker.lockRequested(trackIdLock(1, 5));
        tracker.accept(bound(T0, 5, tracked("person", 5, TrackState.CONFIRMED, DetectionSource.DETECTOR)), List.of());

        tracker.accept(unbound(T0.plusSeconds(5)), List.of());

        assertTrue(tracker.followStatus().orElseThrow().reacquirable());
    }

    @Test
    void reacquirableFalseOnceMemoryTtlElapses() {
        WorldModel tracker = follower(Duration.ofSeconds(30));
        tracker.lockRequested(trackIdLock(1, 5));
        tracker.accept(bound(T0, 5, tracked("person", 5, TrackState.CONFIRMED, DetectionSource.DETECTOR)), List.of());

        tracker.accept(unbound(T0.plusSeconds(31)), List.of());

        assertFalse(tracker.followStatus().orElseThrow().reacquirable());
    }

    @Test
    void reacquirableFalseForAPointFormLockEvenImmediatelyAfterLoss() {
        WorldModel tracker = follower(Duration.ofSeconds(30));
        tracker.lockRequested(pointLock(1));
        tracker.accept(bound(T0, 5, tracked("person", 5, TrackState.CONFIRMED, DetectionSource.DETECTOR)), List.of());

        tracker.accept(unbound(T0.plusSeconds(1)), List.of());

        assertFalse(tracker.followStatus().orElseThrow().reacquirable());
    }

    @Test
    void reacquirableFalseWhileHoldingOrCoasting() {
        WorldModel tracker = holdingTracker();
        assertFalse(tracker.followStatus().orElseThrow().reacquirable());
    }

    // -- clear (model re-arm / gate close) --------------------------------------------------------

    @Test
    void clearResetsToNeverRequested() {
        WorldModel tracker = holdingTracker();

        tracker.clear();

        assertTrue(tracker.followStatus().isEmpty());

        // requestPending must also have been reset -- otherwise the next unbound frame would
        // wrongly resurrect REQUESTING for a lock this pipeline no longer remembers.
        tracker.accept(unbound(T0.plusSeconds(9)), List.of());
        assertTrue(tracker.followStatus().isEmpty());
    }

    // -- all five states enumerated end-to-end ------------------------------------------------------

    @Test
    void allFiveStatesAreReachableInSequence() {
        WorldModel tracker = follower(WorldModel.DEFAULT_MEMORY_TTL);

        tracker.lockRequested(trackIdLock(1, 5));
        tracker.accept(unbound(T0), List.of());
        assertEquals(FollowState.REQUESTING, tracker.followStatus().orElseThrow().state());

        Instant t1 = T0.plusSeconds(1);
        tracker.accept(bound(t1, 5, tracked("person", 5, TrackState.CONFIRMED, DetectionSource.DETECTOR)), List.of());
        assertEquals(FollowState.HOLDING, tracker.followStatus().orElseThrow().state());

        Instant t2 = T0.plusSeconds(2);
        tracker.accept(bound(t2, 5, tracked("person", 5, TrackState.COASTING, DetectionSource.TRACKER)), List.of());
        assertEquals(FollowState.COASTING, tracker.followStatus().orElseThrow().state());

        Instant t3 = T0.plusSeconds(3);
        tracker.accept(unbound(t3), List.of());
        assertEquals(FollowState.LOST, tracker.followStatus().orElseThrow().state());

        tracker.lockRequested(releaseLock(2));
        assertTrue(tracker.followStatus().isEmpty(), "RELEASED is a transition, not an observable resting state");
    }

    private WorldModel holdingTracker() {
        WorldModel tracker = follower(WorldModel.DEFAULT_MEMORY_TTL);
        tracker.lockRequested(trackIdLock(1, 5));
        tracker.accept(unbound(T0), List.of());
        tracker.accept(bound(T0.plusSeconds(1), 5, tracked("person", 5, TrackState.CONFIRMED, DetectionSource.DETECTOR)), List.of());
        return tracker;
    }
}
