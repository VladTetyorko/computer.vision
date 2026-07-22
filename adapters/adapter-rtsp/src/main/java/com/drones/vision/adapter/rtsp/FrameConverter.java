package com.drones.vision.adapter.rtsp;

import org.bytedeco.javacv.Frame;

import java.nio.Buffer;
import java.nio.ByteBuffer;

/**
 * Copies the pixel payload of a JavaCV {@link Frame} already decoded to
 * 3-channel, 8-bit BGR ("BGR24") data into a fresh, tightly packed heap
 * {@link ByteBuffer} (no per-row stride padding).
 *
 * <p>{@link Frame#image} is backed by native memory that the grabber (or
 * recorder) reuses on every subsequent {@code grab()}/{@code record()} call,
 * so the bytes must be copied out before they can be handed to code that
 * outlives the current grab-loop iteration — in particular before wrapping
 * them in a {@link com.drones.vision.domain.model.VideoFrame}, whose compact
 * constructor stores the buffer for the lifetime of that immutable record.
 *
 * <p>Package-private and stateless so it is unit-testable in isolation,
 * without any grabber/network/file I/O.
 */
final class FrameConverter {

    private FrameConverter() {
    }

    /**
     * Copies a decoded video frame's pixel data into a tightly packed BGR24
     * buffer.
     *
     * @param frame a video frame decoded to 3-channel, 8-bit-per-sample BGR
     *              pixel data (e.g. via {@code FFmpegFrameGrabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24)})
     * @return a new heap buffer, positioned at 0 with limit at capacity,
     *         containing exactly {@code frame.imageWidth * frame.imageHeight * 3}
     *         bytes of tightly packed BGR24 pixel data (any row padding stripped)
     * @throws IllegalArgumentException if the frame has no image payload, invalid
     *                                  dimensions, is not 3-channel, or its image
     *                                  buffer is not byte-backed
     */
    static ByteBuffer copyBgr24(Frame frame) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        if (frame.image == null || frame.image.length == 0 || frame.image[0] == null) {
            throw new IllegalArgumentException("frame has no image data to copy");
        }
        if (frame.imageWidth <= 0 || frame.imageHeight <= 0) {
            throw new IllegalArgumentException(
                    "frame has invalid dimensions: " + frame.imageWidth + "x" + frame.imageHeight);
        }
        if (frame.imageChannels != 3) {
            throw new IllegalArgumentException(
                    "expected a 3-channel BGR24 frame, got " + frame.imageChannels + " channel(s)");
        }
        Buffer rawImage = frame.image[0];
        if (!(rawImage instanceof ByteBuffer)) {
            throw new IllegalArgumentException(
                    "expected byte-backed image data, got " + rawImage.getClass().getSimpleName());
        }
        ByteBuffer source = (ByteBuffer) rawImage;

        int width = frame.imageWidth;
        int height = frame.imageHeight;
        int rowBytes = width * frame.imageChannels;
        int stride = frame.imageStride > 0 ? frame.imageStride : rowBytes;

        ByteBuffer copy = ByteBuffer.allocate(rowBytes * height);
        byte[] row = new byte[rowBytes];
        for (int y = 0; y < height; y++) {
            // Absolute bulk get: does not touch source's position/limit/mark,
            // so it is safe regardless of what state the caller left it in.
            source.get(y * stride, row, 0, rowBytes);
            copy.put(row);
        }
        copy.flip();
        return copy;
    }
}
