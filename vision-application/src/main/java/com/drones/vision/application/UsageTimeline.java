package com.drones.vision.application;

import com.drones.vision.domain.model.AssetUsage;
import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.model.Telemetry;

import java.time.Instant;
import java.util.List;

/**
 * A merged, time-ordered replay window over one {@link AssetUsage}: the usage's own facts plus
 * its telemetry and detection history within {@code [from, to]}, each independently downsampled
 * to fit a response budget (see {@link ReplayService}).
 *
 * @param usage      the usage this window belongs to
 * @param from       the window's resolved lower bound (never {@code null} — defaulted by {@link
 *                   ReplayService} to the usage's own start when the caller didn't supply one)
 * @param to         the window's resolved upper bound (never {@code null} — defaulted to the
 *                   usage's end, or "now" for a still-open usage)
 * @param telemetry  telemetry samples in {@code [from, to]}, ascending by {@link Telemetry#at()},
 *                   thinned to the requested {@code maxPoints}; defensively copied
 * @param detections detection results in {@code [from, to]}; always empty today — see {@link
 *                   ReplayService}'s javadoc for why; defensively copied
 */
public record UsageTimeline(AssetUsage usage, Instant from, Instant to, List<Telemetry> telemetry,
                             List<DetectionResult> detections) {

    public UsageTimeline {
        if (usage == null) {
            throw new IllegalArgumentException("UsageTimeline usage must not be null");
        }
        if (from == null) {
            throw new IllegalArgumentException("UsageTimeline from must not be null");
        }
        if (to == null) {
            throw new IllegalArgumentException("UsageTimeline to must not be null");
        }
        if (telemetry == null) {
            throw new IllegalArgumentException("UsageTimeline telemetry must not be null");
        }
        if (detections == null) {
            throw new IllegalArgumentException("UsageTimeline detections must not be null");
        }
        telemetry = List.copyOf(telemetry);
        detections = List.copyOf(detections);
    }
}
