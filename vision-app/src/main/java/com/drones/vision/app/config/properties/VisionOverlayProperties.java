package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for {@code adapter-overlay}'s detection/OSD burn-in ({@code vision.overlay.*}),
 * docs/LAYERING-REFACTOR-PLAN.md &sect;2.2, wave F3.
 *
 * <p>Mapped by {@code wiring.PublishWiring} onto {@code
 * com.drones.vision.adapter.overlay.OverlaySettings} — every {@code @DefaultValue} below is
 * byte-identical to {@code OverlaySettings.defaults()}'s own literal.
 *
 * @param jpegQuality        re-encode quality for the {@code JPEG} overlay path; default {@value #DEFAULT_JPEG_QUALITY}
 * @param minStrokeWidth     floor on a detection box border's thickness in pixels; default {@value #DEFAULT_MIN_STROKE_WIDTH}
 * @param strokeDivisor      divisor scaling border thickness with resolution; default {@value #DEFAULT_STROKE_DIVISOR}
 * @param minFontSize        floor on any rendered font size in pixels; default {@value #DEFAULT_MIN_FONT_SIZE}
 * @param fontDivisor        divisor scaling font size with resolution; default {@value #DEFAULT_FONT_DIVISOR}
 * @param osdBackgroundAlpha alpha channel of the telemetry OSD's background fill; default {@value #DEFAULT_OSD_BACKGROUND_ALPHA}
 * @param osdMargin          pixel offset of the telemetry OSD block; default {@value #DEFAULT_OSD_MARGIN}
 */
@ConfigurationProperties(prefix = "vision.overlay")
public record VisionOverlayProperties(
        @DefaultValue(VisionOverlayProperties.DEFAULT_JPEG_QUALITY) float jpegQuality,
        @DefaultValue(VisionOverlayProperties.DEFAULT_MIN_STROKE_WIDTH) int minStrokeWidth,
        @DefaultValue(VisionOverlayProperties.DEFAULT_STROKE_DIVISOR) int strokeDivisor,
        @DefaultValue(VisionOverlayProperties.DEFAULT_MIN_FONT_SIZE) int minFontSize,
        @DefaultValue(VisionOverlayProperties.DEFAULT_FONT_DIVISOR) int fontDivisor,
        @DefaultValue(VisionOverlayProperties.DEFAULT_OSD_BACKGROUND_ALPHA) int osdBackgroundAlpha,
        @DefaultValue(VisionOverlayProperties.DEFAULT_OSD_MARGIN) int osdMargin) {

    static final String DEFAULT_JPEG_QUALITY = "0.8";
    static final String DEFAULT_MIN_STROKE_WIDTH = "2";
    static final String DEFAULT_STROKE_DIVISOR = "200";
    static final String DEFAULT_MIN_FONT_SIZE = "12";
    static final String DEFAULT_FONT_DIVISOR = "45";
    static final String DEFAULT_OSD_BACKGROUND_ALPHA = "160";
    static final String DEFAULT_OSD_MARGIN = "4";
}
