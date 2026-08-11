package com.drones.vision.adapter.overlay;

import com.drones.vision.domain.model.BoundingBox;
import com.drones.vision.domain.model.Detection;
import com.drones.vision.domain.model.DetectionSource;
import com.drones.vision.domain.model.ModelRef;
import com.drones.vision.domain.model.TrackRef;
import com.drones.vision.domain.model.TrackState;

import org.junit.jupiter.api.Test;

import java.awt.BasicStroke;
import java.awt.Stroke;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct unit tests for {@link DetectionBoxPainter}, added in wave T5
 * (docs/TRACKING-PLAN.md §7) alongside {@link Java2DOverlayRendererTest}'s
 * renderer-level pixel probes. Two things are deliberately tested here
 * rather than through a rendered frame:
 *
 * <ul>
 *   <li>{@link DetectionBoxPainter#labelText} — exact string content is
 *       trivial to assert directly and does not depend on {@code
 *       FontMetrics}, unlike the label bar's pixel geometry.</li>
 *   <li>Solid vs. dashed border dispatch — asserted structurally via
 *       {@link RecordingGraphics2D}, per this module's determinism
 *       convention that a dashed stroke's rasterized pixels are never
 *       pixel-probed (adapter-overlay/MODULE.md).</li>
 * </ul>
 */
class DetectionBoxPainterTest {

    private static final ModelRef MODEL = new ModelRef("yolo", "v1");
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;

    private final DetectionBoxPainter painter = new DetectionBoxPainter(2, 200, 12, 45);

    // -- label text ------------------------------------------------------

    @Test
    void labelTextOmitsTrackIdWhenUntracked() {
        Detection detection = new Detection("person", 0.87, new BoundingBox(0.1, 0.1, 0.2, 0.2), MODEL);

        assertEquals("person 0.87", DetectionBoxPainter.labelText(detection));
    }

    @Test
    void labelTextIncludesTrackIdWhenTracked() {
        TrackRef track = new TrackRef(7, TrackState.CONFIRMED, DetectionSource.TRACKER);
        Detection detection = new Detection("person", 0.87, new BoundingBox(0.1, 0.1, 0.2, 0.2), MODEL, track);

        assertEquals("#7 person 0.87", DetectionBoxPainter.labelText(detection));
    }

    // -- solid vs. dashed border dispatch (structural, not pixel-probed) -

    @Test
    void confirmedTrackDrawsSolidBorderViaFillRectStripsOnly() {
        RecordingGraphics2D recording = recordingGraphics();
        TrackRef track = new TrackRef(7, TrackState.CONFIRMED, DetectionSource.TRACKER);
        Detection detection = new Detection("person", 0.87, new BoundingBox(0.5, 0.5, 0.2, 0.2), MODEL, track);

        painter.draw(recording, WIDTH, HEIGHT, detection);

        // 4 border strips (top/bottom/left/right, drawInsetBorder) + 1 label bar fill.
        assertEquals(5, recording.fillRectCalls.size(),
                "solid path must draw the border as fillRect strips, unchanged from the untracked path");
        assertTrue(recording.drawCalls.isEmpty(),
                "solid path must not invoke Graphics2D#draw(Shape) at all");
    }

    @Test
    void untrackedDetectionAlsoDrawsSolidBorderViaFillRectStripsOnly() {
        RecordingGraphics2D recording = recordingGraphics();
        Detection detection = new Detection("person", 0.87, new BoundingBox(0.5, 0.5, 0.2, 0.2), MODEL);

        painter.draw(recording, WIDTH, HEIGHT, detection);

        assertEquals(5, recording.fillRectCalls.size());
        assertTrue(recording.drawCalls.isEmpty());
    }

    @Test
    void coastingTrackDrawsDashedBorderViaGraphicsDrawNotFillRectStrips() {
        RecordingGraphics2D recording = recordingGraphics();
        TrackRef track = new TrackRef(7, TrackState.COASTING, DetectionSource.TRACKER);
        Detection detection = new Detection("person", 0.87, new BoundingBox(0.5, 0.5, 0.2, 0.2), MODEL, track);

        painter.draw(recording, WIDTH, HEIGHT, detection);

        // Only the label bar's fillRect must fire — the border itself must not use fillRect strips.
        assertEquals(1, recording.fillRectCalls.size(),
                "COASTING must not draw the border as fillRect strips (that is the solid path)");
        assertEquals(1, recording.drawCalls.size(),
                "COASTING border must be drawn exactly once via Graphics2D#draw(Shape)");

        Stroke stroke = recording.drawCalls.get(0).strokeAtCallTime();
        BasicStroke basicStroke = assertInstanceOf(BasicStroke.class, stroke,
                "the recorded draw call's stroke must be a dashed BasicStroke");
        assertNotNull(basicStroke.getDashArray(), "COASTING border stroke must be dashed");
    }

    private static RecordingGraphics2D recordingGraphics() {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        return new RecordingGraphics2D(image.createGraphics());
    }
}
