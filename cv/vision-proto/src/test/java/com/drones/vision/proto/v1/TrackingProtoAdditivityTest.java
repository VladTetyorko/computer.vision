package com.drones.vision.proto.v1;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Wave T0 acceptance test (docs/plans/done/TRACKING-PLAN.md §4.A, §7 "T0"), extended by
 * VISUAL-GEO-V2 wave H1 (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.1/§5 H1).
 *
 * <p>Proto3 additive rules require that an old client against a new server (no {@code tracking}
 * sent) and a new client that simply leaves tracking off both produce the exact wire bytes the
 * pre-tracking contract produced. This is not a claim to take on faith: each "expected" payload
 * below is built field-by-field with the raw protobuf wire format ({@code tag = (fieldNumber <<
 * 3) | wireType}), using only the fields that existed in {@code cv.proto} before this wave --
 * independent of {@link FrameRequest}/{@link Detection}'s own serialization. If a new field ever
 * leaked a byte onto the wire while unset (e.g. a message field accidentally defaulted to a
 * non-empty instance), this test would fail rather than the two encodings coincidentally agreeing
 * with each other.
 *
 * <p>H1 added an entirely new, disjoint "Geolocation" service/message section to {@code cv.proto}
 * (VISUAL-GEO-V2-PLAN.md §3.1) and touched no existing field number, name or type. {@link
 * #pullControlRoundTripsAndStaysUnaffectedByTheGeoAddition()} pins that {@link PullControl} --
 * the one pre-existing message this repo's proto contract carries that had no additivity
 * coverage yet -- still serializes exactly as it did before H1's diff.
 */
class TrackingProtoAdditivityTest {

    @Test
    void frameRequestWithoutTrackingMatchesPreTrackingWireFormat() throws IOException {
        FrameRequest actual = FrameRequest.newBuilder()
                .setStreamId("cam-1")
                .setSequence(42L)
                .setTimestampMillis(1_723_400_000_000L)
                .setWidth(1920)
                .setHeight(1080)
                .setEncoding(ImageEncoding.IMAGE_ENCODING_JPEG)
                .setData(ByteString.copyFrom(new byte[]{1, 2, 3, 4}))
                .setModelId("yolo26n.pt")
                .setModelVersion("latest")
                .setConfidenceThreshold(0.35f)
                // tracking (field 11, NEW) deliberately left unset -- the field under test
                .build();

        byte[] expected = encode(out -> {
            out.writeString(1, "cam-1");
            out.writeInt64(2, 42L);
            out.writeInt64(3, 1_723_400_000_000L);
            out.writeInt32(4, 1920);
            out.writeInt32(5, 1080);
            out.writeEnum(6, ImageEncoding.IMAGE_ENCODING_JPEG.getNumber());
            out.writeByteArray(7, new byte[]{1, 2, 3, 4});
            out.writeString(8, "yolo26n.pt");
            out.writeString(9, "latest");
            out.writeFloat(10, 0.35f);
            // field 11 (tracking) intentionally absent: this is what "old" bytes look like
        });

        assertArrayEquals(expected, actual.toByteArray());

        // and parsing back must not silently invent a tracking config either
        FrameRequest roundTripped = FrameRequest.parseFrom(actual.toByteArray());
        assertFalse(roundTripped.hasTracking());
    }

    @Test
    void detectionWithoutTrackFieldsMatchesPreTrackingWireFormat() throws IOException {
        BoundingBox box = BoundingBox.newBuilder()
                .setX(0.1f).setY(0.2f).setWidth(0.3f).setHeight(0.4f)
                .build();

        Detection actual = Detection.newBuilder()
                .setLabel("car")
                .setConfidence(0.82f)
                .setBox(box)
                // track_id/track_state/source/velocity_x/velocity_y/track_age_frames (fields
                // 4-9, NEW) all left at their proto3 zero-value defaults
                .build();

        byte[] expected = encode(out -> {
            out.writeString(1, "car");
            out.writeFloat(2, 0.82f);
            out.writeMessage(3, box);
            // fields 4-9 (all NEW track fields) intentionally absent
        });

        assertArrayEquals(expected, actual.toByteArray());

        Detection roundTripped = Detection.parseFrom(actual.toByteArray());
        assertEquals(0L, roundTripped.getTrackId());
        assertEquals(TrackState.TRACK_STATE_UNSPECIFIED, roundTripped.getTrackState());
        assertEquals(DetectionSource.DETECTION_SOURCE_UNSPECIFIED, roundTripped.getSource());
    }

    @Test
    void detectionResponseWithoutTrackingTelemetryMatchesPreTrackingWireFormat() throws IOException {
        DetectionResponse actual = DetectionResponse.newBuilder()
                .setStreamId("cam-1")
                .setSequence(7L)
                .setTimestampMillis(1_723_400_000_000L)
                .setModelId("yolo26n.pt")
                .setModelVersion("latest")
                .setInferenceMillis(41L)
                // tracker_millis/detector_ran/tracker_engine_id/locked_track_id/detector_reason
                // (fields 8-12, NEW) all left at their proto3 zero-value defaults
                .build();

        byte[] expected = encode(out -> {
            out.writeString(1, "cam-1");
            out.writeInt64(2, 7L);
            out.writeInt64(3, 1_723_400_000_000L);
            out.writeString(4, "yolo26n.pt");
            out.writeString(5, "latest");
            // field 6 (repeated detections) empty -- omitted
            out.writeInt64(7, 41L);
            // fields 8-12 (all NEW tracking-telemetry fields) intentionally absent
        });

        assertArrayEquals(expected, actual.toByteArray());

        DetectionResponse roundTripped = DetectionResponse.parseFrom(actual.toByteArray());
        assertFalse(roundTripped.getDetectorRan());
        assertEquals(DetectorReason.DETECTOR_REASON_UNSPECIFIED, roundTripped.getDetectorReason());
        assertEquals(0L, roundTripped.getLockedTrackId());
    }

    /** Sanity check that the new fields actually round-trip once populated -- not just that they stay silent when absent. */
    @Test
    void trackingConfigAndTrackFieldsRoundTripWhenPresent() throws IOException {
        TargetLock lock = TargetLock.newBuilder()
                .setLockSeq(3L)
                .setTrackId(7L)
                .build();
        TrackingConfig tracking = TrackingConfig.newBuilder()
                .setMode(TrackingMode.TRACKING_MODE_FOLLOW)
                .setEngineId("lk")
                .setVerifyEveryMillis(2000)
                .setRedetectIouThreshold(0.3f)
                .setMaxAgeFrames(30)
                .setMinHits(3)
                .setLock(lock)
                .build();
        FrameRequest request = FrameRequest.newBuilder()
                .setStreamId("cam-1")
                .setTracking(tracking)
                .build();

        FrameRequest roundTripped = FrameRequest.parseFrom(request.toByteArray());
        assertEquals(tracking, roundTripped.getTracking());
        assertEquals(TrackingMode.TRACKING_MODE_FOLLOW, roundTripped.getTracking().getMode());
        assertEquals(7L, roundTripped.getTracking().getLock().getTrackId());

        Detection detection = Detection.newBuilder()
                .setLabel("car")
                .setTrackId(7L)
                .setTrackState(TrackState.TRACK_STATE_CONFIRMED)
                .setSource(DetectionSource.DETECTION_SOURCE_TRACKER)
                .setVelocityX(0.012f)
                .setVelocityY(-0.001f)
                .setTrackAgeFrames(143)
                .build();
        Detection roundTrippedDetection = Detection.parseFrom(detection.toByteArray());
        assertEquals(detection, roundTrippedDetection);

        DetectionResponse response = DetectionResponse.newBuilder()
                .setStreamId("cam-1")
                .setTrackerMillis(0L)
                .setDetectorRan(false)
                .setTrackerEngineId("lk")
                .setLockedTrackId(7L)
                .setDetectorReason(DetectorReason.DETECTOR_REASON_CADENCE)
                .build();
        DetectionResponse roundTrippedResponse = DetectionResponse.parseFrom(response.toByteArray());
        assertEquals(response, roundTrippedResponse);
    }

    /**
     * H1 (VISUAL-GEO-V2-PLAN.md §5 H1): {@code PullControl} is a pre-existing message the new
     * Geolocation section does not reference at all, but the wave's "done" criterion is that
     * {@code FrameRequest}/{@code Detection}/{@code DetectionResponse}/{@code PullControl} all
     * "serialize byte-identically" after the geo section lands. The first three already have
     * cases above (pre-dating H1); this pins the fourth, byte-for-byte, independent of {@link
     * PullControl}'s own generated serialization.
     */
    @Test
    void pullControlRoundTripsAndStaysUnaffectedByTheGeoAddition() throws IOException {
        TargetLock lock = TargetLock.newBuilder()
                .setLockSeq(5L)
                .setTrackId(9L)
                .build();
        TrackingConfig tracking = TrackingConfig.newBuilder()
                .setMode(TrackingMode.TRACKING_MODE_FOLLOW)
                .setLock(lock)
                .build();
        CameraPose pose = CameraPose.newBuilder()
                .setYawDegrees(12.5f)
                .setHfovDegrees(90f)
                .build();

        PullControl actual = PullControl.newBuilder()
                .setStreamId("cam-1")
                .setSourceUrl("rtsp://localhost:8554/cam-1")
                .setRtspTransport("tcp")
                .setModelId("yolo26n.pt")
                .setModelVersion("latest")
                .setConfidenceThreshold(0.35f)
                .setTargetFps(5f)
                .setDetectWidth(640)
                .setTracking(tracking)
                .setCameraPose(pose)
                .setStop(false)
                .build();

        byte[] expected = encode(out -> {
            out.writeString(1, "cam-1");
            out.writeString(2, "rtsp://localhost:8554/cam-1");
            out.writeString(3, "tcp");
            out.writeString(4, "yolo26n.pt");
            out.writeString(5, "latest");
            out.writeFloat(6, 0.35f);
            out.writeFloat(7, 5f);
            out.writeInt32(8, 640);
            out.writeMessage(9, tracking);
            out.writeMessage(10, pose);
            // field 11 (stop) at its proto3 zero-value (false) -- omitted
        });

        assertArrayEquals(expected, actual.toByteArray());

        PullControl roundTripped = PullControl.parseFrom(actual.toByteArray());
        assertEquals(actual, roundTripped);
        assertEquals(TrackingMode.TRACKING_MODE_FOLLOW, roundTripped.getTracking().getMode());
        assertEquals(9L, roundTripped.getTracking().getLock().getTrackId());
    }

    @FunctionalInterface
    private interface Writer {
        void write(CodedOutputStream out) throws IOException;
    }

    private static byte[] encode(Writer writer) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(bos);
        writer.write(cos);
        cos.flush();
        return bos.toByteArray();
    }
}
