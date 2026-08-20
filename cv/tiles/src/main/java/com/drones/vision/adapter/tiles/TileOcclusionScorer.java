package com.drones.vision.adapter.tiles;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Objects;

/**
 * Pure occlusion-proxy scorer for a decoded raster tile: the fraction of dark pixels, in {@code
 * [0,1]}, cheap and fully automatic (harvested from {@code feat/visual-geo}'s {@code
 * adapter-tiles}, docs/plans/active/VISUAL-GEO-V2-PLAN.md §1.3). Decodes via {@link ImageIO}
 * (JDK-native, {@code javax.imageio} — no new Maven dependency), converts to grayscale using the
 * standard ITU-R BT.601 luma weights (the same conversion {@code PIL.Image.convert("L")} performs),
 * and reports the fraction of pixels whose luma falls below {@link #DEFAULT_DARKNESS_THRESHOLD}.
 *
 * <h2>Why this proxy, not a semantic occlusion detector</h2>
 * A tall building near-nadir imagery casts a real shadow onto ground features it occludes; a dark
 * pixel fraction is a cheap, fully automatic proxy for "how much of this capture is shadow", not a
 * detector for "is this specific spot occluded". It can in principle be fooled by a genuinely dark
 * ground surface (asphalt, water) with zero actual occlusion; not expected to matter much in
 * practice since {@link WaybackTileSource} only ever compares scores across captures of the
 * <b>same</b> location (a dark parking lot is dark in every capture, so it never wins purely by
 * being ranked against itself), but worth a measured eye during acceptance testing.
 *
 * <h2>Decode failure</h2>
 * {@link #score(byte[])}/{@link #score(byte[], int)} never throw on malformed input. Bytes that
 * fail to decode (a truncated response, an HTML error body served with a 200, an empty array) score
 * {@link #WORST_SCORE} (1.0) — treated as maximally occluded and therefore <b>unusable</b>, the
 * worst possible candidate. This is deliberate, not merely "safe": {@link WaybackTileSource} picks
 * the <em>lowest</em>-scoring candidate among several releases, so a candidate that can't even be
 * decoded is automatically ranked last and never wins unless literally every other candidate also
 * failed to decode.
 */
public final class TileOcclusionScorer {

    /** Score returned for bytes that fail to decode as an image — see class javadoc. */
    public static final double WORST_SCORE = 1.0;

    /**
     * Default luma threshold (out of 255) below which a pixel counts as "dark".
     */
    public static final int DEFAULT_DARKNESS_THRESHOLD = 60;

    private TileOcclusionScorer() {
    }

    /**
     * Scores {@code imageBytes} using {@link #DEFAULT_DARKNESS_THRESHOLD}.
     *
     * @param imageBytes raw encoded image bytes (JPEG in production; any format {@link ImageIO} can
     *                   decode works, since decoding is format-agnostic)
     * @return the fraction of pixels darker than the threshold, in {@code [0,1]}; {@link
     *         #WORST_SCORE} if {@code imageBytes} does not decode to a usable image
     */
    public static double score(byte[] imageBytes) {
        return score(imageBytes, DEFAULT_DARKNESS_THRESHOLD);
    }

    /**
     * Scores {@code imageBytes} using an explicit darkness threshold.
     *
     * @param imageBytes        raw encoded image bytes
     * @param darknessThreshold luma value (out of 255) below which a pixel counts as "dark"
     * @return the fraction of pixels darker than {@code darknessThreshold}, in {@code [0,1]}; {@link
     *         #WORST_SCORE} if {@code imageBytes} does not decode to a usable image
     */
    public static double score(byte[] imageBytes, int darknessThreshold) {
        Objects.requireNonNull(imageBytes, "imageBytes must not be null");
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (IOException e) {
            return WORST_SCORE;
        }
        if (image == null) {
            // ImageIO.read returns null (not an exception) when no registered reader recognizes the
            // content -- e.g. an HTML error page served with a 200, or garbage bytes.
            return WORST_SCORE;
        }

        int width = image.getWidth();
        int height = image.getHeight();
        long totalPixels = (long) width * height;
        if (totalPixels == 0) {
            return WORST_SCORE;
        }

        long darkPixels = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                double luma = 0.299 * r + 0.587 * g + 0.114 * b;
                if (luma < darknessThreshold) {
                    darkPixels++;
                }
            }
        }
        return (double) darkPixels / totalPixels;
    }
}
