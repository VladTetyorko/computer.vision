package com.drones.vision.adapter.tiles;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthetic-image tests for {@link TileOcclusionScorer} — known dark/light pixel fractions encoded
 * as real JPEG bytes via {@link ImageIO}, plus explicit decode-failure handling.
 */
class TileOcclusionScorerTest {

    private static final int SIZE = 32;

    @Test
    void allBlackImageScoresNearOne() throws IOException {
        byte[] jpeg = solidColorJpeg(Color.BLACK);
        double score = TileOcclusionScorer.score(jpeg);
        assertEquals(1.0, score, 0.02, "an all-black tile should be almost entirely 'dark'");
    }

    @Test
    void allWhiteImageScoresNearZero() throws IOException {
        byte[] jpeg = solidColorJpeg(Color.WHITE);
        double score = TileOcclusionScorer.score(jpeg);
        assertEquals(0.0, score, 0.02, "an all-white tile should have almost no 'dark' pixels");
    }

    @Test
    void halfDarkHalfLightImageScoresNearHalf() throws IOException {
        byte[] jpeg = halfAndHalfJpeg();
        double score = TileOcclusionScorer.score(jpeg);
        assertEquals(0.5, score, 0.05, "half-black-half-white should score close to 0.5");
    }

    @Test
    void scoreIsMonotonicWithDarknessAcrossGraySteps() throws IOException {
        double darkGrayScore = TileOcclusionScorer.score(solidColorJpeg(new Color(20, 20, 20)));
        double midGrayScore = TileOcclusionScorer.score(solidColorJpeg(new Color(128, 128, 128)));
        double lightGrayScore = TileOcclusionScorer.score(solidColorJpeg(new Color(220, 220, 220)));

        assertTrue(darkGrayScore > midGrayScore, "20/20/20 should score darker than 128/128/128");
        assertTrue(midGrayScore >= lightGrayScore, "128/128/128 should not score lighter than 220/220/220");
    }

    @Test
    void explicitThresholdChangesWhatCountsAsDark() throws IOException {
        byte[] jpeg = solidColorJpeg(new Color(80, 80, 80)); // luma ~80

        double scoreWithDefaultThreshold = TileOcclusionScorer.score(jpeg, TileOcclusionScorer.DEFAULT_DARKNESS_THRESHOLD);
        double scoreWithHighThreshold = TileOcclusionScorer.score(jpeg, 200);

        assertEquals(0.0, scoreWithDefaultThreshold, 0.02, "luma 80 is not below the default threshold of 60");
        assertEquals(1.0, scoreWithHighThreshold, 0.02, "luma 80 IS below a threshold of 200");
    }

    @Test
    void garbageBytesScoreAsWorstPossible() {
        byte[] garbage = "this is definitely not an image".getBytes();
        assertEquals(TileOcclusionScorer.WORST_SCORE, TileOcclusionScorer.score(garbage));
    }

    @Test
    void emptyBytesScoreAsWorstPossible() {
        assertEquals(TileOcclusionScorer.WORST_SCORE, TileOcclusionScorer.score(new byte[0]));
    }

    @Test
    void truncatedJpegBytesScoreAsWorstPossible() throws IOException {
        byte[] jpeg = solidColorJpeg(Color.BLACK);
        byte[] truncated = new byte[jpeg.length / 4];
        System.arraycopy(jpeg, 0, truncated, 0, truncated.length);

        assertEquals(TileOcclusionScorer.WORST_SCORE, TileOcclusionScorer.score(truncated));
    }

    @Test
    void scoreRejectsNullBytes() {
        assertThrows(NullPointerException.class, () -> TileOcclusionScorer.score(null));
    }

    private static byte[] solidColorJpeg(Color color) throws IOException {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, SIZE, SIZE);
        } finally {
            g.dispose();
        }
        return encodeJpeg(image);
    }

    private static byte[] halfAndHalfJpeg() throws IOException {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, SIZE, SIZE / 2);
            g.setColor(Color.WHITE);
            g.fillRect(0, SIZE / 2, SIZE, SIZE / 2);
        } finally {
            g.dispose();
        }
        return encodeJpeg(image);
    }

    private static byte[] encodeJpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean wrote = ImageIO.write(image, "jpg", out);
        assertTrue(wrote, "test fixture image must encode as JPEG");
        return out.toByteArray();
    }
}
