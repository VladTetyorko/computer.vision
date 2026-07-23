package com.drones.vision.api;

import com.drones.vision.domain.model.PixelFormat;
import com.drones.vision.domain.model.VideoFrame;

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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Iterator;

/**
 * Encodes a {@link VideoFrame} to a downscaled JPEG for {@code GET /api/streams/{streamId}/snapshot}
 * (docs/MVP3-PLAN.md C-a). Deliberately lives here, not in an adapter: {@code vision-api} may not
 * depend on any adapter module (ArchUnit-enforced, see vision-app/MODULE.md's {@code
 * ArchitectureTest}), and this endpoint's owning layer is Spring-full already, so {@code
 * javax.imageio} is a fine dependency to take directly here rather than inventing a new port for
 * what is a one-shot, on-request encode.
 *
 * <p>Package-private, stateless, no interface — one call site ({@link StreamController#snapshot}),
 * no second implementation ever plausible (see {@code .claude/skills/java-clean-code/SKILL.md} §1).
 *
 * <h2>Idiom</h2>
 * The {@code BGR24}&rarr;{@link BufferedImage} bulk-copy and explicit-{@link ImageWriter} JPEG
 * encode below mirror {@code adapter-cv-grpc}'s {@code GrpcDetectionPort} downscale/encode path
 * (docs/CYCLES-PLAN.md §12, CP-b) exactly — the same idiom, independently implemented here since
 * this module cannot depend on that adapter.
 *
 * <h2>Supported formats</h2>
 * Only the two {@link PixelFormat}s any adapter in this codebase actually produces today: {@code
 * BGR24} (adapter-rtsp, and adapter-overlay's renderer when its input was {@code BGR24}) and {@code
 * JPEG} (adapter-simulation, adapter-mjpeg, and adapter-overlay's renderer when its input was
 * already {@code JPEG}). Anything else throws {@link IllegalStateException} — unreachable with
 * today's adapters, so deliberately left to fall through to Spring's default 500 rather than a
 * bespoke 4xx mapping in {@link ApiExceptionHandler}: an unexpected pixel format here is a genuine
 * server-side surprise, not a client mistake.
 */
final class SnapshotJpegEncoder {

    /**
     * Max width a snapshot is downscaled to before JPEG encoding (docs/MVP3-PLAN.md C-a); height
     * scales to preserve aspect ratio. Keeps a manager dashboard's many-thumbnail poll cycle cheap
     * — at this width a JPEG is a few KB to a few tens of KB, not the hundreds of KB to low-MB a
     * full-resolution frame could be.
     */
    static final int MAX_SNAPSHOT_WIDTH = 480;

    private static final float JPEG_QUALITY = 0.8f;

    private SnapshotJpegEncoder() {
    }

    /**
     * Encodes {@code frame} to JPEG bytes, downscaled to at most {@value #MAX_SNAPSHOT_WIDTH}px
     * wide. A frame that is already {@link PixelFormat#JPEG} and already at or under that width is
     * returned untouched (no decode/re-encode round trip) — the common case for
     * adapter-simulation/adapter-mjpeg's typically-small frames, keeping the cheap path genuinely
     * cheap.
     *
     * @param frame the frame to encode; never mutated
     * @return JPEG-encoded bytes
     * @throws IllegalStateException if {@code frame}'s {@link PixelFormat} is not one this method
     *                                supports, or a JPEG-format frame's bytes fail to decode
     * @throws UncheckedIOException  if the JPEG encoder itself fails
     */
    static byte[] encode(VideoFrame frame) {
        if (frame.format() == PixelFormat.JPEG && frame.width() <= MAX_SNAPSHOT_WIDTH) {
            return rawBytes(frame.data());
        }
        BufferedImage decoded = decode(frame);
        BufferedImage scaled = downscale(decoded);
        return encodeJpeg(scaled);
    }

    private static BufferedImage decode(VideoFrame frame) {
        return switch (frame.format()) {
            case BGR24 -> wrapBgr24(frame.width(), frame.height(), frame.data());
            case JPEG -> decodeJpeg(frame.data());
            default -> throw new IllegalStateException(
                    "Unsupported pixel format for snapshot encoding: " + frame.format());
        };
    }

    private static byte[] rawBytes(ByteBuffer data) {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        return bytes;
    }

    /**
     * Copies raw {@code BGR24} bytes into a fresh {@link BufferedImage#TYPE_3BYTE_BGR}'s backing
     * array — the same packed-BGR-no-padding layout that format already carries, so this is a
     * straight bulk copy, no per-pixel reordering. Same idiom as {@code adapter-cv-grpc}'s {@code
     * GrpcDetectionPort#wrapBgr24}/{@code adapter-overlay}'s {@code Java2DOverlayRenderer.renderBgr24}.
     */
    private static BufferedImage wrapBgr24(int width, int height, ByteBuffer data) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        data.get(pixels);
        return image;
    }

    private static BufferedImage decodeJpeg(ByteBuffer data) {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw new IllegalStateException("JPEG frame could not be decoded: no suitable ImageReader");
            }
            return image;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to decode JPEG frame for snapshot", e);
        }
    }

    private static BufferedImage downscale(BufferedImage source) {
        if (source.getWidth() <= MAX_SNAPSHOT_WIDTH) {
            return source;
        }
        double scale = (double) MAX_SNAPSHOT_WIDTH / source.getWidth();
        int scaledHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(MAX_SNAPSHOT_WIDTH, scaledHeight, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, MAX_SNAPSHOT_WIDTH, scaledHeight, null);
        } finally {
            g.dispose();
        }
        return scaled;
    }

    /** Encodes {@code image} as a JPEG at {@value #JPEG_QUALITY} quality via an explicit ImageWriter. */
    private static byte[] encodeJpeg(BufferedImage image) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No JPEG ImageWriter available on this JVM");
        }
        ImageWriter writer = writers.next();
        try {
            JPEGImageWriteParam param = new JPEGImageWriteParam(null);
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to JPEG-encode snapshot", e);
        } finally {
            writer.dispose();
        }
    }
}
