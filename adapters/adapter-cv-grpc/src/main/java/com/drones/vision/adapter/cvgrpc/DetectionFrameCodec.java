package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.perception.domain.model.CameraAttitude;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.DetectionSource;
import com.drones.vision.perception.domain.model.DetectorReason;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.perception.domain.model.PixelFormat;
import com.drones.vision.perception.domain.model.PullTelemetry;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.TargetLock;
import com.drones.vision.perception.domain.model.TrackRef;
import com.drones.vision.perception.domain.model.TrackState;
import com.drones.vision.perception.domain.model.TrackingConfig;
import com.drones.vision.perception.domain.model.TrackingMode;
import com.drones.vision.perception.domain.model.TrackingTelemetry;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.proto.v1.CameraPose;
import com.drones.vision.proto.v1.DetectionResponse;
import com.drones.vision.proto.v1.FrameRequest;
import com.drones.vision.proto.v1.ImageEncoding;
import com.google.protobuf.ByteString;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;

/**
 * Pure wire&harr;domain codec for one {@link GrpcDetectionPort} instance: turns a {@link VideoFrame}+
 * {@link PipelineConfig} into an outbound {@link FrameRequest} ({@link #encode}), shrinking large
 * {@code BGR24} payloads first, and turns an inbound {@link DetectionResponse} into a domain {@link
 * DetectionResult} ({@link #decode}). No gRPC call machinery and no per-stream correlation state live
 * here — see {@link DetectionStreamSession} for that; this class only ever converts one frame or one
 * response at a time and never touches a network call.
 *
 * <h2>Payload shrinking for large {@code BGR24} frames (CP-b, docs/main/CYCLES-PLAN.md &sect;CP-b)</h2>
 * The RTSP/file RX path produces full-resolution raw {@code BGR24} frames (e.g. 1280&times;720
 * &asymp; 2.7&nbsp;MB uncompressed) — too large to push over gRPC at a useful detection rate. A
 * {@code BGR24} frame wider than this instance's {@code detectWidth} is downscaled to exactly
 * {@code detectWidth} wide (aspect preserved, {@code Math.round}-ed height) via Java2D bilinear
 * {@code drawImage}, then sent with the scaled width/height in this instance's {@link WireFormat}:
 * JPEG-encoded ({@link ImageIO}, explicit {@link ImageWriter}, quality = this instance's {@code
 * jpegQuality}), or raw {@code IMAGE_ENCODING_BGR24} when the endpoint is local enough that the
 * encode costs more than the bytes. {@code JPEG} frames and {@code BGR24} frames already at or narrower than
 * {@code detectWidth} pass through byte-identical (only {@code >}, not {@code >=}, triggers the
 * downscale — a frame exactly {@code detectWidth}px wide is untouched).
 *
 * <p>No coordinate mapping back to the original resolution is needed or performed: detection boxes
 * come back normalized to {@code [0,1]} and {@link DetectionResult} never references the source
 * frame's pixel dimensions.
 *
 * <h2>Tracking (docs/plans/done/TRACKING-PLAN.md &sect;4.A/&sect;4.B, docs/extracts/TRACKING-ORCHESTRATION.md &sect;5.1/&sect;5.2)</h2>
 * {@link #encode} maps {@link PipelineConfig#tracking()} onto every outbound {@code FrameRequest} —
 * {@code redetectIouPercent} (an {@code int} percent) converts to the wire's {@code float} ratio via
 * {@code / 100f}; a present {@link TrackingConfig#lock()} maps to a wire {@code TargetLock}, absent
 * leaves the wire {@code lock} field unset. {@link #decode} maps the wire's per-detection track fields
 * (4-9) onto {@link Detection#track()} and — the headline requirement of this class, not an
 * afterthought (TRACKING-ORCHESTRATION.md &sect;5.2) — the response's per-<em>frame</em> fields
 * ({@code detector_ran}/{@code detector_reason}/{@code tracker_millis}/{@code tracker_engine_id}/
 * {@code locked_track_id}) onto {@link DetectionResult#tracking()} as a {@link TrackingTelemetry}.
 * {@code track_id == 0} always decodes to {@code track() == null} — the wire's untracked sentinel
 * never becomes a {@code TrackRef(0, …)}. An {@code UNSPECIFIED}/unrecognized {@code TrackState} or
 * {@code DetectionSource} decodes defensively to no {@code TrackRef} at all rather than a guessed
 * one, mirroring this class's own {@code JOB_STATE_UNSPECIFIED -> RUNNING} "never guess" posture
 * elsewhere in this module (see {@code GrpcTrainingPort}). A response carrying none of the five
 * per-frame tracking fields (every one at its proto zero-value — the shape an old, pre-tracking
 * server's response has) decodes {@code tracking() == null}, byte-identical to this class's
 * pre-tracking behavior; this is indistinguishable at the wire level from a modern server explicitly
 * reporting all-default values, which never legitimately happens because a modern cv-service always
 * reports {@code detector_ran}/{@code detector_reason} on every response regardless of mode
 * (docs/plans/done/TRACKING-PLAN.md T1 wave).
 *
 * <h2>Failure shape</h2>
 * {@link #encode} throws {@link IllegalArgumentException} for an unsupported {@link PixelFormat}
 * (only {@code JPEG}/{@code BGR24} are mapped), {@link IOException} if the JPEG encoder fails, and a
 * {@link RuntimeException} (e.g. {@link java.nio.BufferUnderflowException}) if the source frame's
 * data is malformed/too short for its declared width/height. {@link GrpcDetectionPort#detect} catches
 * all three and fails only that one frame's stage — no {@link DetectionStreamSession} is created or
 * touched, so an already-open session for the same stream is unaffected.
 */
