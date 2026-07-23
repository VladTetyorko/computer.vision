package com.drones.vision.adapter.overlay;

import com.drones.vision.domain.model.AnnotatedFrame;
import com.drones.vision.domain.model.BoundingBox;
import com.drones.vision.domain.model.Detection;
import com.drones.vision.domain.model.PixelFormat;
import com.drones.vision.domain.model.Telemetry;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.domain.port.out.OverlayPort;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Burns {@link Detection} boxes and an optional {@link Telemetry} OSD into a
 * frame's pixel data using pure {@code java.awt}/Java2D — no toolkit/display
 * is touched, so this runs headless (CI, containers), the same idiom
 * {@code adapter-simulation}'s {@code FrameRenderer} already uses.
 *
 * <h2>Format handling</h2>
 * <ul>
 *   <li>{@link PixelFormat#BGR24}: the packed BGR bytes are copied into a
 *       fresh {@link BufferedImage#TYPE_3BYTE_BGR} backing array (the same
 *       packed-BGR-no-padding layout {@code adapter-publish-hls}'s
 *       {@code FrameConverter} assumes), drawn on, and returned as a new
 *       {@code BGR24} frame.</li>
 *   <li>{@link PixelFormat#JPEG}: decoded via {@link ImageIO}, drawn on, and
 *       re-encoded as JPEG at ~0.8 quality.</li>
 *   <li>Any other format, or a JPEG payload {@code ImageIO} cannot decode:
 *       passed through unchanged. Overlay rendering is a cosmetic add-on to
 *       the video path and must never be the reason a stream breaks.</li>
 * </ul>
 *
 * <p>Plain class, no Spring — wiring into {@code StreamPipeline} is a later
 * task ({@code docs/MVP1-PLAN.md} §C8 bullet 2).
 */
public final class Java2DOverlayRenderer implements OverlayPort {

    /**
     * ~8 visually distinct categorical colors (Sasha Trubetskoy's "Alphabet"
     * subset, standard picks for label palettes). A label is assigned one by
     * hashing its text, so the same label always renders the same color and
     * different frames of the same stream stay visually consistent.
     */
    private static final Color[] LABEL_PALETTE = {
            new Color(230, 25, 75),   // red
            new Color(60, 180, 75),   // green
            new Color(255, 225, 25),  // yellow
            new Color(0, 130, 200),   // blue
            new Color(245, 130, 48),  // orange
            new Color(145, 30, 180),  // purple
            new Color(70, 240, 240),  // cyan
            new Color(240, 50, 230),  // magenta
    };

    private static final int MIN_STROKE_WIDTH = 2;
    private static final int MIN_FONT_SIZE = 12;
    private static final float JPEG_QUALITY = 0.8f;
    private static final Color OSD_BACKGROUND = new Color(0, 0, 0, 160);

    @Override
    public VideoFrame render(AnnotatedFrame annotated) {
        VideoFrame frame = annotated.frame();
        if (annotated.detections().isEmpty() && annotated.telemetry() == null) {
            return frame;
        }
        return switch (frame.format()) {
            case BGR24 -> renderBgr24(frame, annotated);
            case JPEG -> renderJpeg(frame, annotated);
            default -> frame;
        };
    }

    private static VideoFrame renderBgr24(VideoFrame frame, AnnotatedFrame annotated) {
        int width = frame.width();
        int height = frame.height();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        frame.data().get(pixels);

        paint(image, annotated);

        return new VideoFrame(frame.streamId(), frame.sequence(), frame.capturedAt(), width, height,
                PixelFormat.BGR24, ByteBuffer.wrap(pixels));
    }

    private static VideoFrame renderJpeg(VideoFrame frame, AnnotatedFrame annotated) {
        byte[] sourceBytes = readAll(frame.data());
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(sourceBytes));
        } catch (IOException e) {
            image = null;
        }
        if (image == null) {
            return frame;
        }

        paint(image, annotated);

        byte[] encoded;
        try {
            encoded = encodeJpeg(image);
        } catch (IOException e) {
            return frame;
        }

        return new VideoFrame(frame.streamId(), frame.sequence(), frame.capturedAt(), frame.width(), frame.height(),
                PixelFormat.JPEG, ByteBuffer.wrap(encoded));
    }

    private static void paint(BufferedImage image, AnnotatedFrame annotated) {
        int width = image.getWidth();
        int height = image.getHeight();
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            for (Detection detection : annotated.detections()) {
                drawDetection(g, width, height, detection);
            }
            if (annotated.telemetry() != null) {
                drawTelemetry(g, annotated.telemetry());
            }
        } finally {
            g.dispose();
        }
    }

    private static void drawDetection(Graphics2D g, int frameWidth, int frameHeight, Detection detection) {
        BoundingBox box = detection.box();
        int x = clampToDimension(box.x(), frameWidth);
        int y = clampToDimension(box.y(), frameHeight);
        int w = Math.min(Math.round((float) (box.width() * frameWidth)), frameWidth - x);
        int h = Math.min(Math.round((float) (box.height() * frameHeight)), frameHeight - y);
        if (w <= 0 || h <= 0) {
            return;
        }

        Color color = colorForLabel(detection.label());
        int strokeWidth = Math.max(MIN_STROKE_WIDTH, Math.min(frameWidth, frameHeight) / 200);
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

    private static void drawLabel(Graphics2D g, int boxX, int boxY, int frameWidth, int frameHeight,
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

    private static void drawTelemetry(Graphics2D g, Telemetry telemetry) {
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
        if (lines.isEmpty()) {
            return;
        }

        Font font = new Font(Font.MONOSPACED, Font.PLAIN, MIN_FONT_SIZE);
        g.setFont(font);
        FontMetrics metrics = g.getFontMetrics();
        int padding = 6;
        int lineHeight = metrics.getHeight();
        int blockWidth = lines.stream().mapToInt(metrics::stringWidth).max().orElse(0) + padding * 2;
        int blockHeight = lineHeight * lines.size() + padding;

        g.setColor(OSD_BACKGROUND);
        g.fillRect(4, 4, blockWidth, blockHeight);

        g.setColor(Color.WHITE);
        int baseline = 4 + padding / 2 + metrics.getAscent();
        for (String line : lines) {
            g.drawString(line, 4 + padding, baseline);
            baseline += lineHeight;
        }
    }

    /**
     * Assigns a stable color to a label by hashing its text into
     * {@link #LABEL_PALETTE}. Package-private so tests can probe color
     * assignment directly instead of only through rendered pixels.
     * {@code String.hashCode()} is specified by the JDK to be a fixed
     * formula, so this is stable across JVM runs, not just within one.
     */
    static Color colorForLabel(String label) {
        int index = Math.floorMod(label.hashCode(), LABEL_PALETTE.length);
        return LABEL_PALETTE[index];
    }

    private static Color readableTextColor(Color background) {
        double luminance = 0.299 * background.getRed() + 0.587 * background.getGreen() + 0.114 * background.getBlue();
        return luminance > 140 ? Color.BLACK : Color.WHITE;
    }

    private static int fontSize(int frameHeight) {
        return Math.max(MIN_FONT_SIZE, frameHeight / 45);
    }

    private static int clampToDimension(double normalized, int dimension) {
        int pixel = Math.round((float) (normalized * dimension));
        return Math.max(0, Math.min(pixel, dimension));
    }

    private static byte[] readAll(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private static byte[] encodeJpeg(BufferedImage image) throws IOException {
        BufferedImage rgbImage = image;
        if (image.getColorModel().hasAlpha()) {
            rgbImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgbImage.createGraphics();
            try {
                g.drawImage(image, 0, 0, null);
            } finally {
                g.dispose();
            }
        }

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG writer available for overlay re-encoding");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(rgbImage, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
