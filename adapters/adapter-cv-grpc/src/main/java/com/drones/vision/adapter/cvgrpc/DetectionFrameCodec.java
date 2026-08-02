package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.domain.model.BoundingBox;
import com.drones.vision.domain.model.Detection;
import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.model.ModelRef;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.PixelFormat;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;
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
 * <h2>Payload shrinking for large {@code BGR24} frames (CP-b, docs/CYCLES-PLAN.md &sect;CP-b)</h2>
 * The RTSP/file RX path produces full-resolution raw {@code BGR24} frames (e.g. 1280&times;720
 * &asymp; 2.7&nbsp;MB uncompressed) — too large to push over gRPC at a useful detection rate. A
 * {@code BGR24} frame wider than this instance's {@code detectWidth} is downscaled to exactly
 * {@code detectWidth} wide (aspect preserved, {@code Math.round}-ed height) via Java2D bilinear
 * {@code drawImage}, then JPEG-encoded ({@link ImageIO}, explicit {@link ImageWriter}, quality =
 * this instance's {@code jpegQuality}) and sent as {@code IMAGE_ENCODING_JPEG} with the scaled
 * width/height. {@code JPEG} frames and {@code BGR24} frames already at or narrower than
 * {@code detectWidth} pass through byte-identical (only {@code >}, not {@code >=}, triggers the
 * downscale — a frame exactly {@code detectWidth}px wide is untouched).
 *
 * <p>No coordinate mapping back to the original resolution is needed or performed: detection boxes
 * come back normalized to {@code [0,1]} and {@link DetectionResult} never references the source
 * frame's pixel dimensions.
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

    DetectionFrameCodec(int detectWidth, float jpegQuality) {
        this.detectWidth = detectWidth;
        this.jpegQuality = jpegQuality;
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
                .setConfidenceThreshold((float) config.confidenceThreshold());

        if (encoding == ImageEncoding.IMAGE_ENCODING_BGR24 && frame.width() > detectWidth) {
            return withDownscaledJpeg(builder, frame);
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
                Duration.ofMillis(response.getInferenceMillis()));
    }

    private static ImageEncoding toImageEncoding(PixelFormat format) {
        return switch (format) {
            case JPEG -> ImageEncoding.IMAGE_ENCODING_JPEG;
            case BGR24 -> ImageEncoding.IMAGE_ENCODING_BGR24;
            default -> null;
        };
    }

    private FrameRequest withDownscaledJpeg(FrameRequest.Builder builder, VideoFrame frame) throws IOException {
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

        byte[] jpeg = encodeJpeg(scaled, jpegQuality);
        return builder
                .setWidth(scaledWidth)
                .setHeight(scaledHeight)
                .setEncoding(ImageEncoding.IMAGE_ENCODING_JPEG)
                .setData(ByteString.copyFrom(jpeg))
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
        return new Detection(wire.getLabel(), wire.getConfidence(), box, model);
    }
}
