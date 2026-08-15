package com.drones.vision.api.dto;

import com.drones.vision.events.application.UsageRecording;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

/**
 * Response body for {@code GET /api/usages/{usageId}/recording} (docs/plans/done/OPS-CORE-PLAN.md §R): is a
 * recorded clip available for one usage's flight window, and if so, where to fetch it.
 *
 * <p>{@code url}/{@code start}/{@code durationSeconds} are omitted entirely (rather than
 * serialized {@code null}) when {@code available} is {@code false} — a known usage with nothing
 * to play back is not an error, just an honest {@code {"available":false}}; the replay player's
 * own empty state handles it.
 *
 * @param available       whether a recording/clip-export URL could be resolved
 * @param url             the recording/playback URL, present only when {@code available}
 * @param start           the clip window's start, present only when {@code available}
 * @param durationSeconds the clip window's length in seconds, present only when {@code available}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UsageRecordingResponse(boolean available, URI url, Instant start, Long durationSeconds) {

    private static final UsageRecordingResponse UNAVAILABLE = new UsageRecordingResponse(false, null, null, null);

    /**
     * Maps a resolved (or absent) {@link UsageRecording} to its wire representation.
     *
     * @param recording the resolved recording, or {@link Optional#empty()} when none is available
     * @return the response body
     */
    public static UsageRecordingResponse from(Optional<UsageRecording> recording) {
        return recording
                .map(r -> new UsageRecordingResponse(true, r.url(), r.start(), r.durationSeconds()))
                .orElse(UNAVAILABLE);
    }
}
