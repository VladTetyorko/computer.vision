package com.drones.vision.adapter.overlay;

/**
 * Tunable layout/encode values for {@link Java2DOverlayRenderer} and its
 * collaborators, extracted from {@code private static final} literals per
 * {@code docs/LAYERING-REFACTOR-PLAN.md} §1.3/§2.2 (property prefix
 * {@code vision.overlay}). Framework-free — {@code vision-app} maps its own
 * {@code VisionOverlayProperties} (a later wave) onto this record and passes
 * it as one constructor argument, matching §1.3 rule 3.
 *
 * <p>{@link #defaults()} reproduces exactly the literals this record
 * replaced, so a caller that does not yet wire real configuration renders
 * byte-identical output to before this type existed.
 *
 * @param jpegQuality         re-encode quality for the {@code JPEG} overlay
 *                            path, {@code (0,1]} (was {@code JPEG_QUALITY})
 * @param minStrokeWidth      floor on a detection box border's thickness in
 *                            pixels, positive (was {@code MIN_STROKE_WIDTH})
 * @param strokeDivisor       divisor applied to {@code min(frameWidth,
 *                            frameHeight)} to scale border thickness with
 *                            resolution, positive (was the literal {@code
 *                            200} in {@code drawDetection})
 * @param minFontSize         floor on any rendered font size in pixels,
 *                            positive (was {@code MIN_FONT_SIZE})
 * @param fontDivisor         divisor applied to {@code frameHeight} to scale
 *                            font size with resolution, positive (was the
 *                            literal {@code 45} in {@code fontSize})
 * @param osdBackgroundAlpha  alpha channel, {@code [0,255]}, of the
 *                            telemetry OSD's background fill (was the {@code
 *                            160} in {@code OSD_BACKGROUND})
 * @param osdMargin           pixel offset of the telemetry OSD block's
 *                            top-left corner from the frame's own top-left
 *                            corner, non-negative (was the literal {@code 4}
 *                            in {@code drawTelemetry})
 */
public record OverlaySettings(
        float jpegQuality,
        int minStrokeWidth,
        int strokeDivisor,
        int minFontSize,
        int fontDivisor,
        int osdBackgroundAlpha,
        int osdMargin) {

    public OverlaySettings {
        if (jpegQuality <= 0f || jpegQuality > 1f) {
            throw new IllegalArgumentException("jpegQuality must be in (0,1]: " + jpegQuality);
        }
        if (minStrokeWidth <= 0) {
            throw new IllegalArgumentException("minStrokeWidth must be positive: " + minStrokeWidth);
        }
        if (strokeDivisor <= 0) {
            throw new IllegalArgumentException("strokeDivisor must be positive: " + strokeDivisor);
        }
        if (minFontSize <= 0) {
            throw new IllegalArgumentException("minFontSize must be positive: " + minFontSize);
        }
        if (fontDivisor <= 0) {
            throw new IllegalArgumentException("fontDivisor must be positive: " + fontDivisor);
        }
        if (osdBackgroundAlpha < 0 || osdBackgroundAlpha > 255) {
            throw new IllegalArgumentException("osdBackgroundAlpha must be in [0,255]: " + osdBackgroundAlpha);
        }
        if (osdMargin < 0) {
            throw new IllegalArgumentException("osdMargin must not be negative: " + osdMargin);
        }
    }

    /**
     * The literal values {@link Java2DOverlayRenderer} used before this
     * record existed — {@code jpeg-quality=0.8}, {@code min-stroke-width=2},
     * {@code stroke-divisor=200}, {@code min-font-size=12}, {@code
     * font-divisor=45}, {@code osd-background-alpha=160}, {@code
     * osd-margin=4} (docs/LAYERING-REFACTOR-PLAN.md §2.2).
     */
    public static OverlaySettings defaults() {
        return new OverlaySettings(0.8f, 2, 200, 12, 45, 160, 4);
    }
}