final class DetectionFrameCodec {

    private final int detectWidth;
    private final float jpegQuality;

    /**
     * Already resolved — never {@link WireFormat#AUTO}. Resolution belongs to whoever knows the
     * endpoint ({@link GrpcDetectionPort}); this class only encodes.
     */
    private final WireFormat wireFormat;

    DetectionFrameCodec(int detectWidth, float jpegQuality) {
        this(detectWidth, jpegQuality, WireFormat.JPEG);
    }

    DetectionFrameCodec(int detectWidth, float jpegQuality, WireFormat wireFormat) {
        this.detectWidth = detectWidth;
        this.jpegQuality = jpegQuality;
        this.wireFormat = wireFormat == WireFormat.AUTO ? WireFormat.JPEG : wireFormat;
    }

    /**
     * Builds the wire request for {@code frame}, downscaling+JPEG-re-encoding it first if it is a
     * {@code BGR24} frame wider than this instance's {@code detectWidth} (see class javadoc).
     * Runs synchronously on the caller's thread.
     *
     * @throws IllegalArgumentException if {@code frame}'s {@link PixelFormat} is not sendable
     * @throws IOException              if the JPEG encoder fails
     */
    FrameRequest encode(VideoFrame frame, PipelineConfig config) throws IOException {
        return encode(frame, config, null);
    }

