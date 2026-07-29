package com.drones.vision.application;

import com.drones.vision.domain.model.UsageId;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Serves a scrubbable replay window over one finished (or still-open) {@link
 * com.drones.vision.domain.model.AssetUsage} — the read side of flight replay (docs/MVP2-PLAN.md
 * §R, R-a).
 *
 * <h2>Windowing</h2>
 * {@code from}/{@code to} are both nullable: a {@code null} bound defaults to the usage's own
 * {@code startedAt}/{@code endedAt}, and a still-open usage (no {@code endedAt}) defaults its
 * upper bound to "now" at call time.
 *
 * <h2>Downsampling</h2>
 * Telemetry and detections are each independently thinned to at most {@code maxPoints} points via
 * equidistant-index thinning that always keeps the first and last point in range, so a long
 * flight's response stays bounded regardless of sample density.
 *
 * <h2>Detections (docs/MVP2-PLAN.md §R, R-a2)</h2>
 * {@link com.drones.vision.domain.model.AssetUsage#streamId()} — recorded once, at open time, by
 * {@code UsageTracker} — is the join key: when it is non-{@code null}, {@link
 * UsageTimeline#detections()} is a real, time-windowed, downsampled query against {@link
 * com.drones.vision.domain.port.out.DetectionRepositoryPort} for that exact stream, so it can never
 * mix in another asset's/stream's detections. A {@code null} {@code streamId()} — a usage opened
 * before this field existed, or by an asset with no video device — still yields an honestly empty
 * list; there remains no reliable join to fall back to for those. See {@code
 * DefaultReplayService}'s javadoc for the query shape and its own honest limitations.
 */
public interface ReplayService {

    /**
     * Builds a replay window for one usage.
     *
     * @param usageId   the usage to replay
     * @param from      inclusive lower bound, or {@code null} to default to the usage's {@code
     *                  startedAt}
     * @param to        inclusive upper bound, or {@code null} to default to the usage's {@code
     *                  endedAt} (or "now" if still open)
     * @param maxPoints maximum points per series after downsampling; must be positive, silently
     *                  clamped to an internal ceiling if larger
     * @return the merged, time-ordered replay window
     * @throws NoSuchElementException  if no usage exists with {@code usageId}
     * @throws IllegalArgumentException if {@code maxPoints} is not positive, or {@code to} is
     *                                   before the resolved {@code from}
     */
    UsageTimeline timeline(UsageId usageId, Instant from, Instant to, int maxPoints);

    /**
     * Resolves a recording/clip-export URL for one usage's flight window (docs/OPS-CORE-PLAN.md
     * §R): {@code start} is the usage's {@code startedAt}, {@code duration} runs to its {@code
     * endedAt} (or "now" for a still-open usage), and the actual URL comes from {@code
     * StreamPublisherPort#playbackUrl} for that window.
     *
     * <p>This is an honest-cheap check (configuration presence, not a round trip to the media
     * server) — {@link Optional#empty()} covers both "this usage never had a video stream"
     * ({@code streamId() == null}) and "the configured stream publisher has no recording/playback
     * endpoint at all", never an error.
     *
     * @param usageId the usage to resolve a recording for
     * @return the recording, or {@link Optional#empty()} when none is available
     * @throws NoSuchElementException if no usage exists with {@code usageId}
     */
    Optional<UsageRecording> recordingFor(UsageId usageId);
}
