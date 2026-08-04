package com.drones.vision.application.usage;

import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.UsageId;

import java.time.Instant;

/**
 * One row of the fleet-wide "replay library" list (docs/NAV-IA-REDESIGN-PLAN.md Wave 4, F8) — the
 * read side behind {@code GET /api/usages}: enough facts to pick a finished (or still-open) flight
 * without opening it.
 *
 * @param usageId          typed usage identity
 * @param assetId          the asset this usage belongs to
 * @param assetName        the owning asset's {@code displayName}, resolved for display; {@code ""}
 *                         (never {@code null}) when the asset can no longer be resolved — see
 *                         {@code DefaultUsageService} for exactly when that happens
 * @param startedAt        when the usage was opened
 * @param endedAt          when the usage was closed, or {@code null} if still open
 * @param durationSeconds  whole seconds between {@code startedAt} and {@code endedAt}, or {@code
 *                         null} while the usage is still open
 * @param sampleCount      number of telemetry samples received during this usage; must not be
 *                         negative
 */
public record UsageSummary(UsageId usageId, AssetId assetId, String assetName, Instant startedAt, Instant endedAt,
                            Long durationSeconds, long sampleCount) {

    public UsageSummary {
        if (usageId == null) {
            throw new IllegalArgumentException("UsageSummary usageId must not be null");
        }
        if (assetId == null) {
            throw new IllegalArgumentException("UsageSummary assetId must not be null");
        }
        if (assetName == null) {
            throw new IllegalArgumentException("UsageSummary assetName must not be null");
        }
        if (startedAt == null) {
            throw new IllegalArgumentException("UsageSummary startedAt must not be null");
        }
        if (durationSeconds != null && durationSeconds < 0) {
            throw new IllegalArgumentException(
                    "UsageSummary durationSeconds must not be negative: " + durationSeconds);
        }
        if (sampleCount < 0) {
            throw new IllegalArgumentException("UsageSummary sampleCount must not be negative: " + sampleCount);
        }
    }
}