    /**
     * As {@link #encode(VideoFrame, PipelineConfig)}, additionally stamping the camera attitude so
     * cv-service's {@code pose} ego-motion compensator has something to work with
     * (docs/conclusions/CV-RATE-BUDGET.md &sect;5, gap 3).
     *
     * <p>A {@code null} or {@link CameraAttitude#known() unknown} attitude sets <b>no</b> {@code
     * camera_pose} at all rather than a zeroed one — the wire's documented "absent = flow-based
     * compensation only". A zeroed pose would be worse than absent: cv-service gates on {@code
     * hfov_degrees > 0}, so it would be ignored anyway, at the cost of a submessage per frame.
     *
     * @param frame    the frame to send
     * @param config   model, threshold, and tracking configuration
     * @param attitude where the camera was pointing at capture, or {@code null} when unknown
     * @throws IllegalArgumentException if {@code frame}'s {@link PixelFormat} is not sendable
     * @throws IOException              if the JPEG encoder fails
     */
    FrameRequest encode(VideoFrame frame, PipelineConfig config, CameraAttitude attitude) throws IOException {
        ImageEncoding encoding = toImageEncoding(frame.format());
        if (encoding == null) {
            throw new IllegalArgumentException(
                    "Unsupported PixelFormat for gRPC inference: " + frame.format());
        }

        FrameRequest.Builder builder = FrameRequest.newBuilder()
                .setStreamId(frame.streamId().value().toString())
                .setSequence(frame.sequence())
                .setTimestampMillis(frame.capturedAt().toEpochMilli())
                .setModelId(config.model().id())
                .setModelVersion(config.model().version())
                .setConfidenceThreshold((float) config.confidenceThreshold())
                .setTracking(toWireTrackingConfig(config.tracking()));

        if (attitude != null && attitude.known()) {
            builder.setCameraPose(toWireCameraPose(attitude));
        }

        if (encoding == ImageEncoding.IMAGE_ENCODING_BGR24 && frame.width() > detectWidth) {
            return withDownscaled(builder, frame);
        }

        return builder
                .setWidth(frame.width())
                .setHeight(frame.height())
                .setEncoding(encoding)
                .setData(ByteString.copyFrom(frame.data()))
                .build();
    }

    /** Maps a wire {@link DetectionResponse} back to the domain {@link DetectionResult} for {@code streamId}. */
    static DetectionResult decode(StreamId streamId, DetectionResponse response) {
        ModelRef model = new ModelRef(response.getModelId(), response.getModelVersion());
        List<Detection> detections = response.getDetectionsList().stream()
                .map(wire -> toDetection(wire, model))
                .toList();
        return new DetectionResult(
                streamId,
                response.getSequence(),
                Instant.ofEpochMilli(response.getTimestampMillis()),
                detections,
                Duration.ofMillis(response.getInferenceMillis()),
                toTrackingTelemetry(response),
                toPullTelemetry(response));
    }

    /**
     * Maps the response's six pull-mode-only diagnostic fields (docs/plans/active/MEDIA-SOT-PLAN.md &sect;5.1
     * fields 16-21, decision D12) onto a {@link PullTelemetry} — the carrier M4 could decode the wire
     * for but had nowhere in the domain to put. Returns {@code null} (push mode, matching this class's
     * pre-D12 behavior byte-for-byte) exactly when every one of the six fields is still at its proto
     * zero-value — the shape a {@code DetectStream} response always has, since a pull worker always
     * reports a non-zero {@code source_fps}/{@code achieved_fps} once it has measured anything at all.
     * Mirrors {@link #toTrackingTelemetry}'s own all-zero-means-absent reasoning.
     */
    private static PullTelemetry toPullTelemetry(DetectionResponse response) {
        long decodeMillis = response.getDecodeMillis();
        float sourceFps = response.getSourceFps();
        float achievedFps = response.getAchievedFps();
        long droppedFrames = response.getDroppedFrames();
        long missedDeadlines = response.getMissedDeadlines();
        long captureSkewMillis = response.getCaptureSkewMillis();
        if (decodeMillis == 0 && sourceFps == 0f && achievedFps == 0f && droppedFrames == 0
                && missedDeadlines == 0 && captureSkewMillis == 0) {
            return null;
        }
        return new PullTelemetry(decodeMillis, sourceFps, achievedFps, droppedFrames, missedDeadlines,
                captureSkewMillis);
    }

