package com.drones.vision.adapter.publishhls;

import com.drones.vision.domain.model.PixelFormat;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameConverterTest {

    private static final StreamId STREAM_ID = StreamId.random();

    // -- BGR24 -----------------------------------------------------------

    @Test
    void bgr24ToFrameCopiesPixelsAndRespectsJavaCvRowStride() throws IOException {
        // width=3, channels=3 => 9 bytes/row, which is NOT a multiple of 8: JavaCV pads each
        // row up to 16 bytes internally, so this exercises the row-wise (not flat) copy path.
        int width = 3;
        int height = 2;
        byte[] packed = new byte[width * height * 3];
        for (int i = 0; i < packed.length; i++) {
            packed[i] = (byte) i;
        }
        VideoFrame videoFrame = new VideoFrame(STREAM_ID, 0L, Instant.now(), width, height,
                PixelFormat.BGR24, ByteBuffer.wrap(packed));

        Frame frame = FrameConverter.toFrame(videoFrame);

        assertEquals(width, frame.imageWidth);
        assertEquals(height, frame.imageHeight);
        assertEquals(3, frame.imageChannels);
        assertEquals(Frame.DEPTH_UBYTE, frame.imageDepth);
        assertTrue(frame.imageStride >= width * 3, "JavaCV must pad the stride up to a multiple of 8 bytes");

        ByteBuffer image = (ByteBuffer) frame.image[0];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width * 3; x++) {
                byte expected = packed[y * width * 3 + x];
                byte actual = image.get(y * frame.imageStride + x);
                assertEquals(expected, actual, "byte mismatch at row " + y + ", offset " + x);
            }
        }
    }

    @Test
    void bgr24ToFrameNeverAliasesSourceBuffer() {
        int width = 2;
        int height = 2;
        byte[] packed = new byte[width * height * 3];
        VideoFrame videoFrame = new VideoFrame(STREAM_ID, 0L, Instant.now(), width, height,
                PixelFormat.BGR24, ByteBuffer.wrap(packed));

        Frame frame = FrameConverter.bgr24ToFrame(videoFrame);
        ByteBuffer image = (ByteBuffer) frame.image[0];
        image.put(0, (byte) 0x7F);

        // Mutating the converted Frame's buffer must not reach back into the VideoFrame's data.
        assertEquals(0, videoFrame.data().get(0));
    }

    // -- JPEG --------------------------------------------------------------

    @Test
    void jpegToFrameDecodesSolidColorImageAsApproximatelyCorrectBgr() throws IOException {
        int width = 16;
        int height = 16;
        byte[] jpeg = renderSolidJpeg(width, height, new Color(220, 30, 10)); // R=220 G=30 B=10

        VideoFrame videoFrame = new VideoFrame(STREAM_ID, 0L, Instant.now(), width, height,
                PixelFormat.JPEG, ByteBuffer.wrap(jpeg));

        Frame frame = FrameConverter.toFrame(videoFrame);

        assertEquals(width, frame.imageWidth);
        assertEquals(height, frame.imageHeight);
        assertEquals(3, frame.imageChannels);

        ByteBuffer image = (ByteBuffer) frame.image[0];
        int b = image.get(0) & 0xFF;
        int g = image.get(1) & 0xFF;
        int r = image.get(2) & 0xFF;

        // JPEG is lossy, so allow slack, but the channel identity/order must be unmistakable.
        assertTrue(Math.abs(r - 220) <= 15, "red channel out of tolerance: " + r);
        assertTrue(Math.abs(g - 30) <= 15, "green channel out of tolerance: " + g);
        assertTrue(Math.abs(b - 10) <= 15, "blue channel out of tolerance: " + b);
    }

    @Test
    void jpegToFramePreservesLeftRightSpatialOrderingAcrossQuadrants() throws IOException {
        int width = 20;
        int height = 10;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.RED);
            g.fillRect(0, 0, width / 2, height);
            g.setColor(Color.BLUE);
            g.fillRect(width / 2, 0, width / 2, height);
        } finally {
            g.dispose();
        }
        byte[] jpeg = encodeJpeg(image);
        VideoFrame videoFrame = new VideoFrame(STREAM_ID, 0L, Instant.now(), width, height,
                PixelFormat.JPEG, ByteBuffer.wrap(jpeg));

        Frame frame = FrameConverter.toFrame(videoFrame);
        ByteBuffer converted = (ByteBuffer) frame.image[0];

        int leftR = converted.get(0 * frame.imageStride + 2) & 0xFF; // row 0, first pixel, R
        int rightB = converted.get(0 * frame.imageStride + (width - 1) * 3) & 0xFF; // row 0, last pixel, B

        assertTrue(leftR > 150, "left half should decode as predominantly red, got R=" + leftR);
        assertTrue(rightB > 150, "right half should decode as predominantly blue, got B=" + rightB);
    }

    @Test
    void jpegToFrameThrowsIOExceptionOnUndecodableBytes() {
        VideoFrame videoFrame = new VideoFrame(STREAM_ID, 0L, Instant.now(), 4, 4,
                PixelFormat.JPEG, ByteBuffer.wrap(new byte[]{1, 2, 3, 4}));

        assertThrows(IOException.class, () -> FrameConverter.toFrame(videoFrame));
    }

    @Test
    void toFrameRejectsUnsupportedPixelFormats() {
        VideoFrame videoFrame = new VideoFrame(STREAM_ID, 0L, Instant.now(), 4, 4,
                PixelFormat.YUV420P, ByteBuffer.wrap(new byte[6 * 4]));

        assertThrows(IllegalArgumentException.class, () -> FrameConverter.toFrame(videoFrame));
    }

    // -- copyBgr24 (Frame -> ByteBuffer, the MediamtxReplayFrameExtractor direction) -----------
    // Mirrors adapter-rtsp's own FrameConverterTest for its twin method — same synthetic,
    // hand-built Frame instances, no grabber/network/file I/O.

    @Test
    void copyBgr24CopiesTightlyPackedPixelDataStrippingRowPadding() {
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

        Frame frame = syntheticGrabberFrame(width, height, channels, stride, raw);

        ByteBuffer result = FrameConverter.copyBgr24(frame);

        assertEquals(rowBytes * height, result.remaining());
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < rowBytes; x++) {
                byte expected = (byte) (y * 100 + x);
                assertEquals(expected, result.get(y * rowBytes + x), "mismatch at row " + y + ", column " + x);
            }
        }
    }

    @Test
    void copyBgr24CopiesContiguousDataWithNoPadding() {
        int width = 2;
        int height = 2;
        int channels = 3;
        byte[] raw = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};

        Frame frame = syntheticGrabberFrame(width, height, channels, width * channels, raw);

        ByteBuffer result = FrameConverter.copyBgr24(frame);

        byte[] out = new byte[result.remaining()];
        result.get(out);
        assertArrayEquals(raw, out);
    }

    @Test
    void copyBgr24ReturnedBufferIsIndependentOfTheSourceBuffer() {
        byte[] raw = {10, 20, 30};
        ByteBuffer source = ByteBuffer.wrap(raw);

        Frame frame = new Frame();
        frame.imageWidth = 1;
        frame.imageHeight = 1;
        frame.imageDepth = Frame.DEPTH_UBYTE;
        frame.imageChannels = 3;
        frame.imageStride = 3;
        frame.image = new Buffer[] {source};

        ByteBuffer result = FrameConverter.copyBgr24(frame);
        // Mutate the "native" source buffer after copying, simulating the grabber reusing it
        // for the next grabImage() call -- the previously returned copy must be unaffected.
        source.put(0, (byte) 99);

        assertEquals((byte) 10, result.get(0));
    }

    @Test
    void copyBgr24RejectsNullFrame() {
        assertThrows(IllegalArgumentException.class, () -> FrameConverter.copyBgr24(null));
    }

    @Test
    void copyBgr24RejectsFrameWithoutImageData() {
        Frame frame = new Frame();
        frame.imageWidth = 4;
        frame.imageHeight = 4;
        // frame.image left null: an audio-only/data-only frame

        assertThrows(IllegalArgumentException.class, () -> FrameConverter.copyBgr24(frame));
    }

    @Test
    void copyBgr24RejectsFrameWithInvalidDimensions() {
        Frame frame = new Frame();
        frame.imageWidth = 0;
        frame.imageHeight = 4;
        frame.imageChannels = 3;
        frame.imageStride = 0;
        frame.image = new Buffer[] {ByteBuffer.wrap(new byte[0])};

        assertThrows(IllegalArgumentException.class, () -> FrameConverter.copyBgr24(frame));
    }

    @Test
    void copyBgr24RejectsNonThreeChannelFrame() {
        Frame frame = syntheticGrabberFrame(2, 2, 1, 2, new byte[]{1, 2, 3, 4});

        assertThrows(IllegalArgumentException.class, () -> FrameConverter.copyBgr24(frame));
    }

    @Test
    void copyBgr24RejectsNonByteBackedImageBuffer() {
        Frame frame = new Frame();
        frame.imageWidth = 2;
        frame.imageHeight = 1;
        frame.imageChannels = 3;
        frame.imageStride = 6;
        frame.image = new Buffer[] {java.nio.FloatBuffer.allocate(6)};

        assertThrows(IllegalArgumentException.class, () -> FrameConverter.copyBgr24(frame));
    }

    private static Frame syntheticGrabberFrame(int width, int height, int channels, int stride, byte[] raw) {
        Frame frame = new Frame();
        frame.imageWidth = width;
        frame.imageHeight = height;
        frame.imageDepth = Frame.DEPTH_UBYTE;
        frame.imageChannels = channels;
        frame.imageStride = stride;
        frame.image = new Buffer[] {ByteBuffer.wrap(raw)};
        return frame;
    }

    private static byte[] renderSolidJpeg(int width, int height, Color color) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        return encodeJpeg(image);
    }

    private static byte[] encodeJpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "jpg", out)) {
            throw new IOException("No JPEG writer available");
        }
        return out.toByteArray();
    }
}
