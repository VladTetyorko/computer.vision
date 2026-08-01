package com.drones.vision.application;

import com.drones.vision.domain.model.PixelFormat;
import com.drones.vision.domain.model.VideoFrame;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Iterator;

/**
 * Encodes a raw {@link VideoFrame} to <b>full-resolution</b> JPEG bytes for a captured {@link
 * com.drones.vision.domain.model.TrainingSample} (docs/CV-TRAINING-PLAN.md §D) — training truth
 * wants real pixels, never the downscaled thumbnail {@code vision-api}'s own snapshot encoder
 * produces for a dashboard.
 *
 * <p>Deliberately a focused, independent duplicate of that encoder's {@code BGR24}/JPEG idiom
 * rather than a shared dependency: {@code vision-application} cannot depend on {@code vision-api}
 * (wrong direction of the dependency rule, ArchUnit-enforced), and this module has no other reason
 * to take on an image-encoding dependency for anything but this one call site. This mirrors {@code
 * vision-api}'s own {@code SnapshotJpegEncoder}, whose javadoc documents the identical reasoning
 * for why <i>it</i> independently reimplements {@code adapter-cv-grpc}'s BGR24-wrap idiom rather
 * than sharing it — the same module-boundary trade-off, one level down.
 *
 * <p>Package-private, stateless, no interface — one call site ({@link
 * DefaultLabelingService#capture}), no second implementation ever plausible (java-clean-code
 * SKILL.md §1).
 *
 * <h2>Supported formats</h2>
 * The two {@link PixelFormat}s any adapter in this codebase actually produces: {@code BGR24} and
 * {@code JPEG} (already-JPEG frames pass through their bytes unchanged, no decode/re-encode round
 * trip). Anything else throws {@link IllegalStateException} — unreachable with today's adapters.
 */
final class TrainingFrameEncoder {

    private static final float JPEG_QUALITY = 0.9f;

    private TrainingFrameEncoder() {
    }

    /**
     * Encodes {@code frame} to full-resolution JPEG bytes.
     *
     * @param frame the frame to encode; never mutated
     * @return JPEG-encoded bytes
     * @throws IllegalStateException if {@code frame}'s {@link PixelFormat} is not one this method
     *                                supports
     * @throws UncheckedIOException  if the JPEG encoder itself fails
     */
    static byte[] encode(VideoFrame frame) {
        if (frame.format() == PixelFormat.JPEG) {
            return rawBytes(frame.data());
        }
        if (frame.format() != PixelFormat.BGR24) {
            throw new IllegalStateException("Unsupported pixel format for training capture: " + frame.format());
        }
        BufferedImage image = wrapBgr24(frame.width(), frame.height(), frame.data());
        return encodeJpeg(image);
    }

    private static byte[] rawBytes(ByteBuffer data) {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        return bytes;
    }

    /**
     * Copies raw {@code BGR24} bytes into a fresh {@link BufferedImage#TYPE_3BYTE_BGR}'s backing
     * array — a straight bulk copy, no per-pixel reordering, mirroring {@code vision-api}'s own
     * {@code SnapshotJpegEncoder#wrapBgr24}.
     */
    private static BufferedImage wrapBgr24(int width, int height, ByteBuffer data) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        data.get(pixels);
        return image;
    }

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
            throw new UncheckedIOException("Failed to JPEG-encode training capture frame", e);
        } finally {
            writer.dispose();
        }
    }
}