    /**
     * Maps the response's five per-frame tracking fields (docs/plans/done/TRACKING-PLAN.md &sect;4.A fields
     * 8-12) onto a {@link TrackingTelemetry} — the docs/extracts/TRACKING-ORCHESTRATION.md &sect;5.2 gap fix
     * this wave exists to close. Returns {@code null} (tracking off for this result, matching this
     * class's pre-tracking behavior byte-for-byte) only when every one of the five fields is still
     * at its proto zero-value — the shape an old, pre-tracking server's response has, since a modern
     * cv-service always reports {@code detector_ran}/{@code detector_reason} on every response
     * regardless of mode (see class javadoc).
     */
    private static TrackingTelemetry toTrackingTelemetry(DetectionResponse response) {
        boolean detectorRan = response.getDetectorRan();
        long trackerMillis = response.getTrackerMillis();
        String engineId = response.getTrackerEngineId();
        long lockedTrackId = response.getLockedTrackId();
        com.drones.vision.proto.v1.DetectorReason wireReason = response.getDetectorReason();
        if (!detectorRan && trackerMillis == 0 && engineId.isEmpty() && lockedTrackId == 0
                && wireReason == com.drones.vision.proto.v1.DetectorReason.DETECTOR_REASON_UNSPECIFIED) {
            return null;
        }
        // detector_reason is meaningful only when detector_ran is true (docs/plans/done/TRACKING-PLAN.md
        // §4.G); an UNSPECIFIED reason on a detector-ran frame is a contract violation, not
        // something to guess at, and is left to fail via TrackingTelemetry's own compact-ctor
        // validation (caught per-response by DetectionStreamSession#onResponse).
        DetectorReason reason = detectorRan ? toDetectorReason(wireReason) : null;
        return new TrackingTelemetry(detectorRan, reason, Duration.ofMillis(trackerMillis), engineId, lockedTrackId);
    }

    /**
     * Maps the domain attitude onto the wire message field-for-field. {@code vfov_degrees} passes
     * {@code 0} straight through when the domain does not know it — the wire's own spelling of
     * "derive it from the horizontal FOV and the frame aspect ratio", which is exactly what
     * cv-service's {@code pose_gmc} does with it.
     *
     * <p>Package-private (not {@code private}), docs/plans/active/MEDIA-SOT-PLAN.md wave M4:
     * {@link PulledDetectionSession} reuses this exact mapping for {@code PullControl.camera_pose} —
     * the wire shape is identical between {@code FrameRequest} and {@code PullControl}, so this is the
     * one place either builds one.
     */
    static CameraPose toWireCameraPose(CameraAttitude attitude) {
        return CameraPose.newBuilder()
                .setYawDegrees((float) attitude.yawDegrees())
                .setPitchDegrees((float) attitude.pitchDegrees())
                .setRollDegrees((float) attitude.rollDegrees())
                .setHfovDegrees((float) attitude.hfovDegrees())
                .setVfovDegrees((float) attitude.vfovDegrees())
                .setPoseTimestampMillis(attitude.at().toEpochMilli())
                .build();
    }

    /**
     * Package-private (not {@code private}), docs/plans/active/MEDIA-SOT-PLAN.md wave M4: {@link
     * PulledDetectionSession} reuses this exact mapping for {@code PullControl.tracking} — {@code
     * PullControl} carries the identical wire {@code TrackingConfig} message {@code FrameRequest}
     * does (docs/plans/active/MEDIA-SOT-PLAN.md &sect;5.1: "reused verbatim, including TargetLock/lock_seq
     * semantics"), so this is the one place either builds one.
     */
    static com.drones.vision.proto.v1.TrackingConfig toWireTrackingConfig(TrackingConfig tracking) {
        com.drones.vision.proto.v1.TrackingConfig.Builder builder = com.drones.vision.proto.v1.TrackingConfig.newBuilder()
                .setMode(toWireTrackingMode(tracking.mode()))
                .setEngineId(tracking.engineId())
                .setVerifyEveryMillis(tracking.verifyEveryMillis())
                .setRedetectIouThreshold(tracking.redetectIouPercent() / 100f)
                .setMaxAgeFrames(tracking.maxAgeFrames())
                .setMinHits(tracking.minHits());
        TargetLock lock = tracking.lock();
        if (lock != null) {
            builder.setLock(toWireTargetLock(lock));
        }
        return builder.build();
    }

