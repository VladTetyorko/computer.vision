package com.drones.vision.api.dto;

import com.drones.vision.application.replay.UsageTimeline;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code GET /api/usages/{usageId}/timeline} (docs/plans/done/MVP2-PLAN.md §R, R-a): a
 * merged, time-ordered replay window over one usage.
 *
 * <p>No field here is ever absent — {@code from}/{@code to} are always resolved (defaulted
 * server-side when the caller omits them), and both list fields are always present, possibly
 * empty — so this record carries no {@code @JsonInclude(NON_NULL)}, same as {@link
 * DetectionResultResponse}.
 *
 * @param usage      the replayed usage's own facts, mirroring {@code AssetDetailsResponse}'s
 *                   embedded usage rows
 * @param from       the window's resolved lower bound
 * @param to         the window's resolved upper bound
 * @param telemetry  telemetry samples in {@code [from, to]}, ascending by time, downsampled to
 *                   the request's {@code maxPoints}
 * @param detections detection results in {@code [from, to]} — always empty today; see {@code
 *                   ReplayService}'s javadoc (vision-application) for the honest reason why
 */
public record UsageTimelineResponse(AssetUsageResponse usage, Instant from, Instant to,
                                     List<TelemetrySampleResponse> telemetry,
                                     List<DetectionResultResponse> detections) {

    /**
     * Maps an application-layer {@link UsageTimeline} to its wire representation.
     *
     * @param timeline the timeline to map
     * @return the response body for {@code timeline}
     */
    public static UsageTimelineResponse from(UsageTimeline timeline) {
        return new UsageTimelineResponse(
                AssetUsageResponse.from(timeline.usage()),
                timeline.from(),
                timeline.to(),
                timeline.telemetry().stream().map(TelemetrySampleResponse::from).toList(),
                timeline.detections().stream().map(DetectionResultResponse::from).toList());
    }
}
