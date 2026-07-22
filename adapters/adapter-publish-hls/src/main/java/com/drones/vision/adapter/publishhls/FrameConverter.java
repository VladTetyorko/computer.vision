package com.drones.vision.adapter.publishhls;

import com.drones.vision.domain.model.VideoFrame;

import org.bytedeco.javacv.Frame;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Converts a domain {@link VideoFrame} into a JavaCV {@link Frame} ready to
 * hand to an {@link org.bytedeco.javacv.FFmpegFrameRecorder}.
 *
 * <p>Per the platform's decision that decoded BGR24 is the internal pixel
 * currency (see {@code docs/PHASE1-PLAN.md} §0.2), only two source formats
 * are understood: {@link com.drones.vision.domain.model.PixelFormat#BGR24}
 * is copied directly, and {@link com.drones.vision.domain.model.PixelFormat#JPEG}
 * (produced by the simulation source) is decoded via {@link ImageIO} and
 * re-packed as BGR.
 *
 * <p>Package-private and stateless so it is directly unit-testable without a
 * network or a real recorder, mirroring {@code adapter-rtsp}'s converter.
 */
final class FrameConverter {

    private FrameConverter() {
    }

    /**
     * Converts one frame, allocating a fresh {@link Frame} with its own
     * native backing buffer (never aliases {@code videoFrame}'s buffer).
     *
     * @param videoFrame the frame to convert; must carry {@code BGR24} or {@code JPEG} pixel data
     * @return a BGR, 3-channel, 8-bit {@link Frame} of the same dimensions
     * @throws IOException if {@code videoFrame} is JPEG-encoded and cannot be decoded
     */
    static Frame toFrame(VideoFrame videoFrame) throws IOException {
        return switch (videoFrame.format()) {
            case BGR24 -> bgr24ToFrame(videoFrame);
            case JPEG -> jpegToFrame(videoFrame);
            default -> throw new IllegalArgumentException(
                    "MediamtxStreamPublisher cannot publish pixel format " + videoFrame.format()
                            + " (only BGR24 and JPEG are supported)");
        };
    }

    /** Copies a packed BGR24 buffer into a new {@link Frame}, row by row to respect JavaCV's padded stride. */
    static Frame bgr24ToFrame(VideoFrame videoFrame) {
        int width = videoFrame.width();
        int height = videoFrame.height();
        Frame frame = new Frame(width, height, Frame.DEPTH_UBYTE, 3);
        copyPackedRowsInto((ByteBuffer) frame.image[0], videoFrame.data(), width * 3, height, frame.imageStride);
        return frame;
    }

    /** Decodes a JPEG buffer via {@link ImageIO} and re-packs the pixels as BGR into a new {@link Frame}. */
    static Frame jpegToFrame(VideoFrame videoFrame) throws IOException {
        ByteBuffer src = videoFrame.data();
        byte[] jpegBytes = new byte[src.remaining()];
        src.get(jpegBytes);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpegBytes));
        if (image == null) {
            throw new IOException("ImageIO could not decode JPEG frame for stream "
                    + videoFrame.streamId().value() + " (sequence " + videoFrame.sequence() + ")");
        }

        int width = image.getWidth();
        int height = image.getHeight();
        Frame frame = new Frame(width, height, Frame.DEPTH_UBYTE, 3);
        ByteBuffer dst = (ByteBuffer) frame.image[0];
        int[] argbRow = image.getRGB(0, 0, width, height, null, 0, width);

        for (int y = 0; y < height; y++) {
            dst.position(y * frame.imageStride);
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                int argb = argbRow[rowOffset + x];
                dst.put((byte) argb);         // B
                dst.put((byte) (argb >>> 8));  // G
                dst.put((byte) (argb >>> 16)); // R
            }
        }
        dst.rewind();
        return frame;
    }

    /**
     * Copies {@code height} rows of {@code rowBytes} packed bytes each from {@code src} into {@code dst},
     * starting each row at its {@code strideBytes}-aligned offset (JavaCV pads each row up to a multiple
     * of 8 bytes, so a flat copy would corrupt every row after the first once {@code rowBytes} isn't
     * itself a multiple of 8).
     */
    private static void copyPackedRowsInto(ByteBuffer dst, ByteBuffer src, int rowBytes, int height, int strideBytes) {
        byte[] row = new byte[rowBytes];
        for (int y = 0; y < height; y++) {
            src.get(row);
            dst.position(y * strideBytes);
            dst.put(row);
        }
        dst.rewind();
    }
}
