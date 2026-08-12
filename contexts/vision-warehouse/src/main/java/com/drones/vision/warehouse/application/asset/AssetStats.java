package com.drones.vision.warehouse.application.asset;

import java.time.Instant;

/**
 * One asset's aggregated flight-utilization stats (docs/plans/done/ASSET-MANAGER-PAGE-PLAN.md, Wave A) — the
 * read model behind the manager page's KPI tile row: {@code AssetStatsService}'s output, mapped
 * essentially field-for-field onto {@code vision-api}'s {@code AssetStatsResponse}.
 *
 * <p>Every nullable field here is honestly {@code null}, never a fabricated {@code 0}, when the
 * asset has no flights (or no telemetry) to report — see each field's own javadoc for exactly
 * when. {@code totalFlightSeconds}/{@code flightCount} are the only two counters that are always
 * present, since "zero flights" is itself a real, reportable answer rather than an unknown one.
 *
 * @param totalFlightSeconds     sum of every fetched usage's duration, in whole seconds — an open
 *                                usage ({@code endedAt == null}) counts toward this up to "now";
 *                                {@code 0} (never negative) for an asset with no usages
 * @param flightCount            number of usages fetched for the asset; {@code 0} (never
 *                                negative) for an asset with no usages
 * @param firstFlownAt           the earliest usage's {@code startedAt}, or {@code null} if the
 *                                asset has no usages
 * @param lastFlownAt            the most recent activity instant across every usage — a closed
 *                                usage's {@code endedAt}, or an open usage's own {@code
 *                                startedAt} — or {@code null} if the asset has no usages
 * @param avgFlightSeconds       mean duration (whole seconds) of <b>closed</b> usages only (an
 *                                open usage has no final duration to average in); {@code null} if
 *                                there are no closed usages, even when an open one exists
 * @param lastKnownBatteryPercent the freshest telemetry sample's battery reading, rounded to the
 *                                nearest whole percent, or {@code null} if the asset has never
 *                                reported telemetry — see {@code DefaultAssetStatsService} for
 *                                exactly which source this reuses
 * @param flightInProgress       {@code true} iff at least one fetched usage is still open ({@code
 *                                endedAt == null})
 */
public record AssetStats(long totalFlightSeconds, int flightCount, Instant firstFlownAt, Instant lastFlownAt,
                          Long avgFlightSeconds, Integer lastKnownBatteryPercent, boolean flightInProgress) {

    public AssetStats {
        if (totalFlightSeconds < 0) {
            throw new IllegalArgumentException(
                    "AssetStats totalFlightSeconds must not be negative: " + totalFlightSeconds);
        }
        if (flightCount < 0) {
            throw new IllegalArgumentException("AssetStats flightCount must not be negative: " + flightCount);
        }
        if (avgFlightSeconds != null && avgFlightSeconds < 0) {
            throw new IllegalArgumentException(
                    "AssetStats avgFlightSeconds must not be negative: " + avgFlightSeconds);
        }
    }
}
