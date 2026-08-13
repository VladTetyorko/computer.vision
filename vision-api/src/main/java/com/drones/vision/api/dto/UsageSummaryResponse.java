package com.drones.vision.api.dto;

import com.drones.vision.warehouse.application.usage.UsageSummary;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * One row of {@code GET /api/usages} (docs/plans/done/NAV-IA-REDESIGN-PLAN.md Wave 4, F8, docs/extracts/design/
 * 10-replay.md's frozen wire contract) — the "replay library" list.
 *
 * <p>{@code endedAt}/{@code durationSeconds} are omitted from the JSON entirely (rather than
 * serialized as {@code null}) for a still-open usage — the same convention {@link
 * AssetUsageResponse}/{@link UsageRecordingResponse} already use for their own nullable fields.
 *
 * @param usageId          usage identity, as a canonical UUID string
 * @param assetId          the owning asset's id, as a canonical UUID string
 * @param assetName        the owning asset's display name; {@code ""} when the asset can no longer
 *                         be resolved (see {@link UsageSummary}'s own javadoc)
 * @param startedAt        when the usage was opened
 * @param endedAt          when the usage was closed, or absent if still open
 * @param durationSeconds  whole seconds between {@code startedAt} and {@code endedAt}, or absent
 *                         while still open
 * @param sampleCount      number of telemetry samples received during this usage
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UsageSummaryResponse(String usageId, String assetId, String assetName, Instant startedAt,
                                    Instant endedAt, Long durationSeconds, long sampleCount) {

    /**
     * Maps an application-layer {@link UsageSummary} to its wire representation.
     *
     * @param summary the row to map
     * @return the response body element for {@code summary}
     */
    public static UsageSummaryResponse from(UsageSummary summary) {
        return new UsageSummaryResponse(
                summary.usageId().value().toString(),
                summary.assetId().value().toString(),
                summary.assetName(),
                summary.startedAt(),
                summary.endedAt(),
                summary.durationSeconds(),
                summary.sampleCount());
    }
}
