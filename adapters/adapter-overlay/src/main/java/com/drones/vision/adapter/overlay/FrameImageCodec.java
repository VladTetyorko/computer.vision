package com.drones.vision.adapter.overlay;

import com.drones.vision.domain.model.PixelFormat;
import com.drones.vision.domain.model.VideoFrame;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;

/**
 * Pure pixel/byte &lt;-&gt; {@link BufferedImage} conversion for the two
 * overlay-supported {@link PixelFormat}s, split out of {@link
 * Java2DOverlayRenderer} (docs/LAYERING-REFACTOR-PLAN.md §5.1) so the
 * renderer's dispatch logic isn't tangled with codec detail.
 *
 * <p>Every decode/encode failure here is recoverable from the caller's point
 * of view — overlay rendering is a cosmetic add-on and must never be the
 * reason a stream breaks — so failures are logged at {@code WARNING} and
 * signalled by a {@code null} return or a thrown {@link IOException} the
 * caller already falls back on, never propagated as a stream-ending error.
 */
final class FrameImageCodec {

    private static final System.Logger LOG = System.getLogger(FrameImageCodec.class.getName());

    private final float jpegQuality;

    FrameImageCodec(float jpegQuality) {
        this.jpegQuality = jpegQuality;
    }

    /**
     * Copies a {@code BGR24} frame's packed pixel bytes into a fresh, freshly
     * allocated {@link BufferedImage#TYPE_3BYTE_BGR} image — the same
     * packed-BGR-no-padding layout {@code adapter-publish-hls}'s {@code
     * FrameConverter} assumes. Never aliases {@code frame.data()}: the
     * backing array is newly allocated and only ever {@code .get()}-copied
     * from the frame's own read-only duplicate.
     */
    BufferedImage decodeBgr24(VideoFrame frame) {
        int width = frame.width();
        int height = frame.height();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        byte[] pixels = rasterBytes(image);
        frame.data().get(pixels);
        return image;
    }

    /**
     * Builds the {@code BGR24} {@link VideoFrame} to return after painting,
     * reusing the exact backing array {@code image}'s raster already holds
     * (no extra copy) — {@code image} must have come from {@link
     * #decodeBgr24} and been painted on, not replaced.
     */
    VideoFrame encodeBgr24(VideoFrame source, BufferedImage image) {
        byte[] pixels = rasterBytes(image);
        return new VideoFrame(source.streamId(), source.sequence(), source.capturedAt(),
                image.getWidth(), image.getHeight(), PixelFormat.BGR24, ByteBuffer.wrap(pixels));
    }

    /**
     * Decodes a JPEG frame's bytes via {@link ImageIO}. Returns {@code null}
     * — never throws — on any decode failure (bad bytes, no matching
     * reader), which the caller treats identically to "pass the original
     * frame through unchanged".
     */
    BufferedImage decodeJpeg(ByteBuffer data) {
        byte[] bytes = readAll(data);
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                LOG.log(System.Logger.Level.WARNING,
                        "No ImageIO reader could decode the overlay JPEG frame; passing it through unchanged");
            }
            return image;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "JPEG overlay decode failed; passing frame through unchanged", e);
            return null;
        }
    }

    /**
     * Re-encodes a painted {@link BufferedImage} as JPEG at {@link
     * #jpegQuality}. A decoded JPEG image is assumed opaque; if {@code
     * hasAlpha()} is ever {@code true} (JPEG has no alpha channel, so this is
     * belt-and-braces, not expected in practice), it is flattened onto
     * {@code TYPE_INT_RGB} first — JPEG writers throw {@link IOException} on
     * an alpha-bearing image type otherwise.
     */
    byte[] encodeJpeg(BufferedImage image) throws IOException {
        BufferedImage rgbImage = image;
        if (image.getColorModel().hasAlpha()) {
            rgbImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgbImage.createGraphics();
            try {
                g.drawImage(image, 0, 0, null);
            } finally {
                g.dispose();
            }
        }

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG writer available for overlay re-encoding");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(jpegQuality);
            writer.write(null, new IIOImage(rgbImage, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static byte[] rasterBytes(BufferedImage image) {
        return ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
    }

    private static byte[] readAll(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
