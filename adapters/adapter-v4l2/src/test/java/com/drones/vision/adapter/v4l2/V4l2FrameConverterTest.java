package com.drones.vision.adapter.v4l2;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

import java.nio.Buffer;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link V4l2FrameConverter}, exercised entirely against
 * synthetic, hand-built {@link Frame} instances -- no grabber, no device I/O.
 */
class V4l2FrameConverterTest {

    @Test
    void copiesTightlyPackedPixelDataStrippingRowPadding() {
        int width = 4;
        int height = 2;
        int channels = 3;
        int rowBytes = width * channels; // 12
        int stride = rowBytes + 4;       // 4 bytes of row padding, as a real grabber might produce

        byte[] raw = new byte[stride * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < rowBytes; x++) {
                raw[y * stride + x] = (byte) (y * 100 + x);
            }
            for (int x = rowBytes; x < stride; x++) {
                raw[y * stride + x] = (byte) 0xFF; // padding -- must never appear in the output
            }
        }

        Frame frame = syntheticFrame(width, height, channels, stride, raw);

        ByteBuffer result = V4l2FrameConverter.copyBgr24(frame);

        assertEquals(rowBytes * height, result.remaining());
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < rowBytes; x++) {
                byte expected = (byte) (y * 100 + x);
                assertEquals(expected, result.get(y * rowBytes + x),
                        "mismatch at row " + y + ", column " + x);
            }
        }
    }

    @Test
    void copiesContiguousDataWithNoPadding() {
        int width = 2;
        int height = 2;
        int channels = 3;
        byte[] raw = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};

        Frame frame = syntheticFrame(width, height, channels, width * channels, raw);

        ByteBuffer result = V4l2FrameConverter.copyBgr24(frame);

        byte[] out = new byte[result.remaining()];
        result.get(out);
        assertArrayEquals(raw, out);
    }

    @Test
    void returnedBufferIsIndependentOfTheSourceBuffer() {
        int width = 1;
        int height = 1;
        int channels = 3;
        byte[] raw = {10, 20, 30};
        ByteBuffer source = ByteBuffer.wrap(raw);

        Frame frame = new Frame();
        frame.imageWidth = width;
        frame.imageHeight = height;
        frame.imageDepth = Frame.DEPTH_UBYTE;
        frame.imageChannels = channels;
        frame.imageStride = width * channels;
        frame.image = new Buffer[] {source};

        ByteBuffer result = V4l2FrameConverter.copyBgr24(frame);
        // Mutate the "native" source buffer after copying, simulating the grabber reusing it
        // for the next grab() call -- the previously returned copy must be unaffected.
        source.put(0, (byte) 99);

        assertEquals((byte) 10, result.get(0));
    }

    @Test
    void rejectsNullFrame() {
        assertThrows(IllegalArgumentException.class, () -> V4l2FrameConverter.copyBgr24(null));
    }

    @Test
    void rejectsFrameWithoutImageData() {
        Frame frame = new Frame();
        frame.imageWidth = 4;
        frame.imageHeight = 4;
        // frame.image left null: a non-pixel frame

        assertThrows(IllegalArgumentException.class, () -> V4l2FrameConverter.copyBgr24(frame));
    }

    @Test
    void rejectsFrameWithInvalidDimensions() {
        Frame frame = new Frame();
        frame.imageWidth = 0;
        frame.imageHeight = 4;
        frame.imageChannels = 3;
        frame.imageStride = 0;
        frame.image = new Buffer[] {ByteBuffer.wrap(new byte[0])};

        assertThrows(IllegalArgumentException.class, () -> V4l2FrameConverter.copyBgr24(frame));
    }

    @Test
    void rejectsNonThreeChannelFrame() {
        Frame frame = syntheticFrame(2, 2, 1, 2, new byte[]{1, 2, 3, 4});

        assertThrows(IllegalArgumentException.class, () -> V4l2FrameConverter.copyBgr24(frame));
    }

    @Test
    void rejectsNonByteBackedImageBuffer() {
        Frame frame = new Frame();
        frame.imageWidth = 2;
        frame.imageHeight = 1;
        frame.imageChannels = 3;
        frame.imageStride = 6;
        frame.image = new Buffer[] {java.nio.FloatBuffer.allocate(6)};

        assertThrows(IllegalArgumentException.class, () -> V4l2FrameConverter.copyBgr24(frame));
    }

    private static Frame syntheticFrame(int width, int height, int channels, int stride, byte[] raw) {
        Frame frame = new Frame();
        frame.imageWidth = width;
        frame.imageHeight = height;
        frame.imageDepth = Frame.DEPTH_UBYTE;
        frame.imageChannels = channels;
        frame.imageStride = stride;
        frame.image = new Buffer[] {ByteBuffer.wrap(raw)};
        return frame;
    }
}