    private static com.drones.vision.proto.v1.TrackingMode toWireTrackingMode(TrackingMode mode) {
        return switch (mode) {
            case OFF -> com.drones.vision.proto.v1.TrackingMode.TRACKING_MODE_OFF;
            case ASSOCIATE -> com.drones.vision.proto.v1.TrackingMode.TRACKING_MODE_ASSOCIATE;
            case FOLLOW -> com.drones.vision.proto.v1.TrackingMode.TRACKING_MODE_FOLLOW;
        };
    }

    /**
     * {@code box} is deliberately never set: the domain {@link TargetLock} carries no box component
     * (docs/plans/done/TRACKING-PLAN.md §4.D — the PATCH surface accepts only {@code trackId}/point/{@code
     * release}), so the wire's optional explicit-box override is a cv-service-only affordance this
     * adapter has nothing to populate it from.
     */
    private static com.drones.vision.proto.v1.TargetLock toWireTargetLock(TargetLock lock) {
        com.drones.vision.proto.v1.TargetLock.Builder builder = com.drones.vision.proto.v1.TargetLock.newBuilder()
                .setLockSeq(lock.lockSeq())
                .setRelease(lock.release());
        if (lock.trackId() != null) {
            builder.setTrackId(lock.trackId());
        }
        if (lock.pointX() != null) {
            builder.setPointX(lock.pointX().floatValue());
            builder.setPointY(lock.pointY().floatValue());
        }
        return builder.build();
    }

    private static DetectorReason toDetectorReason(com.drones.vision.proto.v1.DetectorReason wire) {
        return switch (wire) {
            case DETECTOR_REASON_ALWAYS -> DetectorReason.ALWAYS;
            case DETECTOR_REASON_CADENCE -> DetectorReason.CADENCE;
            case DETECTOR_REASON_TRACKER_FAILED -> DetectorReason.TRACKER_FAILED;
            case DETECTOR_REASON_NO_LOCK -> DetectorReason.NO_LOCK;
            case DETECTOR_REASON_BOX_INVALID -> DetectorReason.BOX_INVALID;
            case DETECTOR_REASON_COASTED_OUT -> DetectorReason.COASTED_OUT;
            case DETECTOR_REASON_UNSPECIFIED, UNRECOGNIZED -> null;
        };
    }

    private static TrackState toTrackState(com.drones.vision.proto.v1.TrackState wire) {
        return switch (wire) {
            case TRACK_STATE_TENTATIVE -> TrackState.TENTATIVE;
            case TRACK_STATE_CONFIRMED -> TrackState.CONFIRMED;
            case TRACK_STATE_COASTING -> TrackState.COASTING;
            case TRACK_STATE_LOST -> TrackState.LOST;
            case TRACK_STATE_UNSPECIFIED, UNRECOGNIZED -> null;
        };
    }

    private static DetectionSource toDetectionSource(com.drones.vision.proto.v1.DetectionSource wire) {
        return switch (wire) {
            case DETECTION_SOURCE_DETECTOR -> DetectionSource.DETECTOR;
            case DETECTION_SOURCE_TRACKER -> DetectionSource.TRACKER;
            case DETECTION_SOURCE_UNSPECIFIED, UNRECOGNIZED -> null;
        };
    }

    /**
     * Maps a wire {@code Detection}'s track fields (4-9) onto a {@link TrackRef}, or {@code null} if
     * untracked. {@code track_id == 0} is the wire's untracked sentinel and short-circuits everything
     * else — it never reaches {@link TrackRef}'s constructor as a guessed {@code trackId}, regardless
     * of what the other track fields say (docs/extracts/TRACKING-ORCHESTRATION.md §6 rule 2). An {@code
     * UNSPECIFIED}/unrecognized {@code TrackState} or {@code DetectionSource} on an otherwise-tracked
     * detection decodes defensively to {@code null} too — never a guessed state/source.
     */
    private static TrackRef toTrackRef(com.drones.vision.proto.v1.Detection wire) {
        if (wire.getTrackId() == 0) {
            return null;
        }
        TrackState state = toTrackState(wire.getTrackState());
        if (state == null) {
            return null;
        }
        DetectionSource source = toDetectionSource(wire.getSource());
        if (source == null) {
            return null;
        }
        return new TrackRef(wire.getTrackId(), state, source, wire.getVelocityX(), wire.getVelocityY(),
                wire.getTrackAgeFrames());
    }

