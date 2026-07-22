package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnnotatedFrameTest {

    private static VideoFrame frame() {
        return new VideoFrame(StreamId.random(), 0L, Instant.now(), 640, 480, PixelFormat.JPEG,
                ByteBuffer.wrap(new byte[]{1}));
    }

    private static Detection detection() {
        return new Detection("person", 0.9, new BoundingBox(0, 0, 0.1, 0.1), new ModelRef("yolo", "1"));
    }

    @Test
    void detectionsAreDefensivelyCopied() {
        List<Detection> detections = new ArrayList<>();
        detections.add(detection());

        AnnotatedFrame annotated = new AnnotatedFrame(frame(), detections, null);

        detections.add(detection());

        assertEquals(1, annotated.detections().size());
        assertThrows(UnsupportedOperationException.class, () -> annotated.detections().add(detection()));
    }

    @Test
    void telemetryIsNullable() {
        AnnotatedFrame annotated = new AnnotatedFrame(frame(), List.of(), null);
        assertNull(annotated.telemetry());
    }

    @Test
    void rejectsNullFrameOrDetections() {
        assertThrows(IllegalArgumentException.class, () -> new AnnotatedFrame(null, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new AnnotatedFrame(frame(), null, null));
    }
}
