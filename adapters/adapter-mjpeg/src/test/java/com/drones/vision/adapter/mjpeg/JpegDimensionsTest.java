package com.drones.vision.adapter.mjpeg;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JpegDimensionsTest {

    @Test
    void parsesWidthAndHeightFromABaselineJpeg() throws IOException {
        assertArrayEquals(new int[] {64, 48}, JpegDimensions.parse(renderJpeg(64, 48)));
    }

    @Test
    void parsesWidthAndHeightFromADifferentlySizedNonSquareJpeg() throws IOException {
        assertArrayEquals(new int[] {320, 200}, JpegDimensions.parse(renderJpeg(320, 200)));
    }

    @Test
    void parsesASmallOddSizedJpeg() throws IOException {
        assertArrayEquals(new int[] {17, 33}, JpegDimensions.parse(renderJpeg(17, 33)));
    }

    @Test
    void returnsNullForNullInput() {
        assertNull(JpegDimensions.parse(null));
    }

    @Test
    void returnsNullForEmptyOrTooShortInput() {
        assertNull(JpegDimensions.parse(new byte[0]));
        assertNull(JpegDimensions.parse(new byte[] {(byte) 0xFF}));
    }

    @Test
    void returnsNullWhenNotAJpegAtAll() {
        assertNull(JpegDimensions.parse("not a jpeg at all, just plain text".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void returnsNullForAJpegTruncatedBeforeItsSofMarker() throws IOException {
        byte[] jpeg = renderJpeg(64, 48);
        byte[] truncated = new byte[6]; // SOI + the very start of the next marker, nowhere near a full SOF payload
        System.arraycopy(jpeg, 0, truncated, 0, truncated.length);
        assertNull(JpegDimensions.parse(truncated));
    }

    private static byte[] renderJpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.BLUE);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "jpg", out)) {
            throw new IOException("No JPEG writer available in this JVM");
        }
        return out.toByteArray();
    }
}
