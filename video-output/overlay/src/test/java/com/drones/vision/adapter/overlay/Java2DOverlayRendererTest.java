package com.drones.vision.adapter.overlay;

import com.drones.vision.perception.domain.model.AnnotatedFrame;
import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionSource;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.perception.domain.model.PixelFormat;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.perception.domain.model.TrackRef;
import com.drones.vision.perception.domain.model.TrackState;
import com.drones.vision.perception.domain.model.VideoFrame;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Java2DOverlayRendererTest {

    private static final StreamId STREAM_ID = StreamId.random();
    private static final ModelRef MODEL = new ModelRef("yolo", "v1");
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final byte GRAY = (byte) 128;

    private final Java2DOverlayRenderer renderer = new Java2DOverlayRenderer();

    // -- BGR24 -------------------------------------------------------------

    @Test
    void bgr24BoxCornersLandAtLabelColorAndInputBufferIsUntouched() {
        VideoFrame frame = solidBgr24Frame(WIDTH, HEIGHT, GRAY);
        byte[] originalCopy = bytesOf(frame.data());

        // 0.5/0.5 origin, 0.2/0.2 size => pixel rect [320,240)-(448,336)
        Detection detection = new Detection("person", 0.87, new BoundingBox(0.5, 0.5, 0.2, 0.2), MODEL);
        AnnotatedFrame annotated = new AnnotatedFrame(frame, List.of(detection), null);

        VideoFrame result = renderer.render(annotated);

        assertNotSame(frame, result);
        assertEquals(frame.streamId(), result.streamId());
        assertEquals(frame.sequence(), result.sequence());
        assertEquals(frame.capturedAt(), result.capturedAt());
        assertEquals(frame.width(), result.width());
        assertEquals(frame.height(), result.height());
        assertEquals(PixelFormat.BGR24, result.format());

        Color expected = Java2DOverlayRenderer.colorForLabel("person");
        assertBgrPixel(result, 320, 240, expected); // top-left corner of the box
        assertBgrPixel(result, 447, 335, expected); // bottom-right corner of the box

        // The input frame's own buffer must be unchanged.
        assertArrayEquals(originalCopy, bytesOf(frame.data()));
    }

    @Test
    void boxAtFrameTopEdgeStillDrawsBorderWithoutThrowing() {
        // y=0.0 means the label bar (normally above the box) has no room and must fall back
        // to drawing inside the box's top edge instead of at a negative y.
        VideoFrame frame = solidBgr24Frame(WIDTH, HEIGHT, GRAY);
        Detection detection = new Detection("car", 0.5, new BoundingBox(0.1, 0.0, 0.3, 0.3), MODEL);
        AnnotatedFrame annotated = new AnnotatedFrame(frame, List.of(detection), null);

        VideoFrame result = assertDoesNotThrow(() -> renderer.render(annotated));

        Color expected = Java2DOverlayRenderer.colorForLabel("car");
        assertBgrPixel(result, 64, 0, expected); // box top-left corner, y pixel = 0
    }

    @Test
    void boxTouchingTheFramesFarEdgeDoesNotThrow() {
        VideoFrame frame = solidBgr24Frame(WIDTH, HEIGHT, GRAY);
        // x=1.0 / y=1.0 are valid (BoundingBox allows the [0,1] boundary) but push the box
        // entirely outside the frame once converted to pixels.
        Detection detection = new Detection("truck", 0.6, new BoundingBox(1.0, 1.0, 0.2, 0.2), MODEL);
        AnnotatedFrame annotated = new AnnotatedFrame(frame, List.of(detection), null);

        assertDoesNotThrow(() -> renderer.render(annotated));
    }

    @Test
    void emptyDetectionsAndNullTelemetryReturnTheSameInstance() {
        VideoFrame frame = solidBgr24Frame(WIDTH, HEIGHT, GRAY);
        AnnotatedFrame annotated = new AnnotatedFrame(frame, List.of(), null);

        VideoFrame result = renderer.render(annotated);

        assertSame(frame, result);
    }

    @Test
    void unsupportedPixelFormatPassesThroughUnchangedWithoutThrowing() {
        VideoFrame frame = new VideoFrame(STREAM_ID, 0L, Instant.now(), WIDTH, HEIGHT,
                PixelFormat.YUV420P, ByteBuffer.wrap(new byte[WIDTH * HEIGHT * 2]));
        Detection detection = new Detection("person", 0.9, new BoundingBox(0.1, 0.1, 0.2, 0.2), MODEL);
        AnnotatedFrame annotated = new AnnotatedFrame(frame, List.of(detection), null);

        VideoFrame result = assertDoesNotThrow(() -> renderer.render(annotated));

        assertSame(frame, result);
    }

    @Test
    void labelColorIsStableAcrossCallsAndDiffersBetweenKnownDifferentBuckets() {
        // "a".hashCode()=97, floorMod(97,8)=1 ; "b".hashCode()=98, floorMod(98,8)=2 — different buckets.
        Color first = Java2DOverlayRenderer.colorForLabel("a");
        Color second = Java2DOverlayRenderer.colorForLabel("a");
        Color other = Java2DOverlayRenderer.colorForLabel("b");

        assertEquals(first, second);
        assertNotEquals(first, other);
    }

    // -- tracking (docs/plans/done/TRACKING-PLAN.md §7, wave T5) -------------------------

    @Test
    void trackColorIsStableAcrossCallsAndDiffersBetweenKnownDifferentBuckets() {
        // floorMod(1,8)=1 ; floorMod(2,8)=2 — different buckets, same formula colorForLabel uses.
        Color first = Java2DOverlayRenderer.colorForTrack(1);
        Color second = Java2DOverlayRenderer.colorForTrack(1);
        Color other = Java2DOverlayRenderer.colorForTrack(2);

        assertEquals(first, second);
        assertNotEquals(first, other);
    }

    @Test
    void untrackedDetectionRendersByteIdenticalWhetherTrackIsExplicitOrDefaultedNull() {
        // The 4-arg convenience ctor (today's call shape, used by every pre-tracking test above)
        // must render pixel-for-pixel identically to the new 5-arg ctor with an explicit null
        // track — "untracked" has exactly one spelling, and neither call site may look different
        // to the renderer (docs/extracts/TRACKING-ORCHESTRATION.md §6 rule 2).
        VideoFrame frameA = solidBgr24Frame(WIDTH, HEIGHT, GRAY);
        VideoFrame frameB = solidBgr24Frame(WIDTH, HEIGHT, GRAY);
        Detection viaConvenienceCtor = new Detection("person", 0.87, new BoundingBox(0.5, 0.5, 0.2, 0.2), MODEL);
        Detection viaExplicitNullTrack = new Detection("person", 0.87, new BoundingBox(0.5, 0.5, 0.2, 0.2), MODEL, null);

        VideoFrame resultA = renderer.render(new AnnotatedFrame(frameA, List.of(viaConvenienceCtor), null));
        VideoFrame resultB = renderer.render(new AnnotatedFrame(frameB, List.of(viaExplicitNullTrack), null));

        assertArrayEquals(bytesOf(resultA.data()), bytesOf(resultB.data()));
    }

    @Test
    void trackedDetectionBoxColorsByTrackIdNotLabel() {
        // "person" hashes to bucket 5 (floorMod(-991716523, 8) = 5); track id 3 hashes to bucket 3
        // — deliberately different buckets, so this proves colorForTrack wins over colorForLabel
        // for a tracked detection, rather than merely being consistent with it by coincidence.
        Color labelColor = Java2DOverlayRenderer.colorForLabel("person");
        Color trackColor = Java2DOverlayRenderer.colorForTrack(3);
        assertNotEquals(labelColor, trackColor, "fixture assumption: label/track colors must differ");

        VideoFrame frame = solidBgr24Frame(WIDTH, HEIGHT, GRAY);
        TrackRef track = new TrackRef(3, TrackState.CONFIRMED, DetectionSource.TRACKER);
        Detection detection = new Detection("person", 0.87, new BoundingBox(0.5, 0.5, 0.2, 0.2), MODEL, track);
        AnnotatedFrame annotated = new AnnotatedFrame(frame, List.of(detection), null);

        VideoFrame result = renderer.render(annotated);

        assertBgrPixel(result, 320, 240, trackColor); // top-left corner of the box
    }

    @Test
    void twoTracksWithDifferentIdsGetDifferentBoxColors() {
        VideoFrame frameOne = solidBgr24Frame(WIDTH, HEIGHT, GRAY);
        VideoFrame frameTwo = solidBgr24Frame(WIDTH, HEIGHT, GRAY);
        TrackRef trackOne = new TrackRef(1, TrackState.CONFIRMED, DetectionSource.TRACKER);
        TrackRef trackTwo = new TrackRef(2, TrackState.CONFIRMED, DetectionSource.TRACKER);
        Detection detectionOne = new Detection("car", 0.9, new BoundingBox(0.5, 0.5, 0.2, 0.2), MODEL, trackOne);
        Detection detectionTwo = new Detection("car", 0.9, new BoundingBox(0.5, 0.5, 0.2, 0.2), MODEL, trackTwo);

        VideoFrame resultOne = renderer.render(new AnnotatedFrame(frameOne, List.of(detectionOne), null));
        VideoFrame resultTwo = renderer.render(new AnnotatedFrame(frameTwo, List.of(detectionTwo), null));

        assertBgrPixel(resultOne, 320, 240, Java2DOverlayRenderer.colorForTrack(1));
        assertBgrPixel(resultTwo, 320, 240, Java2DOverlayRenderer.colorForTrack(2));
        assertNotEquals(Java2DOverlayRenderer.colorForTrack(1), Java2DOverlayRenderer.colorForTrack(2));
    }

    @Test
    void telemetryOnlyFrameChangesPixelsInTopLeftCorner() {
        VideoFrame frame = solidBgr24Frame(WIDTH, HEIGHT, (byte) 255); // white, so an alpha-blended OSD is detectable
        Telemetry telemetry = new Telemetry(DeviceId.random(), Instant.now(),
                48.85837, 2.29448, 120.5, 90.0, 76.0, Map.of());
        AnnotatedFrame annotated = new AnnotatedFrame(frame, List.of(), telemetry);

        VideoFrame result = renderer.render(annotated);

        assertNotSame(frame, result);
        int[] before = bgrAt(frame, 6, 6);
        int[] after = bgrAt(result, 6, 6);
        assertNotEquals(before[0], after[0], "top-left OSD block should have darkened the background");
    }

    @Test
    void telemetryFieldsSkipNulls() {
        VideoFrame frame = solidBgr24Frame(WIDTH, HEIGHT, (byte) 255);
        // Only battery is present; lat/lon/altitude are null and must be skipped, not crash.
        Telemetry telemetry = new Telemetry(DeviceId.random(), Instant.now(), null, null, null, null, 42.0, Map.of());
        AnnotatedFrame annotated = new AnnotatedFrame(frame, List.of(), telemetry);

        VideoFrame result = assertDoesNotThrow(() -> renderer.render(annotated));

        int[] before = bgrAt(frame, 6, 6);
        int[] after = bgrAt(result, 6, 6);
        assertNotEquals(before[0], after[0]);
    }

    // -- JPEG ----------------------------------------------------------------

    @Test
    void jpegOutputDecodesWithPreservedDimensionsAndVisibleDrawing() throws IOException {
        int width = 200;
        int height = 150;
        byte[] jpeg = solidJpeg(width, height, new Color(128, 128, 128));
        VideoFrame frame = new VideoFrame(STREAM_ID, 0L, Instant.now(), width, height,
                PixelFormat.JPEG, ByteBuffer.wrap(jpeg));

        Detection detection = new Detection("dog", 0.7, new BoundingBox(0.3, 0.3, 0.3, 0.3), MODEL);
        AnnotatedFrame annotated = new AnnotatedFrame(frame, List.of(detection), null);

        VideoFrame result = renderer.render(annotated);

        assertNotSame(frame, result);
        assertEquals(PixelFormat.JPEG, result.format());
        assertEquals(width, result.width());
        assertEquals(height, result.height());

        byte[] outputBytes = bytesOf(result.data());
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(outputBytes));
        assertEquals(width, decoded.getWidth());
        assertEquals(height, decoded.getHeight());

        // Box top-left pixel: 0.3*200=60, 0.3*150=45.
        int drawnRgb = decoded.getRGB(61, 46);
        int backgroundRgb = new Color(128, 128, 128).getRGB();
        assertNotEquals(backgroundRgb, drawnRgb, "drawn region should differ from the untouched background");
    }

    @Test
    void jpegWithUndecodableBytesPassesThroughUnchanged() {
        VideoFrame frame = new VideoFrame(STREAM_ID, 0L, Instant.now(), 4, 4,
                PixelFormat.JPEG, ByteBuffer.wrap(new byte[]{1, 2, 3, 4}));
        Detection detection = new Detection("cat", 0.4, new BoundingBox(0.1, 0.1, 0.2, 0.2), MODEL);
        AnnotatedFrame annotated = new AnnotatedFrame(frame, List.of(detection), null);

        VideoFrame result = assertDoesNotThrow(() -> renderer.render(annotated));

        assertSame(frame, result);
    }

    // -- fixtures ------------------------------------------------------------

    private static VideoFrame solidBgr24Frame(int width, int height, byte value) {
        byte[] pixels = new byte[width * height * 3];
        java.util.Arrays.fill(pixels, value);
        return new VideoFrame(STREAM_ID, 0L, Instant.now(), width, height, PixelFormat.BGR24, ByteBuffer.wrap(pixels));
    }

    private static byte[] solidJpeg(int width, int height, Color color) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "jpg", out)) {
            throw new IOException("No JPEG writer available");
        }
        return out.toByteArray();
    }

    private static byte[] bytesOf(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private static int[] bgrAt(VideoFrame frame, int x, int y) {
        ByteBuffer data = frame.data();
        int index = (y * frame.width() + x) * 3;
        int b = data.get(index) & 0xFF;
        int g = data.get(index + 1) & 0xFF;
        int r = data.get(index + 2) & 0xFF;
        return new int[]{b, g, r};
    }

    private static void assertBgrPixel(VideoFrame frame, int x, int y, Color expected) {
        int[] bgr = bgrAt(frame, x, y);
        assertEquals(expected.getBlue(), bgr[0], "blue channel at (" + x + "," + y + ")");
        assertEquals(expected.getGreen(), bgr[1], "green channel at (" + x + "," + y + ")");
        assertEquals(expected.getRed(), bgr[2], "red channel at (" + x + "," + y + ")");
    }
}
