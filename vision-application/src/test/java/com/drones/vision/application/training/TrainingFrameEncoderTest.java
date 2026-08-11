package com.drones.vision.application.training;

import com.drones.vision.perception.domain.model.PixelFormat;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.VideoFrame;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingFrameEncoderTest {

    private static VideoFrame frame(PixelFormat format, int width, int height, byte[] data) {
        return new VideoFrame(StreamId.random(), 0, Instant.now(), width, height, format, ByteBuffer.wrap(data));
    }

    @Test
    void passesThroughAnAlreadyJpegFrameUnchanged() {
        byte[] jpegBytes = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3};
        VideoFrame frame = frame(PixelFormat.JPEG, 64, 48, jpegBytes);

        byte[] encoded = new TrainingFrameEncoder().encode(frame);

        assertArrayEquals(jpegBytes, encoded);
    }

    @Test
    void encodesABgr24FrameAtFullResolution() throws IOException {
        int width = 32;
        int height = 24;
        byte[] bgr = new byte[width * height * 3];
        VideoFrame frame = frame(PixelFormat.BGR24, width, height, bgr);

        byte[] encoded = new TrainingFrameEncoder().encode(frame);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(encoded));
        assertTrue(decoded != null, "encoded bytes must be a decodable JPEG");
        assertEquals(width, decoded.getWidth(), "must not be downscaled, unlike vision-api's snapshot encoder");
        assertEquals(height, decoded.getHeight());
    }

    @Test
    void rejectsAnUnsupportedPixelFormat() {
        VideoFrame frame = frame(PixelFormat.YUV420P, 32, 24, new byte[32 * 24]);

        assertThrows(IllegalStateException.class, () -> new TrainingFrameEncoder().encode(frame));
    }
}
