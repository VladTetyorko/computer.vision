package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.perception.domain.model.CameraAttitude;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.DetectionSource;
import com.drones.vision.perception.domain.model.DetectorReason;
import com.drones.vision.perception.domain.model.EventRuleConfig;
import com.drones.vision.perception.domain.model.EvidenceSource;
import com.drones.vision.perception.domain.model.FrameLedger;
import com.drones.vision.perception.domain.model.LedgerOutcome;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.perception.domain.model.ObjectLifecycle;
import com.drones.vision.perception.domain.model.ObjectState;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.perception.domain.model.PixelFormat;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.TrackRef;
import com.drones.vision.perception.domain.model.TrackState;
import com.drones.vision.perception.domain.model.TrackingConfig;
import com.drones.vision.perception.domain.model.TrackingTelemetry;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.proto.v1.BoundingBox;
import com.drones.vision.proto.v1.CameraPose;
import com.drones.vision.proto.v1.DetectionResponse;
import com.drones.vision.proto.v1.FrameRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure {@link DetectionFrameCodec#decode} mapping-logic tests — no gRPC, no network — covering
 * docs/plans/done/TRACKING-PLAN.md §4.A/§4.B track-field decoding and docs/extracts/TRACKING-ORCHESTRATION.md §5.2's
 * {@link TrackingTelemetry} gap fix. {@link DetectionFrameCodec#encode}'s wire-capture behavior
 * (including {@code TrackingConfig}/{@code TargetLock}) is covered end-to-end in
 * {@link GrpcDetectionPortTest} instead, since "captured server-side on the wire" needs a real gRPC
 * round trip to prove.
 */
class DetectionFrameCodecTest {

    private static final StreamId STREAM_ID = StreamId.random();
    private static final Instant CAPTURED_AT = Instant.ofEpochMilli(1_000);

    private static com.drones.vision.proto.v1.Detection.Builder detectionBuilder() {
        BoundingBox box = BoundingBox.newBuilder().setX(0.1f).setY(0.2f).setWidth(0.3f).setHeight(0.4f).build();
        return com.drones.vision.proto.v1.Detection.newBuilder()
                .setLabel("person").setConfidence(0.87f).setBox(box);
    }

    private static DetectionResponse.Builder responseBuilder() {
        return DetectionResponse.newBuilder()
                .setStreamId(STREAM_ID.value().toString())
                .setSequence(5)
                .setTimestampMillis(CAPTURED_AT.toEpochMilli())
                .setModelId("yolo26n.pt")
                .setModelVersion("latest")
                .setInferenceMillis(10);
    }

    // --- encode: camera pose (docs/conclusions/CV-RATE-BUDGET.md §5, gap 3) -------------------

    private static VideoFrame smallBgrFrame() {
        // 2x2 BGR24, well under detectWidth so encode takes the no-downscale path.
        return new VideoFrame(STREAM_ID, 7L, CAPTURED_AT, 2, 2, PixelFormat.BGR24,
                ByteBuffer.wrap(new byte[2 * 2 * 3]));
    }

    private static DetectionFrameCodec codec() {
        return new DetectionFrameCodec(640, 0.8f);
    }

    @Test
    void encodeStampsTheCameraPoseWhenAnAttitudeIsKnown() throws Exception {
        CameraAttitude attitude = new CameraAttitude(137.5, -12.0, 3.0, 62.0, 35.0,
                Instant.ofEpochMilli(1_234));

        FrameRequest request = codec().encode(smallBgrFrame(), PipelineConfig.defaults(), attitude);

        assertTrue(request.hasCameraPose());
        CameraPose pose = request.getCameraPose();
        assertEquals(137.5f, pose.getYawDegrees(), 1e-4);
        assertEquals(-12.0f, pose.getPitchDegrees(), 1e-4);
        assertEquals(3.0f, pose.getRollDegrees(), 1e-4);
        assertEquals(62.0f, pose.getHfovDegrees(), 1e-4);
        assertEquals(35.0f, pose.getVfovDegrees(), 1e-4);
        assertEquals(1_234L, pose.getPoseTimestampMillis());
    }

    @Test
    void encodeSetsNoCameraPoseWithoutAnAttitude() throws Exception {
        FrameRequest request = codec().encode(smallBgrFrame(), PipelineConfig.defaults(), null);

        // Absent, not zeroed: "absent = flow-based compensation only" is the wire's own contract.
        assertFalse(request.hasCameraPose());
    }

    @Test
    void encodeSetsNoCameraPoseWhenTheFieldOfViewIsUnknown() throws Exception {
        CameraAttitude unusable = CameraAttitude.ofYaw(137.5, 0.0, Instant.ofEpochMilli(1_234));

        FrameRequest request = codec().encode(smallBgrFrame(), PipelineConfig.defaults(), unusable);

        // cv-service would ignore an hfov-less pose anyway (CameraPose.known); sending one would
        // cost a submessage per frame for nothing.
        assertFalse(request.hasCameraPose());
    }

    @Test
    void theTwoArgEncodeRemainsPoseFree() throws Exception {
        FrameRequest request = codec().encode(smallBgrFrame(), PipelineConfig.defaults());

        assertFalse(request.hasCameraPose());
    }

    @Test
    void trackIdZeroDecodesToUntrackedDetectionRegardlessOfOtherTrackFields() {
        // track_id == 0 must win even when the other track fields look populated -- 0 is the wire's
        // untracked sentinel and must never reach TrackRef as a guessed trackId (TRACKING-PLAN §4.A,
        // TRACKING-ORCHESTRATION §6 rule 2).
        com.drones.vision.proto.v1.Detection wireDetection = detectionBuilder()
                .setTrackId(0)
                .setTrackState(com.drones.vision.proto.v1.TrackState.TRACK_STATE_CONFIRMED)
                .setSource(com.drones.vision.proto.v1.DetectionSource.DETECTION_SOURCE_DETECTOR)
                .setVelocityX(0.01f)
                .setVelocityY(-0.02f)
                .setTrackAgeFrames(50)
                .build();
        DetectionResponse response = responseBuilder().addDetections(wireDetection).build();

        DetectionResult result = DetectionFrameCodec.decode(STREAM_ID, response);

        assertEquals(1, result.detections().size());
        assertNull(result.detections().get(0).track());
    }

    @Test
    void responseWithNoTrackFieldsAtAllDecodesByteIdenticalToPreTrackingBehavior() {
        // The shape an old, pre-tracking server's response has: none of the five per-frame tracking
        // fields ever touched, and a detection with no track fields touched either. Must decode
        // exactly as this codec did before this wave -- tracking() null, detections' track() null.
        com.drones.vision.proto.v1.Detection wireDetection = detectionBuilder().build();
        DetectionResponse response = responseBuilder().addDetections(wireDetection).build();

        DetectionResult result = DetectionFrameCodec.decode(STREAM_ID, response);

        assertNull(result.tracking());
        assertEquals(1, result.detections().size());
        assertNull(result.detections().get(0).track());
        // and the rest of the mapping is unaffected by any of this wave's changes
        assertEquals(STREAM_ID, result.streamId());
        assertEquals(5L, result.frameSequence());
        assertEquals(CAPTURED_AT, result.capturedAt());
        assertEquals(Duration.ofMillis(10), result.inferenceLatency());
        assertEquals("person", result.detections().get(0).label());
    }

    @Test
    void responseWithNoDetectionsAndNoTrackFieldsDecodesTrackingNull() {
        DetectionResponse response = responseBuilder().build();

        DetectionResult result = DetectionFrameCodec.decode(STREAM_ID, response);

        assertNull(result.tracking());
        assertEquals(0, result.detections().size());
    }

    @Test
    void perFrameTelemetryOnADetectorVerifyPassRoundTripsIntoTrackingTelemetry() {
        DetectionResponse response = responseBuilder()
                .setDetectorRan(true)
                .setDetectorReason(com.drones.vision.proto.v1.DetectorReason.DETECTOR_REASON_CADENCE)
                .setTrackerMillis(0)
                .setTrackerEngineId("lk")
                .setLockedTrackId(7)
                .build();

        DetectionResult result = DetectionFrameCodec.decode(STREAM_ID, response);

        TrackingTelemetry tracking = result.tracking();
        assertEquals(true, tracking.detectorRan());
        assertEquals(DetectorReason.CADENCE, tracking.reason());
        assertEquals(Duration.ZERO, tracking.trackerLatency());
        assertEquals("lk", tracking.engineId());
        assertEquals(7L, tracking.lockedTrackId());
    }

    @Test
    void perFrameTelemetryOnATrackerOnlyFrameRoundTripsWithNullReason() {
        // FOLLOW mode, the common case: no detector pass, so detector_reason is meaningless and
        // stays UNSPECIFIED on the wire -- must decode to a null domain reason, not a guess.
        DetectionResponse response = responseBuilder()
                .setDetectorRan(false)
                .setTrackerMillis(1)
                .setTrackerEngineId("lk")
                .setLockedTrackId(7)
                .build();

        DetectionResult result = DetectionFrameCodec.decode(STREAM_ID, response);

        TrackingTelemetry tracking = result.tracking();
        assertEquals(false, tracking.detectorRan());
        assertNull(tracking.reason());
        assertEquals(Duration.ofMillis(1), tracking.trackerLatency());
        assertEquals("lk", tracking.engineId());
        assertEquals(7L, tracking.lockedTrackId());
    }

    @Test
    void responseWithNoPullFieldsDecodesPullTelemetryNull() {
        DetectionResponse response = responseBuilder().build();

        DetectionResult result = DetectionFrameCodec.decode(STREAM_ID, response);

        assertNull(result.pullTelemetry());
    }

    @Test
    void pullModeDiagnosticFieldsRoundTripIntoPullTelemetry() {
        DetectionResponse response = responseBuilder()
                .setDecodeMillis(3)
                .setSourceFps(9.9f)
                .setAchievedFps(9.5f)
                .setDroppedFrames(2)
                .setMissedDeadlines(1)
                .setCaptureSkewMillis(12)
                .build();

        DetectionResult result = DetectionFrameCodec.decode(STREAM_ID, response);

        com.drones.vision.perception.domain.model.PullTelemetry pullTelemetry = result.pullTelemetry();
        assertEquals(3L, pullTelemetry.decodeMillis());
        assertEquals(9.9f, pullTelemetry.sourceFps(), 1e-6);
        assertEquals(9.5f, pullTelemetry.achievedFps(), 1e-6);
        assertEquals(2L, pullTelemetry.droppedFrames());
        assertEquals(1L, pullTelemetry.missedDeadlines());
        assertEquals(12L, pullTelemetry.captureSkewMillis());
    }

    @Test
    void trackedDetectionMapsAllSevenTrackRefFieldsIncludingReupdated() {
        // docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md §2: Detection.reupdated (wire field 14) is the
        // sixth-turned-seventh TrackRef field, mapped alongside the original six.
        com.drones.vision.proto.v1.Detection wireDetection = detectionBuilder()
                .setTrackId(3)
                .setTrackState(com.drones.vision.proto.v1.TrackState.TRACK_STATE_COASTING)
                .setSource(com.drones.vision.proto.v1.DetectionSource.DETECTION_SOURCE_TRACKER)
                .setVelocityX(0.05f)
                .setVelocityY(-0.01f)
                .setTrackAgeFrames(12)
                .setReupdated(true)
                .build();
        DetectionResponse response = responseBuilder().addDetections(wireDetection).build();

        Detection detection = DetectionFrameCodec.decode(STREAM_ID, response).detections().get(0);

        TrackRef track = detection.track();
        assertEquals(3L, track.trackId());
        assertEquals(TrackState.COASTING, track.state());
        assertEquals(DetectionSource.TRACKER, track.source());
        assertEquals(0.05, track.velocityX(), 1e-6);
        assertEquals(-0.01, track.velocityY(), 1e-6);
        assertEquals(12, track.ageFrames());
        assertTrue(track.reupdated());
    }

    @Test
    void reupdatedFalseByDefaultWhenNotStatedOnTheWire() {
        // The pre-V3 shape: reupdated left untouched on the wire builder defaults to false, matching
        // this codec's byte-identical-when-absent behavior for every other new V3 field.
        com.drones.vision.proto.v1.Detection wireDetection = detectionBuilder()
                .setTrackId(3)
                .setTrackState(com.drones.vision.proto.v1.TrackState.TRACK_STATE_CONFIRMED)
                .setSource(com.drones.vision.proto.v1.DetectionSource.DETECTION_SOURCE_DETECTOR)
                .build();
        DetectionResponse response = responseBuilder().addDetections(wireDetection).build();

        TrackRef track = DetectionFrameCodec.decode(STREAM_ID, response).detections().get(0).track();

        assertFalse(track.reupdated());
    }

    @Test
    void aRecoveredDetectionRoundTripsIdentityConfidenceAndDormantMillis() {
        // docs/plans/active/TRACK-FOLLOW-PLAN.md §3.1/W1, D11: identity_confidence (field 10) and
        // dormant_millis (field 11) are cv-service's own statement that this bind came back from
        // follow memory -- decoded verbatim, never re-derived from lockedTrackId bouncing.
        com.drones.vision.proto.v1.Detection wireDetection = detectionBuilder()
                .setTrackId(3)
                .setTrackState(com.drones.vision.proto.v1.TrackState.TRACK_STATE_CONFIRMED)
                .setSource(com.drones.vision.proto.v1.DetectionSource.DETECTION_SOURCE_TRACKER)
                .setIdentityConfidence(0.71f)
                .setDormantMillis(8200)
                .build();
        DetectionResponse response = responseBuilder().addDetections(wireDetection).build();

        TrackRef track = DetectionFrameCodec.decode(STREAM_ID, response).detections().get(0).track();

        assertEquals(0.71, track.identityConfidence(), 1e-6);
        assertEquals(8200L, track.dormantMillis());
    }

    @Test
    void aNonRecoveredDetectionDecodesIdentityConfidenceAndDormantMillisAsZero() {
        // The common case, and a pre-L4 server's only possible shape: fields 10/11 never touched on
        // the wire, so the honest answer is "not a recovery" -- 0/0.0, never guessed otherwise.
        com.drones.vision.proto.v1.Detection wireDetection = detectionBuilder()
                .setTrackId(3)
                .setTrackState(com.drones.vision.proto.v1.TrackState.TRACK_STATE_CONFIRMED)
                .setSource(com.drones.vision.proto.v1.DetectionSource.DETECTION_SOURCE_DETECTOR)
                .build();
        DetectionResponse response = responseBuilder().addDetections(wireDetection).build();

        TrackRef track = DetectionFrameCodec.decode(STREAM_ID, response).detections().get(0).track();

        assertEquals(0.0, track.identityConfidence());
        assertEquals(0L, track.dormantMillis());
    }

    @Test
    void unspecifiedTrackStateOnATrackedDetectionYieldsNoTrackRefRatherThanAGuess() {
        com.drones.vision.proto.v1.Detection wireDetection = detectionBuilder()
                .setTrackId(9)
                .setTrackState(com.drones.vision.proto.v1.TrackState.TRACK_STATE_UNSPECIFIED)
                .setSource(com.drones.vision.proto.v1.DetectionSource.DETECTION_SOURCE_DETECTOR)
                .build();
        DetectionResponse response = responseBuilder().addDetections(wireDetection).build();

        Detection detection = DetectionFrameCodec.decode(STREAM_ID, response).detections().get(0);

        assertNull(detection.track());
    }

    @Test
    void unspecifiedDetectionSourceOnATrackedDetectionYieldsNoTrackRefRatherThanAGuess() {
        com.drones.vision.proto.v1.Detection wireDetection = detectionBuilder()
                .setTrackId(9)
                .setTrackState(com.drones.vision.proto.v1.TrackState.TRACK_STATE_CONFIRMED)
                .setSource(com.drones.vision.proto.v1.DetectionSource.DETECTION_SOURCE_UNSPECIFIED)
                .build();
        DetectionResponse response = responseBuilder().addDetections(wireDetection).build();

        Detection detection = DetectionFrameCodec.decode(STREAM_ID, response).detections().get(0);

        assertNull(detection.track());
    }

    @Test
    void malformedNonFiniteVelocityOnATrackedDetectionThrowsAndDoesNotSilentlySkip() {
        // A per-detection malformed track field: TrackRef requires finite velocities. This proves
        // decode() surfaces the malformed field as an exception rather than silently dropping it or
        // guessing a value -- DetectionStreamSession#onResponse turns this into a failure of only
        // that one frame's future (see GrpcDetectionPortTest's end-to-end equivalent).
        com.drones.vision.proto.v1.Detection wireDetection = detectionBuilder()
                .setTrackId(5)
                .setTrackState(com.drones.vision.proto.v1.TrackState.TRACK_STATE_CONFIRMED)
                .setSource(com.drones.vision.proto.v1.DetectionSource.DETECTION_SOURCE_DETECTOR)
                .setVelocityX(Float.NaN)
                .build();
        DetectionResponse response = responseBuilder().addDetections(wireDetection).build();

        assertThrows(IllegalArgumentException.class, () -> DetectionFrameCodec.decode(STREAM_ID, response));
    }

    // --- decode: V3 capability ladder + ORU (docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md §2, wave J2) ---

    @Test
    void perFrameTelemetryRoundTripsTheFiveV3Fields() {
        // reupdate_millis/reupdated_tracks/detection_lag_millis map onto TrackingTelemetry's
        // reupdateLatency/reupdatedTracks/detectionLag; capability_level_served+capability_level_reason
        // bundle onto TrackingTelemetry.capability() as one TrackingCapability, not two loose scalars.
        DetectionResponse response = responseBuilder()
                .setDetectorRan(true)
                .setDetectorReason(com.drones.vision.proto.v1.DetectorReason.DETECTOR_REASON_CADENCE)
                .setReupdateMillis(15)
                .setReupdatedTracks(2)
                .setDetectionLagMillis(30)
                .setCapabilityLevelServed(3)
                .setCapabilityLevelReason("thermal_throttle")
                .build();

        TrackingTelemetry tracking = DetectionFrameCodec.decode(STREAM_ID, response).tracking();

        assertEquals(Duration.ofMillis(15), tracking.reupdateLatency());
        assertEquals(2, tracking.reupdatedTracks());
        assertEquals(Duration.ofMillis(30), tracking.detectionLag());
        assertEquals(3, tracking.capability().levelServed());
        assertEquals("thermal_throttle", tracking.capability().reason());
    }

    @Test
    void allZeroResponseIncludingV3FieldsStillDecodesTrackingNull() {
        // B3: absent must stay absent. Every V3 field explicitly stated at its proto zero-value (not
        // merely left unset) still decodes tracking() == null -- this extends, rather than sits beside,
        // the same all-zero-means-absent check toTrackingTelemetry already applies to the five
        // pre-V3 fields. This is the shape a pre-V3 cv-service's response has.
        DetectionResponse response = responseBuilder()
                .setDetectorRan(false)
                .setDetectorReason(com.drones.vision.proto.v1.DetectorReason.DETECTOR_REASON_UNSPECIFIED)
                .setTrackerMillis(0)
                .setTrackerEngineId("")
                .setLockedTrackId(0)
                .setReupdateMillis(0)
                .setReupdatedTracks(0)
                .setDetectionLagMillis(0)
                .setCapabilityLevelServed(0)
                .setCapabilityLevelReason("")
                .build();

        DetectionResult result = DetectionFrameCodec.decode(STREAM_ID, response);

        assertNull(result.tracking());
    }

    @Test
    void capabilityLevelServedZeroDecodesCapabilityNullEvenWhileTrackingIsActive() {
        // capability_level_served == 0 is not a level, it is "this server never reported one" --
        // TrackingCapability validates levelServed in [1,5], so constructing one from 0 would throw.
        // Distinct from the all-zero B3 case above: detector_ran=true keeps tracking() itself non-null,
        // isolating that only capability() collapses to null on a zero level.
        DetectionResponse response = responseBuilder()
                .setDetectorRan(true)
                .setDetectorReason(com.drones.vision.proto.v1.DetectorReason.DETECTOR_REASON_ALWAYS)
                .setCapabilityLevelServed(0)
                .build();

        TrackingTelemetry tracking = DetectionFrameCodec.decode(STREAM_ID, response).tracking();

        assertNull(tracking.capability());
    }

    @Test
    void servedLevelBelowWhateverWasRequestedSurvivesDecodeIntact() {
        // B5: a downgrade is exactly the case this wave exists to make visible. decode() has no
        // access to the request's ceiling (only DetectionResponse is in scope here) and must not
        // clamp, raise, or otherwise second-guess whatever level the server reports served --
        // a low served level (simulating a downgrade from a higher requested ceiling) must reach the
        // domain exactly as reported.
        DetectionResponse response = responseBuilder()
                .setDetectorRan(true)
                .setDetectorReason(com.drones.vision.proto.v1.DetectorReason.DETECTOR_REASON_ALWAYS)
                .setCapabilityLevelServed(2)
                .setCapabilityLevelReason("cpu_only_host")
                .build();

        TrackingTelemetry tracking = DetectionFrameCodec.decode(STREAM_ID, response).tracking();

        assertEquals(2, tracking.capability().levelServed());
        assertEquals("cpu_only_host", tracking.capability().reason());
    }

    @Test
    void malformedDetectorRanTrueWithUnspecifiedReasonThrowsRatherThanGuessingAlways() {
        // detector_reason is meaningful only when detector_ran is true (TRACKING-PLAN §4.G). A
        // response claiming the detector ran but giving no reason is a contract violation, not
        // something to default to ALWAYS -- TrackingTelemetry's own compact ctor rejects it.
        DetectionResponse response = responseBuilder()
                .setDetectorRan(true)
                .setDetectorReason(com.drones.vision.proto.v1.DetectorReason.DETECTOR_REASON_UNSPECIFIED)
                .build();

        assertThrows(IllegalArgumentException.class, () -> DetectionFrameCodec.decode(STREAM_ID, response));
    }

    // --- decode: the per-identity mirror (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.5, wave W1 step 4) ---

    /** One wire {@code ObjectState} with every group present and every leaf distinct, lifecycle DORMANT. */
    private static com.drones.vision.proto.v1.ObjectState fullWireObjectState(long id) {
        com.drones.vision.proto.v1.BoundingBox box = com.drones.vision.proto.v1.BoundingBox.newBuilder()
                .setX(0.10f).setY(0.20f).setWidth(0.30f).setHeight(0.40f).build();
        com.drones.vision.proto.v1.BoundingBox detectorBox = com.drones.vision.proto.v1.BoundingBox.newBuilder()
                .setX(0.11f).setY(0.21f).setWidth(0.31f).setHeight(0.41f).build();
        com.drones.vision.proto.v1.BoundingBox trackerBox = com.drones.vision.proto.v1.BoundingBox.newBuilder()
                .setX(0.12f).setY(0.22f).setWidth(0.32f).setHeight(0.42f).build();
        com.drones.vision.proto.v1.BoundingBox predictedBox = com.drones.vision.proto.v1.BoundingBox.newBuilder()
                .setX(0.13f).setY(0.23f).setWidth(0.33f).setHeight(0.43f).build();

        com.drones.vision.proto.v1.ObjectState.Identity identity = com.drones.vision.proto.v1.ObjectState.Identity
                .newBuilder()
                .setLabel("person").setLabelRaw("person-raw").setStability(4)
                .addCandidates(com.drones.vision.proto.v1.ObjectState.LabelCandidate.newBuilder()
                        .setLabel("person").setWeight(0.9f).build())
                .addCandidates(com.drones.vision.proto.v1.ObjectState.LabelCandidate.newBuilder()
                        .setLabel("bicycle").setWeight(0.1f).build())
                .build();

        com.drones.vision.proto.v1.ObjectState.Kinematics kinematics = com.drones.vision.proto.v1.ObjectState.Kinematics
                .newBuilder()
                .setBox(box).setDetectorBox(detectorBox).setTrackerBox(trackerBox).setPredictedBox(predictedBox)
                .setHorizonMs(120).setVelocityX(0.05f).setVelocityY(-0.03f)
                .setDisplacementX(0.02f).setDisplacementY(-0.01f).setMotionCompensated(true)
                .build();

        com.drones.vision.proto.v1.ObjectState.Belief belief = com.drones.vision.proto.v1.ObjectState.Belief
                .newBuilder()
                .setConfidenceRaw(0.8f).setConfidenceSmoothed(0.75f).setExistence(0.9f).setSinceConfirmedMs(500)
                .build();

        com.drones.vision.proto.v1.ObjectState.Provenance provenance = com.drones.vision.proto.v1.ObjectState.Provenance
                .newBuilder()
                .setSource(com.drones.vision.proto.v1.EvidenceSource.EVIDENCE_SOURCE_TRACKER)
                .addContributors("assoc.cost").addContributors("detect.full")
                .setAssocCost(1.25f).setReupdated(true)
                .build();

        com.drones.vision.proto.v1.ObjectState.Memory memory = com.drones.vision.proto.v1.ObjectState.Memory
                .newBuilder()
                .setRecovered(true).setIdentityConfidence(0.66f).setDormantMs(9000)
                .setGalleryMatches(3).setMatchDistance(0.2f)
                .build();

        com.drones.vision.proto.v1.ObjectState.Lock lock = com.drones.vision.proto.v1.ObjectState.Lock.newBuilder()
                .setLocked(true).setLockSeqApplied(7)
                .build();

        com.drones.vision.proto.v1.ObjectState.Timing timing = com.drones.vision.proto.v1.ObjectState.Timing
                .newBuilder()
                .setFirstSeenMs(1000).setLastSeenMs(2000).setLastConfirmedMs(1800)
                .setAgeFrames(30).setHits(20).setMisses(2)
                .build();

        return com.drones.vision.proto.v1.ObjectState.newBuilder()
                .setId(id)
                .setLifecycle(com.drones.vision.proto.v1.ObjectLifecycle.OBJECT_LIFECYCLE_DORMANT)
                .setStreamId(STREAM_ID.value().toString())
                .setIdentity(identity)
                .setKinematics(kinematics)
                .setBelief(belief)
                .setProvenance(provenance)
                .setMemory(memory)
                .setLock(lock)
                .setTiming(timing)
                .build();
    }

    @Test
    void everyObjectStateGroupRoundTripsWithDistinctValuesForADormantObject() {
        DetectionResponse response = responseBuilder().addObjects(fullWireObjectState(11)).build();

        List<ObjectState> objects = DetectionFrameCodec.decode(STREAM_ID, response).objects();

        assertEquals(1, objects.size());
        ObjectState state = objects.get(0);
        assertEquals(11L, state.id());
        assertEquals(ObjectLifecycle.DORMANT, state.lifecycle());
        assertEquals(STREAM_ID, state.streamId());

        assertEquals("person", state.identity().label());
        assertEquals("person-raw", state.identity().labelRaw());
        assertEquals(2, state.identity().candidates().size());
        assertEquals("person", state.identity().candidates().get(0).label());
        assertEquals(0.9, state.identity().candidates().get(0).weight(), 1e-6);
        assertEquals("bicycle", state.identity().candidates().get(1).label());
        assertEquals(0.1, state.identity().candidates().get(1).weight(), 1e-6);
        assertEquals(4, state.identity().stability());

        assertEquals(0.10, state.kinematics().box().x(), 1e-6);
        assertEquals(0.20, state.kinematics().box().y(), 1e-6);
        assertEquals(0.11, state.kinematics().detectorBox().x(), 1e-6);
        assertEquals(0.12, state.kinematics().trackerBox().x(), 1e-6);
        assertEquals(0.13, state.kinematics().predictedBox().x(), 1e-6);
        assertEquals(120L, state.kinematics().horizonMillis());
        assertEquals(0.05, state.kinematics().velocityX(), 1e-6);
        assertEquals(-0.03, state.kinematics().velocityY(), 1e-6);
        assertEquals(0.02, state.kinematics().displacementX(), 1e-6);
        assertEquals(-0.01, state.kinematics().displacementY(), 1e-6);
        assertTrue(state.kinematics().motionCompensated());

        assertEquals(0.8, state.belief().confidenceRaw(), 1e-6);
        assertEquals(0.75, state.belief().confidenceSmoothed(), 1e-6);
        assertEquals(0.9, state.belief().existence(), 1e-6);
        assertEquals(500L, state.belief().sinceConfirmedMillis());

        assertEquals(EvidenceSource.TRACKER, state.provenance().source());
        assertEquals(List.of("assoc.cost", "detect.full"), state.provenance().contributors());
        assertEquals(1.25, state.provenance().assocCost(), 1e-6);
        assertTrue(state.provenance().reupdated());

        assertTrue(state.memory().recovered());
        assertEquals(0.66, state.memory().identityConfidence(), 1e-6);
        assertEquals(9000L, state.memory().dormantMillis());
        assertEquals(3, state.memory().galleryMatches());
        assertEquals(0.2, state.memory().matchDistance(), 1e-6);

        assertTrue(state.lock().locked());
        assertEquals(7L, state.lock().lockSeqApplied());

        assertEquals(1000L, state.timing().firstSeenMillis());
        assertEquals(2000L, state.timing().lastSeenMillis());
        assertEquals(1800L, state.timing().lastConfirmedMillis());
        assertEquals(30, state.timing().ageFrames());
        assertEquals(20, state.timing().hits());
        assertEquals(2, state.timing().misses());
    }

    @Test
    void objectStateWithNoGroupsPresentDecodesEveryGroupNullNotZeroValued() {
        com.drones.vision.proto.v1.ObjectState wire = com.drones.vision.proto.v1.ObjectState.newBuilder()
                .setId(3)
                .setLifecycle(com.drones.vision.proto.v1.ObjectLifecycle.OBJECT_LIFECYCLE_TENTATIVE)
                .setStreamId(STREAM_ID.value().toString())
                .build();
        DetectionResponse response = responseBuilder().addObjects(wire).build();

        ObjectState state = DetectionFrameCodec.decode(STREAM_ID, response).objects().get(0);

        assertEquals(3L, state.id());
        assertEquals(ObjectLifecycle.TENTATIVE, state.lifecycle());
        assertNull(state.identity());
        assertNull(state.kinematics());
        assertNull(state.belief());
        assertNull(state.provenance());
        assertNull(state.memory());
        assertNull(state.lock());
        assertNull(state.timing());
    }

    @Test
    void unspecifiedLifecycleDropsThatObjectButKeepsTheOthers() {
        com.drones.vision.proto.v1.ObjectState unspecifiedLifecycle = com.drones.vision.proto.v1.ObjectState
                .newBuilder()
                .setId(1)
                .setStreamId(STREAM_ID.value().toString())
                // lifecycle left unset -> OBJECT_LIFECYCLE_UNSPECIFIED, must drop this one only
                .build();
        com.drones.vision.proto.v1.ObjectState good = com.drones.vision.proto.v1.ObjectState.newBuilder()
                .setId(2)
                .setLifecycle(com.drones.vision.proto.v1.ObjectLifecycle.OBJECT_LIFECYCLE_CONFIRMED)
                .setStreamId(STREAM_ID.value().toString())
                .build();
        DetectionResponse response = responseBuilder().addObjects(unspecifiedLifecycle).addObjects(good).build();

        List<ObjectState> objects = DetectionFrameCodec.decode(STREAM_ID, response).objects();

        assertEquals(1, objects.size());
        assertEquals(2L, objects.get(0).id());
    }

    @Test
    void unspecifiedEvidenceSourceOnAPresentProvenanceDropsThatObjectButKeepsTheOthers() {
        com.drones.vision.proto.v1.ObjectState.Provenance unspecifiedSource = com.drones.vision.proto.v1.ObjectState.Provenance
                .newBuilder()
                // source left unset -> EVIDENCE_SOURCE_UNSPECIFIED
                .build();
        com.drones.vision.proto.v1.ObjectState bad = com.drones.vision.proto.v1.ObjectState.newBuilder()
                .setId(5)
                .setLifecycle(com.drones.vision.proto.v1.ObjectLifecycle.OBJECT_LIFECYCLE_CONFIRMED)
                .setStreamId(STREAM_ID.value().toString())
                .setProvenance(unspecifiedSource)
                .build();
        com.drones.vision.proto.v1.ObjectState good = com.drones.vision.proto.v1.ObjectState.newBuilder()
                .setId(6)
                .setLifecycle(com.drones.vision.proto.v1.ObjectLifecycle.OBJECT_LIFECYCLE_CONFIRMED)
                .setStreamId(STREAM_ID.value().toString())
                .build();
        DetectionResponse response = responseBuilder().addObjects(bad).addObjects(good).build();

        List<ObjectState> objects = DetectionFrameCodec.decode(STREAM_ID, response).objects();

        assertEquals(1, objects.size());
        assertEquals(6L, objects.get(0).id());
    }

    @Test
    void anObjectStateIdOfZeroIsDroppedByItsOwnCompactConstructorButKeepsTheOthers() {
        // 0 is the wire's untracked sentinel (TrackRef's own doctrine); ObjectState.id() must be
        // positive. Proves the generic "any IllegalArgumentException from a nested/compact ctor
        // drops just this object" path, not only the two dedicated enum checks above.
        com.drones.vision.proto.v1.ObjectState zeroId = com.drones.vision.proto.v1.ObjectState.newBuilder()
                .setId(0)
                .setLifecycle(com.drones.vision.proto.v1.ObjectLifecycle.OBJECT_LIFECYCLE_CONFIRMED)
                .setStreamId(STREAM_ID.value().toString())
                .build();
        com.drones.vision.proto.v1.ObjectState good = com.drones.vision.proto.v1.ObjectState.newBuilder()
                .setId(9)
                .setLifecycle(com.drones.vision.proto.v1.ObjectLifecycle.OBJECT_LIFECYCLE_CONFIRMED)
                .setStreamId(STREAM_ID.value().toString())
                .build();
        DetectionResponse response = responseBuilder().addObjects(zeroId).addObjects(good).build();

        List<ObjectState> objects = DetectionFrameCodec.decode(STREAM_ID, response).objects();

        assertEquals(1, objects.size());
        assertEquals(9L, objects.get(0).id());
    }

    @Test
    void objectsListDoesNotAffectDetectionsDecodingWhichStaysByteIdentical() {
        // Wave acceptance criterion: a response carrying objects[] must decode detections[]
        // identically to the same response without objects[].
        com.drones.vision.proto.v1.Detection wireDetection = detectionBuilder()
                .setTrackId(3)
                .setTrackState(com.drones.vision.proto.v1.TrackState.TRACK_STATE_CONFIRMED)
                .setSource(com.drones.vision.proto.v1.DetectionSource.DETECTION_SOURCE_DETECTOR)
                .build();
        DetectionResponse withoutObjects = responseBuilder().addDetections(wireDetection).build();
        DetectionResponse withObjects = responseBuilder().addDetections(wireDetection)
                .addObjects(fullWireObjectState(1))
                .build();

        List<Detection> detectionsWithout = DetectionFrameCodec.decode(STREAM_ID, withoutObjects).detections();
        List<Detection> detectionsWith = DetectionFrameCodec.decode(STREAM_ID, withObjects).detections();

        assertEquals(detectionsWithout, detectionsWith);
    }

    @Test
    void noObjectsFieldDecodesAnEmptyObjectsListNeverNull() {
        DetectionResponse response = responseBuilder().build();

        assertEquals(List.of(), DetectionFrameCodec.decode(STREAM_ID, response).objects());
    }

    // --- encode: trace (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.4) --------------------------

    private static PipelineConfig configWithTrace(boolean trace) {
        return new PipelineConfig(new ModelRef("yolo26n.pt", "latest"), 0.4, 10, 2, Set.of(),
                EventRuleConfig.defaults(), true, TrackingConfig.defaults(), Set.of(), trace);
    }

    @Test
    void encodeStatesTraceFalseFromPipelineConfigByDefault() throws Exception {
        FrameRequest request = codec().encode(smallBgrFrame(), configWithTrace(false));

        assertFalse(request.getTrace());
    }

    @Test
    void encodeStatesTraceTrueWhenPipelineConfigCarriesIt() throws Exception {
        FrameRequest request = codec().encode(smallBgrFrame(), configWithTrace(true));

        assertTrue(request.getTrace());
    }

    // --- decode: ledger (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.4, wave W2) ----------------

    @Test
    void noLedgerFieldDecodesAsAnEmptyOptional() {
        DetectionResponse response = responseBuilder().build();

        assertTrue(DetectionFrameCodec.decode(STREAM_ID, response).ledger().isEmpty());
    }

    @Test
    void presentLedgerRoundTripsIntoTheDomainFrameLedger() {
        com.drones.vision.proto.v1.LedgerEntry entryWire = com.drones.vision.proto.v1.LedgerEntry.newBuilder()
                .setContributorId("detect.full")
                .setOutcome(com.drones.vision.proto.v1.LedgerOutcome.LEDGER_OUTCOME_RAN)
                .setReason("")
                .setCostMs(12.5)
                .putSummary("boxes", "3")
                .build();
        com.drones.vision.proto.v1.ObjectEvidence evidenceWire = com.drones.vision.proto.v1.ObjectEvidence.newBuilder()
                .setContributorId("assoc.cost")
                .putClaim("cost", "0.12")
                .build();
        com.drones.vision.proto.v1.ObjectClaims claimsWire = com.drones.vision.proto.v1.ObjectClaims.newBuilder()
                .addClaims(evidenceWire)
                .build();
        com.drones.vision.proto.v1.FrameLedger ledgerWire = com.drones.vision.proto.v1.FrameLedger.newBuilder()
                .setStreamId(STREAM_ID.value().toString())
                .setSequence(5)
                .setCapturedAtMillis(CAPTURED_AT.toEpochMilli())
                .setLevelServed(2)
                .setDetectorReason("PERIODIC")
                .addEligible("detect")
                .addEligible("assoc")
                .addEntries(entryWire)
                .putObjects(42L, claimsWire)
                .setDropsSinceLast(1)
                .setGateWaitMs(3.0)
                .setTotalMs(15.5)
                .setHalted(false)
                .build();
        DetectionResponse response = responseBuilder().setLedger(ledgerWire).build();

        FrameLedger ledger = DetectionFrameCodec.decode(STREAM_ID, response).ledger().orElseThrow();

        assertEquals(STREAM_ID, ledger.streamId());
        assertEquals(5L, ledger.sequence());
        assertEquals(CAPTURED_AT, ledger.capturedAt());
        assertEquals(2, ledger.levelServed());
        assertEquals("PERIODIC", ledger.detectorReason());
        assertEquals(List.of("detect", "assoc"), ledger.eligible());
        assertEquals(1, ledger.entries().size());
        assertEquals("detect.full", ledger.entries().get(0).contributorId());
        assertEquals(LedgerOutcome.RAN, ledger.entries().get(0).outcome());
        assertEquals(12.5, ledger.entries().get(0).costMillis());
        assertEquals("3", ledger.entries().get(0).summary().get("boxes"));
        assertEquals(1, ledger.objects().size());
        assertEquals("assoc.cost", ledger.objects().get(42L).get(0).contributorId());
        assertEquals("0.12", ledger.objects().get(42L).get(0).claim().get("cost"));
        assertEquals(1, ledger.dropsSinceLast());
        assertEquals(3.0, ledger.gateWaitMillis());
        assertEquals(15.5, ledger.totalMillis());
        assertFalse(ledger.halted());
        // CV-ORCHESTRATION wave W5b: an untraced ledger wire carries no `detections`/`frame_width`/
        // `frame_height` at all -- proto3 zero values, decoded as the domain's own "not carried".
        assertEquals(List.of(), ledger.detections());
        assertEquals(0, ledger.frameWidth());
        assertEquals(0, ledger.frameHeight());
    }

    @Test
    void tracedDetectionsRoundTripAsTheDomainsDetectorBoxes() {
        // CV-ORCHESTRATION wave W5b, decision E23: the detector's own raw boxes for a traced frame,
        // never merged with anything the roi rescue pass or the tracker itself produced.
        com.drones.vision.proto.v1.TracedDetection carWire = com.drones.vision.proto.v1.TracedDetection.newBuilder()
                .setLabel("car")
                .setConfidence(0.81f)
                .setBox(com.drones.vision.proto.v1.BoundingBox.newBuilder()
                        .setX(0.1f).setY(0.2f).setWidth(0.3f).setHeight(0.4f).build())
                .build();
        com.drones.vision.proto.v1.TracedDetection personWire = com.drones.vision.proto.v1.TracedDetection.newBuilder()
                .setLabel("person")
                .setConfidence(0.62f)
                .setBox(com.drones.vision.proto.v1.BoundingBox.newBuilder()
                        .setX(0.5f).setY(0.5f).setWidth(0.1f).setHeight(0.2f).build())
                .build();
        com.drones.vision.proto.v1.FrameLedger ledgerWire = com.drones.vision.proto.v1.FrameLedger.newBuilder()
                .setStreamId(STREAM_ID.value().toString())
                .setSequence(6)
                .setCapturedAtMillis(CAPTURED_AT.toEpochMilli())
                .addDetections(carWire)
                .addDetections(personWire)
                .setFrameWidth(1280)
                .setFrameHeight(720)
                .build();
        DetectionResponse response = responseBuilder().setLedger(ledgerWire).build();

        FrameLedger ledger = DetectionFrameCodec.decode(STREAM_ID, response).ledger().orElseThrow();

        assertEquals(2, ledger.detections().size());
        assertEquals("car", ledger.detections().get(0).label());
        assertEquals(0.81, ledger.detections().get(0).confidence(), 1e-6);
        assertEquals(0.1, ledger.detections().get(0).box().x(), 1e-6);
        assertEquals(0.4, ledger.detections().get(0).box().height(), 1e-6);
        assertEquals("person", ledger.detections().get(1).label());
        assertEquals(0.62, ledger.detections().get(1).confidence(), 1e-6);
        assertEquals(1280, ledger.frameWidth());
        assertEquals(720, ledger.frameHeight());
    }

    @Test
    void malformedLedgerEntryOutcomeDropsTheWholeLedgerButNeverTheFrame() {
        // Unlike objects[] (see unspecifiedLifecycleDropsThatObjectButKeepsTheOthers above), a
        // malformed ledger is a purely diagnostic loss -- the whole ledger drops to empty rather
        // than being salvaged entry-by-entry, and detections[]/objects[] decode unaffected.
        com.drones.vision.proto.v1.LedgerEntry unspecifiedOutcome = com.drones.vision.proto.v1.LedgerEntry
                .newBuilder()
                .setContributorId("detect.full")
                // outcome left unset -> LEDGER_OUTCOME_UNSPECIFIED
                .build();
        com.drones.vision.proto.v1.FrameLedger ledgerWire = com.drones.vision.proto.v1.FrameLedger.newBuilder()
                .setStreamId(STREAM_ID.value().toString())
                .setSequence(5)
                .setCapturedAtMillis(CAPTURED_AT.toEpochMilli())
                .addEntries(unspecifiedOutcome)
                .build();
        com.drones.vision.proto.v1.ObjectState goodObject = com.drones.vision.proto.v1.ObjectState.newBuilder()
                .setId(9)
                .setLifecycle(com.drones.vision.proto.v1.ObjectLifecycle.OBJECT_LIFECYCLE_CONFIRMED)
                .setStreamId(STREAM_ID.value().toString())
                .build();
        DetectionResponse response = responseBuilder().setLedger(ledgerWire).addObjects(goodObject).build();

        DetectionResult result = DetectionFrameCodec.decode(STREAM_ID, response);

        assertTrue(result.ledger().isEmpty());
        assertEquals(1, result.objects().size());
        assertEquals(9L, result.objects().get(0).id());
    }
}
