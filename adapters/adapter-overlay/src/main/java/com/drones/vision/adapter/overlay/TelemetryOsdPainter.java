package com.drones.vision.adapter.overlay;

import com.drones.vision.flight.domain.model.Telemetry;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws the telemetry on-screen-display block, split out of {@link
 * Java2DOverlayRenderer} (docs/plans/active/LAYERING-REFACTOR-PLAN.md §5.1). One line per
 * present {@link Telemetry} field, each independently skippable; draws
 * nothing if every field is {@code null} (distinct from the top-level "no
 * detections and no telemetry" fast path in {@code Java2DOverlayRenderer},
 * which covers "no {@code Telemetry} object at all").
 *
 * <p><b>Latent-bug fix (docs/plans/active/LAYERING-REFACTOR-PLAN.md §5.1):</b> the OSD
 * font used to be pinned at the raw {@code MIN_FONT_SIZE} literal regardless
 * of frame resolution, making it a fixed, unreadably small 12px even at 4K.
 * It now scales the same way the detection-label font already did — {@code
 * max(minFontSize, frameHeight / fontDivisor)} — so at today's default
 * resolution (640x480, see {@code vision.simulation.video.*}) it still
 * renders at exactly 12px (480/45=10, floored up to the 12px minimum),
 * byte-identical to before, while a 4K frame now gets a legible, scaled
 * size instead of staying stuck at 12px.
 */
final class TelemetryOsdPainter {

    private static final int TEXT_PADDING = 6;

    private final int minFontSize;
    private final int fontDivisor;
    private final Color background;
    private final int margin;

    TelemetryOsdPainter(int minFontSize, int fontDivisor, int backgroundAlpha, int margin) {
        this.minFontSize = minFontSize;
        this.fontDivisor = fontDivisor;
        this.background = new Color(0, 0, 0, backgroundAlpha);
        this.margin = margin;
    }

    void draw(Graphics2D g, int frameHeight, Telemetry telemetry) {
        List<String> lines = lines(telemetry);
        if (lines.isEmpty()) {
            return;
        }

        Font font = new Font(Font.MONOSPACED, Font.PLAIN, fontSize(frameHeight));
        g.setFont(font);
        FontMetrics metrics = g.getFontMetrics();
        int lineHeight = metrics.getHeight();
        int blockWidth = lines.stream().mapToInt(metrics::stringWidth).max().orElse(0) + TEXT_PADDING * 2;
        int blockHeight = lineHeight * lines.size() + TEXT_PADDING;

        g.setColor(background);
        g.fillRect(margin, margin, blockWidth, blockHeight);

        g.setColor(Color.WHITE);
        int baseline = margin + TEXT_PADDING / 2 + metrics.getAscent();
        for (String line : lines) {
            g.drawString(line, margin + TEXT_PADDING, baseline);
            baseline += lineHeight;
        }
    }

    private static List<String> lines(Telemetry telemetry) {
        List<String> lines = new ArrayList<>();
        if (telemetry.latitude() != null || telemetry.longitude() != null) {
            StringBuilder line = new StringBuilder();
            if (telemetry.latitude() != null) {
                line.append(String.format(Locale.ROOT, "lat %.5f", telemetry.latitude()));
            }
            if (telemetry.longitude() != null) {
                if (!line.isEmpty()) {
                    line.append("  ");
                }
                line.append(String.format(Locale.ROOT, "lon %.5f", telemetry.longitude()));
            }
            lines.add(line.toString());
        }
        if (telemetry.altitudeMeters() != null) {
            lines.add(String.format(Locale.ROOT, "alt %.1fm", telemetry.altitudeMeters()));
        }
        if (telemetry.batteryPercent() != null) {
            lines.add(String.format(Locale.ROOT, "batt %.0f%%", telemetry.batteryPercent()));
        }
        return lines;
    }

    private int fontSize(int frameHeight) {
        return Math.max(minFontSize, frameHeight / fontDivisor);
    }
}