    private static ImageEncoding toImageEncoding(PixelFormat format) {
        return switch (format) {
            case JPEG -> ImageEncoding.IMAGE_ENCODING_JPEG;
            case BGR24 -> ImageEncoding.IMAGE_ENCODING_BGR24;
            default -> null;
        };
    }

    /**
     * Downscales an oversized {@code BGR24} frame to {@code detectWidth} and sends it in this
     * instance's {@link #wireFormat} — JPEG-encoded, or raw when the endpoint is close enough that
     * the encode costs more than the bytes do (docs/plans/active/CV-RATE-CONTROL-PLAN.md wave R3).
     *
     * <p>The raw branch skips {@link #encodeJpeg} here <b>and</b> a decode inside cv-service, which
     * together were most of the ~25 ms of non-inference round trip measured in
     * docs/conclusions/CV-RATE-BUDGET.md &sect;3. What it costs instead is payload: 640&times;360 of
     * BGR24 is about 691 KB against roughly 40 KB of JPEG. That trade is free over loopback and
     * indefensible over a radio link, which is exactly why the choice is configured rather than
     * made here.
     */
    private FrameRequest withDownscaled(FrameRequest.Builder builder, VideoFrame frame) throws IOException {
        int scaledWidth = detectWidth;
        int scaledHeight = Math.round((float) frame.height() * detectWidth / frame.width());

        BufferedImage source = wrapBgr24(frame.width(), frame.height(), frame.data());
        BufferedImage scaled = new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, scaledWidth, scaledHeight, null);
        } finally {
            g.dispose();
        }

        builder.setWidth(scaledWidth).setHeight(scaledHeight);
        if (wireFormat == WireFormat.BGR24) {
            // TYPE_3BYTE_BGR's backing array is already packed BGR with no row padding -- the same
            // layout the wire declares -- so this is a straight handover, not a conversion.
            byte[] pixels = ((DataBufferByte) scaled.getRaster().getDataBuffer()).getData();
            return builder
                    .setEncoding(ImageEncoding.IMAGE_ENCODING_BGR24)
                    .setData(ByteString.copyFrom(pixels))
                    .build();
        }
        return builder
                .setEncoding(ImageEncoding.IMAGE_ENCODING_JPEG)
                .setData(ByteString.copyFrom(encodeJpeg(scaled, jpegQuality)))
                .build();
    }

    /**
     * Copies raw {@code BGR24} bytes into a fresh {@link BufferedImage#TYPE_3BYTE_BGR}'s
     * backing array — the same packed-BGR-no-padding layout that format already carries, so this is
     * a straight bulk copy, no per-pixel reordering. Same idiom as {@code adapter-overlay}'s
     * {@code Java2DOverlayRenderer.renderBgr24}.
     */
    private static BufferedImage wrapBgr24(int width, int height, ByteBuffer data) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        data.get(pixels);
        return image;
    }

    /** Encodes {@code image} as a JPEG at {@code jpegQuality} via an explicit ImageWriter. */
    private static byte[] encodeJpeg(BufferedImage image, float jpegQuality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG ImageWriter available on this JVM");
        }
        ImageWriter writer = writers.next();
        try {
            JPEGImageWriteParam param = new JPEGImageWriteParam(null);
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(jpegQuality);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return out.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static Detection toDetection(com.drones.vision.proto.v1.Detection wire, ModelRef model) {
        var wireBox = wire.getBox();
        BoundingBox box = new BoundingBox(wireBox.getX(), wireBox.getY(), wireBox.getWidth(), wireBox.getHeight());
        return new Detection(wire.getLabel(), wire.getConfidence(), box, model, toTrackRef(wire));
    }
}
