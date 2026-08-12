package com.drones.vision.perception.application.device;

import com.drones.vision.perception.domain.model.VideoFrame;

import java.util.List;

/**
 * What {@link ProbeService#probe} found: the one decoded frame it grabbed, plus whatever metadata
 * could honestly be derived from it (UX-DESIGN.md §5.1's "1280&times;720 &middot; H.264 &middot;
 * 24 fps" test-step readout — see this record's own field docs for exactly which of those this
 * single-frame probe can and cannot honestly answer).
 *
 * @param frame             the grabbed frame; {@link VideoFrame#width()}/{@link
 *                          VideoFrame#height()} are the resolution to report — never downscaled,
 *                          that only happens to the preview thumbnail the API layer derives from
 *                          this frame
 * @param codec             a best-effort codec label derived from {@link
 *                          com.drones.vision.perception.domain.model.PixelFormat}, or {@code null} when it
 *                          isn't knowable from that alone. A raw, already-decoded pixel format
 *                          (e.g. {@code BGR24}) carries no memory of the wire codec that produced
 *                          it (H.264, H.265, ...) once an adapter has decoded it — this is an
 *                          honest gap, not a bug: see {@link DefaultProbeService#codecFor}
 * @param fps               a configured/requested frame-rate hint read from the descriptor's own
 *                          {@code options} (e.g. adapter-simulation's {@code fps} option), or
 *                          {@code null} when the descriptor carries no such hint. Never a
 *                          <em>measured</em> rate — grabbing exactly one frame gives no second
 *                          timestamp to measure an interval from
 * @param telemetryDetected whether a registered telemetry source claims this exact connection
 *                          (protocol + capability) and produced a sample within a short bounded
 *                          wait; {@code false} whenever no adapter even claims the protocol (the
 *                          common case for video-only protocols) or none produced a sample in time
 * @param warnings          human-readable, non-fatal notices (UX-DESIGN §5.1's "&#9888; No
 *                          telemetry detected"); defensively copied
 */
public record ProbeResult(VideoFrame frame, String codec, Integer fps, boolean telemetryDetected,
                           List<String> warnings) {

    public ProbeResult {
        if (frame == null) {
            throw new IllegalArgumentException("ProbeResult frame must not be null");
        }
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
