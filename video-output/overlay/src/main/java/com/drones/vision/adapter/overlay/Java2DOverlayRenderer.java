package com.drones.vision.adapter.overlay;

import com.drones.vision.perception.domain.model.AnnotatedFrame;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.PixelFormat;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.perception.domain.port.OverlayPort;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;

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
 *   <li>{@link PixelFormat#JPEG}: decoded via {@code ImageIO}, drawn on, and
 *       re-encoded as JPEG at the configured quality.</li>
 *   <li>Any other format, or a JPEG payload {@code ImageIO} cannot decode:
 *       passed through unchanged. Overlay rendering is a cosmetic add-on to
 *       the video path and must never be the reason a stream breaks.</li>
 * </ul>
 *
 * <p>Dispatch/format handling stays here; the actual codec and painting work
 * is split into {@link FrameImageCodec}, {@link DetectionBoxPainter} and
 * {@link TelemetryOsdPainter} (docs/plans/active/LAYERING-REFACTOR-PLAN.md §5.1) so this
 * class stays a thin orchestrator.
 *
 * <p>Plain class, no Spring. The no-arg constructor keeps today's callers
 * (and {@code vision-app}'s wiring) compiling unchanged, rendering with
 * {@link OverlaySettings#defaults()}; the {@link OverlaySettings}-taking
 * constructor is what a later wiring wave (docs/plans/active/LAYERING-REFACTOR-PLAN.md F3)
 * uses to inject real {@code vision.overlay.*} configuration.
 */
public final class Java2DOverlayRenderer implements OverlayPort {

    /**
     * ~8 visually distinct categorical colors (Sasha Trubetskoy's "Alphabet"
     * subset, standard picks for label palettes). A label is assigned one by
     * hashing its text, so the same label always renders the same color and
     * different frames of the same stream stay visually consistent.
     *
     * <p>Deliberately not a configurable value (docs/plans/active/LAYERING-REFACTOR-PLAN.md
     * §1.3): its length is baked into {@link #colorForLabel}'s {@code
     * floorMod(label.hashCode(), 8)} and the pixel-probe tests assert the
     * stable label→color mapping — changing it would break that mapping.
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

    private final FrameImageCodec codec;
    private final DetectionBoxPainter detectionBoxPainter;
    private final TelemetryOsdPainter telemetryOsdPainter;

    public Java2DOverlayRenderer() {
        this(OverlaySettings.defaults());
    }

    public Java2DOverlayRenderer(OverlaySettings settings) {
        this.codec = new FrameImageCodec(settings.jpegQuality());
        this.detectionBoxPainter = new DetectionBoxPainter(settings.minStrokeWidth(), settings.strokeDivisor(),
                settings.minFontSize(), settings.fontDivisor());
        this.telemetryOsdPainter = new TelemetryOsdPainter(settings.minFontSize(), settings.fontDivisor(),
                settings.osdBackgroundAlpha(), settings.osdMargin());
    }

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

    private VideoFrame renderBgr24(VideoFrame frame, AnnotatedFrame annotated) {
        BufferedImage image = codec.decodeBgr24(frame);
        paint(image, annotated);
        return codec.encodeBgr24(frame, image);
    }

    private VideoFrame renderJpeg(VideoFrame frame, AnnotatedFrame annotated) {
        BufferedImage image = codec.decodeJpeg(frame.data());
        if (image == null) {
            return frame;
        }

        paint(image, annotated);

        byte[] encoded;
        try {
            encoded = codec.encodeJpeg(image);
        } catch (IOException e) {
            return frame;
        }

        return new VideoFrame(frame.streamId(), frame.sequence(), frame.capturedAt(), frame.width(), frame.height(),
                PixelFormat.JPEG, ByteBuffer.wrap(encoded));
    }

    private void paint(BufferedImage image, AnnotatedFrame annotated) {
        int width = image.getWidth();
        int height = image.getHeight();
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            for (Detection detection : annotated.detections()) {
                detectionBoxPainter.draw(g, width, height, detection);
            }
            if (annotated.telemetry() != null) {
                telemetryOsdPainter.draw(g, height, annotated.telemetry());
            }
        } finally {
            g.dispose();
        }
    }

    /**
     * Assigns a stable color to a label by hashing its text into
     * {@link #LABEL_PALETTE}. Package-private so tests (and {@link
     * DetectionBoxPainter}) can use color assignment directly instead of
     * only through rendered pixels. {@code String.hashCode()} is specified
     * by the JDK to be a fixed formula, so this is stable across JVM runs,
     * not just within one.
     */
    static Color colorForLabel(String label) {
        int index = Math.floorMod(label.hashCode(), LABEL_PALETTE.length);
        return LABEL_PALETTE[index];
    }

    /**
     * Assigns a stable color to a track by hashing its numeric id into the
     * same {@link #LABEL_PALETTE} {@link #colorForLabel} draws from, so a
     * tracked detection keeps one color for the life of its track even when
     * its label flips between frames (composite-mode detectors can report
     * different labels for the same physical object,
     * docs/plans/done/TRACKING-PLAN.md §9 risk R9) — coloring by id rather than by
     * label is exactly what makes that stable. An untracked detection is
     * unaffected: it still colors by {@link #colorForLabel}.
     *
     * <p>Package-private for the same reason {@link #colorForLabel} is:
     * {@link DetectionBoxPainter} calls back into this method rather than
     * owning its own copy, and tests assert the mapping directly.
     */
    static Color colorForTrack(long trackId) {
        int index = Math.floorMod(trackId, LABEL_PALETTE.length);
        return LABEL_PALETTE[index];
    }
}
