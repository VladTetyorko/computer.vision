package com.drones.vision.api;

import com.drones.vision.domain.model.PixelFormat;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotJpegEncoderTest {

    private static final StreamId STREAM_ID = StreamId.random();

    private static byte[] jpegBytes(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private static VideoFrame jpegFrame(int width, int height) throws IOException {
        return new VideoFrame(STREAM_ID, 0, Instant.now(), width, height, PixelFormat.JPEG,
                ByteBuffer.wrap(jpegBytes(width, height)));
    }

    private static VideoFrame bgr24Frame(int width, int height) {
        return new VideoFrame(STREAM_ID, 0, Instant.now(), width, height, PixelFormat.BGR24,
                ByteBuffer.wrap(new byte[width * height * 3]));
    }

    private static BufferedImage decode(byte[] jpeg) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }

    @Test
    void encodeReturnsASmallAlreadyJpegFrameUnchanged() throws IOException {
        byte[] original = jpegBytes(64, 48);
        VideoFrame frame = new VideoFrame(STREAM_ID, 0, Instant.now(), 64, 48, PixelFormat.JPEG,
                ByteBuffer.wrap(original));

        byte[] encoded = SnapshotJpegEncoder.encode(frame);

        assertArrayEquals(original, encoded,
                "a frame already at or under the max width and already JPEG must pass through untouched");
    }

    @Test
    void encodeDownscalesALargeJpegFrameToTheMaxWidth() throws IOException {
        VideoFrame frame = jpegFrame(960, 540);

        byte[] encoded = SnapshotJpegEncoder.encode(frame);
        BufferedImage decoded = decode(encoded);

        assertEquals(SnapshotJpegEncoder.MAX_SNAPSHOT_WIDTH, decoded.getWidth());
        assertEquals(Math.round(540.0 * SnapshotJpegEncoder.MAX_SNAPSHOT_WIDTH / 960.0), decoded.getHeight());
    }

    @Test
    void encodeConvertsAndDownscalesABgr24Frame() throws IOException {
        VideoFrame frame = bgr24Frame(960, 480);

        byte[] encoded = SnapshotJpegEncoder.encode(frame);
        BufferedImage decoded = decode(encoded);

        assertEquals(SnapshotJpegEncoder.MAX_SNAPSHOT_WIDTH, decoded.getWidth());
        assertEquals(240, decoded.getHeight());
    }

    @Test
    void encodeReturnsFullResolutionJpegForABgr24FrameAtOrUnderTheMaxWidth() throws IOException {
        VideoFrame frame = bgr24Frame(64, 48);

        byte[] encoded = SnapshotJpegEncoder.encode(frame);
        BufferedImage decoded = decode(encoded);

        assertEquals(64, decoded.getWidth());
        assertEquals(48, decoded.getHeight());
    }

    @Test
    void encodeThrowsForAnUnsupportedPixelFormat() {
        VideoFrame frame = new VideoFrame(STREAM_ID, 0, Instant.now(), 64, 48, PixelFormat.YUV420P,
                ByteBuffer.wrap(new byte[64 * 48 * 3 / 2]));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> SnapshotJpegEncoder.encode(frame));
        assertTrue(ex.getMessage().contains("YUV420P"));
    }
}
