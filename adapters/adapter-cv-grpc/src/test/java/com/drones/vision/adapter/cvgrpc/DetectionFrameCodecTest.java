package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.domain.model.CameraAttitude;
import com.drones.vision.domain.model.Detection;
import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.model.DetectionSource;
import com.drones.vision.domain.model.DetectorReason;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.PixelFormat;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.TrackRef;
import com.drones.vision.domain.model.TrackState;
import com.drones.vision.domain.model.TrackingTelemetry;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.proto.v1.BoundingBox;
import com.drones.vision.proto.v1.CameraPose;
import com.drones.vision.proto.v1.DetectionResponse;
import com.drones.vision.proto.v1.FrameRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.nio.ByteBuffer;

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
    void trackedDetectionMapsAllSixTrackRefFields() {
        com.drones.vision.proto.v1.Detection wireDetection = detectionBuilder()
                .setTrackId(3)
                .setTrackState(com.drones.vision.proto.v1.TrackState.TRACK_STATE_COASTING)
                .setSource(com.drones.vision.proto.v1.DetectionSource.DETECTION_SOURCE_TRACKER)
                .setVelocityX(0.05f)
                .setVelocityY(-0.01f)
                .setTrackAgeFrames(12)
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
}
