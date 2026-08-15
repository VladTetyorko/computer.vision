package com.drones.vision.api.dto;

import com.drones.vision.warehouse.application.asset.AssetStats;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Response body for {@code GET /api/assets/{assetId}/stats} (docs/plans/done/ASSET-MANAGER-PAGE-PLAN.md,
 * Wave A's frozen wire contract) — the manager page's KPI tile row.
 *
 * <p>Mirrors {@link AssetStats} field-for-field. {@code firstFlownAt}/{@code lastFlownAt}/{@code
 * avgFlightSeconds}/{@code lastKnownBatteryPercent} are omitted from the JSON entirely (rather
 * than serialized {@code null}) when the underlying value is honestly unavailable — no flights
 * fetched, no closed flights, or no telemetry ever reported, respectively.
 *
 * @param totalFlightSeconds      sum of every fetched usage's duration, in whole seconds
 * @param flightCount             number of usages fetched for the asset
 * @param firstFlownAt            the earliest usage's start, present only if the asset has flown
 * @param lastFlownAt             the most recent flight activity instant, present only if the
 *                                 asset has flown
 * @param avgFlightSeconds        mean duration of closed flights only, present only if at least
 *                                 one flight has closed
 * @param lastKnownBatteryPercent the freshest telemetry sample's battery reading, present only if
 *                                 the asset has ever reported telemetry
 * @param flightInProgress        whether the asset currently has an open (in-flight) usage
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssetStatsResponse(long totalFlightSeconds, int flightCount, Instant firstFlownAt, Instant lastFlownAt,
                                  Long avgFlightSeconds, Integer lastKnownBatteryPercent, boolean flightInProgress) {

    /**
     * Maps an {@link AssetStats} read model to its wire representation.
     *
     * @param stats the aggregated stats
     * @return the response body
     */
    public static AssetStatsResponse from(AssetStats stats) {
        return new AssetStatsResponse(stats.totalFlightSeconds(), stats.flightCount(), stats.firstFlownAt(),
                stats.lastFlownAt(), stats.avgFlightSeconds(), stats.lastKnownBatteryPercent(),
                stats.flightInProgress());
    }
}
