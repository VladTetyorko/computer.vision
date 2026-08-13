package com.drones.vision.perception.domain.model;

import com.drones.vision.kernel.Telemetry;
import java.util.List;

/**
 * A frame paired with the detections (and optional telemetry) to be rendered
 * onto it by {@code OverlayPort}.
 *
 * <p>Detection is decoupled from rendering: inference runs on sampled
 * frames, while the overlay renders every frame using the latest known
 * detections, which is what lets inference FPS scale independently of video
 * FPS. {@code detections} is defensively copied to an immutable list;
 * {@code telemetry} is nullable since not every stream has a telemetry
 * source.
 *
 * @param frame       the video frame to annotate
 * @param detections  detections to render, interpolated/latest-known by the caller; defensively copied
 * @param telemetry   telemetry to overlay, or {@code null} if unavailable/disabled
 */
public record AnnotatedFrame(VideoFrame frame, List<Detection> detections, Telemetry telemetry) {

    public AnnotatedFrame {
        if (frame == null) {
            throw new IllegalArgumentException("AnnotatedFrame frame must not be null");
        }
        if (detections == null) {
            throw new IllegalArgumentException("AnnotatedFrame detections must not be null");
        }
        detections = List.copyOf(detections);
    }
}
