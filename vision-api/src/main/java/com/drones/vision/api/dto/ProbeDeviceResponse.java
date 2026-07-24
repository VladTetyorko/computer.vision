package com.drones.vision.api.dto;

import com.drones.vision.application.ProbeResult;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Base64;
import java.util.List;

/**
 * Response body for {@code POST /api/devices/probe} — CONTRACT 1 (docs/UX-REWORK-PLAN.md §U-d
 * item 3): a successful probe never omits {@code ok}, so a caller can tell a 200 apart from a
 * (differently-shaped) error body without inspecting the HTTP status alone.
 *
 * @param ok                always {@code true} — a failed probe never reaches this type, it
 *                          throws instead (see {@link com.drones.vision.api.DeviceProbeController})
 * @param widthPx           the grabbed frame's width, in pixels
 * @param heightPx          the grabbed frame's height, in pixels
 * @param codec             best-effort codec label, or absent when not knowable from a single
 *                          decoded frame (see {@link ProbeResult#codec()})
 * @param fps               a configured/requested frame-rate hint, or absent when the descriptor
 *                          carries none (see {@link ProbeResult#fps()}) — never a measurement
 * @param telemetryDetected whether a telemetry source was found and produced a sample
 * @param frameJpegBase64   the grabbed frame, JPEG-encoded and Base64-encoded, downscaled the same
 *                          way {@code GET /api/streams/{streamId}/snapshot} downscales its own
 *                          preview (see {@code SnapshotJpegEncoder})
 * @param warnings          human-readable, non-fatal notices (e.g. "No telemetry detected — OSD
 *                          unavailable"); always present, possibly empty
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProbeDeviceResponse(boolean ok, int widthPx, int heightPx, String codec, Integer fps,
                                   boolean telemetryDetected, String frameJpegBase64, List<String> warnings) {

    /**
     * Maps a {@link ProbeResult} plus its already-encoded JPEG preview to the wire response.
     *
     * @param result    the probe's result
     * @param jpegBytes the JPEG-encoded preview frame (see {@code SnapshotJpegEncoder#encode})
     * @return the response body for a successful probe
     */
    public static ProbeDeviceResponse from(ProbeResult result, byte[] jpegBytes) {
        return new ProbeDeviceResponse(true, result.frame().width(), result.frame().height(), result.codec(),
                result.fps(), result.telemetryDetected(), Base64.getEncoder().encodeToString(jpegBytes),
                result.warnings());
    }
}
