package com.drones.vision.adapter.overlay;

import com.drones.vision.domain.model.BoundingBox;
import com.drones.vision.domain.model.Detection;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.Locale;

/**
 * Draws one {@link Detection}'s box border and label bar onto a frame, split
 * out of {@link Java2DOverlayRenderer} (docs/LAYERING-REFACTOR-PLAN.md §5.1).
 * Label→color assignment ({@link Java2DOverlayRenderer#colorForLabel})
 * deliberately stays on the renderer, not here — its palette length is baked
 * into pixel-probe test expectations and must not move (§1.3).
 */
final class DetectionBoxPainter {

    private final int minStrokeWidth;
    private final int strokeDivisor;
    private final int minFontSize;
    private final int fontDivisor;

    DetectionBoxPainter(int minStrokeWidth, int strokeDivisor, int minFontSize, int fontDivisor) {
        this.minStrokeWidth = minStrokeWidth;
        this.strokeDivisor = strokeDivisor;
        this.minFontSize = minFontSize;
        this.fontDivisor = fontDivisor;
    }

    /**
     * Converts {@code detection}'s normalized {@link BoundingBox} to pixels
     * (silently clipped to the frame — a box may sit exactly on the {@code
     * 1.0} edge, or the domain does not guarantee {@code x+width <= 1}), then
     * draws its border and label. A box clipped down to zero width/height is
     * simply not drawn.
     */
    void draw(Graphics2D g, int frameWidth, int frameHeight, Detection detection) {
        BoundingBox box = detection.box();
        int x = clampToDimension(box.x(), frameWidth);
        int y = clampToDimension(box.y(), frameHeight);
        int w = Math.min(Math.round((float) (box.width() * frameWidth)), frameWidth - x);
        int h = Math.min(Math.round((float) (box.height() * frameHeight)), frameHeight - y);
        if (w <= 0 || h <= 0) {
            return;
        }

        Color color = Java2DOverlayRenderer.colorForLabel(detection.label());
        int strokeWidth = Math.max(minStrokeWidth, Math.min(frameWidth, frameHeight) / strokeDivisor);
        drawInsetBorder(g, x, y, w, h, strokeWidth, color);

        String text = String.format(Locale.ROOT, "%s %.2f", detection.label(), detection.confidence());
        drawLabel(g, x, y, frameWidth, frameHeight, text, color);
    }

    /**
     * Draws a border entirely inside {@code [x, x+w) x [y, y+h)} as four
     * filled strips rather than a centered {@link java.awt.Stroke}, so the
     * painted pixels are exact and independent of anti-aliasing/line-join
     * rules — deterministic for both display and pixel-probe tests.
     */
    private static void drawInsetBorder(Graphics2D g, int x, int y, int w, int h, int strokeWidth, Color color) {
        int thickness = Math.min(strokeWidth, Math.min(w, h));
        g.setColor(color);
        g.fillRect(x, y, w, thickness);                  // top
        g.fillRect(x, y + h - thickness, w, thickness);   // bottom
        g.fillRect(x, y, thickness, h);                   // left
        g.fillRect(x + w - thickness, y, thickness, h);   // right
    }

    private void drawLabel(Graphics2D g, int boxX, int boxY, int frameWidth, int frameHeight,
                            String text, Color color) {
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, fontSize(frameHeight));
        g.setFont(font);
        FontMetrics metrics = g.getFontMetrics();
        int padding = Math.max(2, fontSize(frameHeight) / 4);
        int barWidth = Math.min(frameWidth, metrics.stringWidth(text) + padding * 2);
        int barHeight = metrics.getHeight() + padding;

        // Prefer the bar above the box; fall back inside the box's top edge
        // when the box touches (or is too close to) the frame's top.
        int barY = boxY - barHeight >= 0 ? boxY - barHeight : boxY;
        int barX = Math.max(0, Math.min(boxX, frameWidth - barWidth));

        g.setColor(color);
        g.fillRect(barX, barY, barWidth, barHeight);

        g.setColor(readableTextColor(color));
        int baseline = barY + padding / 2 + metrics.getAscent();
        g.drawString(text, barX + padding, baseline);
    }

    private static Color readableTextColor(Color background) {
        double luminance = 0.299 * background.getRed() + 0.587 * background.getGreen() + 0.114 * background.getBlue();
        return luminance > 140 ? Color.BLACK : Color.WHITE;
    }

    /**
     * Font size in pixels, scaled by frame height so text stays legible from
     * 480p up to 4K, floored at {@link #minFontSize} so it never shrinks to
     * illegibility on a tiny frame.
     */
    private int fontSize(int frameHeight) {
        return Math.max(minFontSize, frameHeight / fontDivisor);
    }

    private static int clampToDimension(double normalized, int dimension) {
        int pixel = Math.round((float) (normalized * dimension));
        return Math.max(0, Math.min(pixel, dimension));
    }
}
