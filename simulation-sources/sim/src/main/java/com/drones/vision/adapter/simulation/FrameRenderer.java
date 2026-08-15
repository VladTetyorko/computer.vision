package com.drones.vision.adapter.simulation;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Renders a single synthetic video frame: a moving filled circle, a frame
 * counter, and the current wall-clock time, JPEG-encoded.
 *
 * <p>Pure in-memory Java2D rendering onto a {@link BufferedImage} — no AWT
 * display/toolkit is required, so this runs fine headless (CI, containers).
 */
final class FrameRenderer {

    private FrameRenderer() {
    }

    /**
     * Renders one frame at the given size for the given sequence number.
     *
     * @param width    frame width in pixels
     * @param height   frame height in pixels
     * @param sequence per-stream frame sequence number, used to animate the circle and label the frame
     * @return JPEG-encoded pixel data
     * @throws IOException if no JPEG writer is available or encoding fails
     */
    static byte[] renderJpeg(int width, int height, long sequence) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.DARK_GRAY);
            g.fillRect(0, 0, width, height);

            int radius = Math.max(6, Math.min(width, height) / 10);
            double angle = (sequence % 120) / 120.0 * 2 * Math.PI;
            double cx = width / 2.0 + Math.cos(angle) * Math.max(1, width / 2.0 - radius - 4);
            double cy = height / 2.0 + Math.sin(angle) * Math.max(1, height / 2.0 - radius - 4);
            g.setColor(Color.CYAN);
            g.fillOval((int) (cx - radius), (int) (cy - radius), radius * 2, radius * 2);

            g.setColor(Color.WHITE);
            g.drawString("frame #" + sequence, 10, 20);
            g.drawString(DateTimeFormatter.ISO_INSTANT.format(Instant.now()), 10, height - 10);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "jpg", out)) {
            throw new IOException("No JPEG writer available for simulated frame encoding");
        }
        return out.toByteArray();
    }
}
