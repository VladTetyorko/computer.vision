package com.drones.vision.api.dto;

import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Response body element for an asset's usage history, embedded in {@code
 * AssetDetailsResponse#recentUsages()}.
 *
 * <p>{@code endedAt}, {@code startPosition}, and {@code lastPosition} are
 * omitted from the JSON entirely (rather than serialized as {@code null})
 * for a still-open usage or one that has not yet received a positioned
 * telemetry sample.
 *
 * @param usageId       usage identity, as a canonical UUID string
 * @param startedAt     when the usage was opened
 * @param endedAt       when the usage was closed, or absent if still open
 * @param startPosition position at the first received sample, or absent if none yet
 * @param lastPosition  position at the most recently received sample, or absent if none yet
 * @param sampleCount   number of telemetry samples received during this usage
 * @param phase         which part of a flight this usage is in (docs/plans/active/DRONE-ONBOARDING-PLAN.md
 *                      O7) — {@code PREFLIGHT} until the aircraft first arms. Exposed because a
 *                      phase computed and stored but never served is one nothing can act on; §8.1
 *                      also names {@code firstArmedAt}/{@code lastDisarmedAt}, which do not exist on
 *                      the domain record yet (their columns do — see storage/persistence)
 * @param origin        which verb opened this session — {@code STREAM}, {@code TELEMETRY}, or
 *                      {@code OPERATOR} (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D2,
 *                      wave R2). Exposed for the same reason {@code phase} is: a future UI wave
 *                      needs to tell an operator-engaged session apart from a stream-opened one to
 *                      render {@code AssetSessionController#disengage} only where it applies
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssetUsageResponse(String usageId, Instant startedAt, Instant endedAt,
                                  GeoPositionResponse startPosition, GeoPositionResponse lastPosition,
                                  long sampleCount, String phase, String origin) {

    /**
     * Maps a domain {@link AssetUsage} to its wire representation.
     *
     * @param usage the usage to map
     * @return the response body element for {@code usage}
     */
    public static AssetUsageResponse from(AssetUsage usage) {
        return new AssetUsageResponse(
                usage.id().value().toString(),
                usage.startedAt(),
                usage.endedAt(),
                GeoPositionResponse.from(usage.startPosition()),
                GeoPositionResponse.from(usage.lastPosition()),
                usage.sampleCount(),
                usage.phase() == null ? null : usage.phase().name(),
                usage.origin() == null ? null : usage.origin().name());
    }
}
