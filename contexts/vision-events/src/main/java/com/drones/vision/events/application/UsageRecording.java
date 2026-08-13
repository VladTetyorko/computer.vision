package com.drones.vision.events.application;

import java.net.URI;
import java.time.Instant;

/**
 * A resolved recording/clip-export URL for one usage's flight window (docs/plans/done/OPS-CORE-PLAN.md §R) —
 * {@link ReplayService#recordingFor}'s read model.
 *
 * <p>{@code durationSeconds} is whole seconds ({@link java.time.Duration#getSeconds()}) between
 * the usage's {@code startedAt} and its {@code endedAt} (or "now" for a still-open usage) — the
 * exact window {@code url} covers, per {@code StreamPublisherPort#playbackUrl}'s contract.
 *
 * @param url             the recording/playback URL for {@code [start, start + durationSeconds)},
 *                        as resolved by {@code StreamPublisherPort#playbackUrl}
 * @param start           the window's start (the usage's own {@code startedAt})
 * @param durationSeconds the window's length in whole seconds; not negative
 */
public record UsageRecording(URI url, Instant start, long durationSeconds) {

    public UsageRecording {
        if (url == null) {
            throw new IllegalArgumentException("UsageRecording url must not be null");
        }
        if (start == null) {
            throw new IllegalArgumentException("UsageRecording start must not be null");
        }
        if (durationSeconds < 0) {
            throw new IllegalArgumentException(
                    "UsageRecording durationSeconds must not be negative: " + durationSeconds);
        }
    }
}
