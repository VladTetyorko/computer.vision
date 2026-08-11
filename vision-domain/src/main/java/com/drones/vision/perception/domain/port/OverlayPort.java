package com.drones.vision.perception.domain.port;

import com.drones.vision.perception.domain.model.AnnotatedFrame;
import com.drones.vision.perception.domain.model.VideoFrame;

/**
 * Driven port: render detections (and optional telemetry) onto a frame.
 *
 * <h2>Contract</h2>
 * Given an {@link AnnotatedFrame} — a video frame paired with the detections
 * and telemetry to draw — returns a new {@link VideoFrame} with those
 * annotations burned into the pixel data. The input frame is never
 * mutated (frames are immutable, see {@code VideoFrame}); the returned
 * frame is a distinct instance.
 *
 * <p>Detection is decoupled from rendering: inference runs only on sampled
 * frames (at {@code inferenceFps}), while every frame on the video path is
 * expected to be run through this port using the <i>latest known</i>
 * detections (interpolated onto frames between inference results by the
 * caller). This lets inference FPS scale independently of video FPS —
 * the overlay stays smooth even when detection is comparatively slow or
 * sparse.
 *
 * <h2>Threading</h2>
 * Implementations should be safe to invoke from the per-stream pipeline
 * thread that owns the frame; rendering is expected to be fast, synchronous
 * pixel manipulation (no network calls), so this port does not use an
 * asynchronous return type.
 */
public interface OverlayPort {

    /**
     * Renders the given annotations onto a copy of the underlying frame.
     *
     * @param annotated frame plus detections/telemetry to render
     * @return a new frame with annotations burned in
     */
    VideoFrame render(AnnotatedFrame annotated);
}
