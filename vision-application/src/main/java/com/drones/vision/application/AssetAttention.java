package com.drones.vision.application;

import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.LifecycleState;
import com.drones.vision.domain.model.StreamId;

/**
 * One asset's attention-relevant facts — one row of {@code GET /api/fleet/summary}'s per-asset list
 * (docs/MVP3-PLAN.md C-a): everything the manager's attention queue needs to decide "does this
 * asset need a look", without a second poll per asset.
 *
 * <p><b>{@code sourceState} was deliberately left out</b> (docs/MVP3-PLAN.md C-a's own instruction:
 * "if nothing is cleanly readable, omit the field and document rather than fake"). Today's {@link
 * com.drones.vision.domain.port.out.EventPublisherPort} is write-only — its only implementation
 * just logs, with no matching read/query port (see vision-api/MODULE.md's Gotchas, first documented
 * for {@code GET /api/events}'s inability to surface {@code PIPELINE_ERROR}) — and {@link
 * SupervisedPublisher}'s in-progress-outage/backoff state is private bookkeeping inside {@link
 * DefaultStreamService}'s per-stream map, never exposed through {@link StreamService} at all. There
 * is no honest "reconnecting"/"degraded" signal to read today; a future task that adds either a
 * queryable event store or a supervision-state read method can add this field then.
 *
 * <p>{@code flightMode}/{@code armed}/{@code failsafe} (docs/FC-INTEGRATIONS-PLAN.md F-b) are a
 * different case from {@code sourceState} above, not another exception to the same rule: they are
 * cleanly readable today, straight off the freshest telemetry sample's {@link
 * com.drones.vision.domain.model.FlightState}, with the same honest-null behavior as {@code
 * batteryPercent} when no sample (or no flight state on that sample) exists — so they were added,
 * not omitted.
 *
 * @param assetId        the asset
 * @param displayName    human-readable name
 * @param categoryId     the asset's category
 * @param categoryName   human-readable category name (mirrors {@link AssetSummary#categoryName()})
 * @param lifecycle      {@code ACTIVE}, {@code DEACTIVATED}, or {@code DELETED}
 * @param streaming      whether any of the asset's devices currently has an active stream
 * @param streamId       the streaming device's stream id, or {@code null} when {@code streaming} is
 *                       {@code false}. An asset with more than one simultaneously-streaming device
 *                       — not reachable through today's single-video-device-per-asset flows (see
 *                       {@code DefaultAssetService#resolveSingleVideoDevice}) — reports whichever
 *                       device its own {@code devices()} set is visited first, order otherwise
 *                       unspecified
 * @param batteryPercent the freshest telemetry sample's battery reading, or {@code null} if the
 *                       asset has never reported telemetry
 * @param telemetryAgeMs milliseconds since the freshest telemetry sample, or {@code null} under the
 *                       same condition as {@code batteryPercent} — deliberately still reported once
 *                       the asset stops streaming (see {@link UsageTracker#latestTelemetry}), since
 *                       staleness is exactly "how long since we last heard from this asset" and that
 *                       question is most useful once it has gone quiet
 * @param openEventCount how many {@code OPEN} detection events currently name this asset, within
 *                       {@link DefaultFleetSummaryService#OPEN_EVENTS_SCAN_LIMIT}'s scan window
 * @param flightMode     the freshest telemetry sample's flight-controller mode name (e.g.
 *                       {@code "RTL"}, {@code "Loiter"}), or {@code null} if the asset has never
 *                       reported telemetry, or has but with no {@code flightState} attached
 *                       (docs/FC-INTEGRATIONS-PLAN.md F-b) — same honest-null discipline as {@code
 *                       batteryPercent}, not a fabricated read: this is {@link
 *                       com.drones.vision.domain.model.FlightState#mode()} carried straight
 *                       through, never guessed at
 * @param armed          the freshest telemetry sample's armed flag, or {@code null} under the same
 *                       condition as {@code flightMode}
 * @param failsafe       the freshest telemetry sample's failsafe flag, or {@code null} under the
 *                       same condition as {@code flightMode}
 */
public record AssetAttention(AssetId assetId, String displayName, CategoryId categoryId, String categoryName,
                              LifecycleState lifecycle, boolean streaming, StreamId streamId,
                              Double batteryPercent, Long telemetryAgeMs, int openEventCount,
                              String flightMode, Boolean armed, Boolean failsafe) {

    public AssetAttention {
        if (assetId == null) {
            throw new IllegalArgumentException("AssetAttention assetId must not be null");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("AssetAttention displayName must not be blank");
        }
        if (categoryId == null) {
            throw new IllegalArgumentException("AssetAttention categoryId must not be null");
        }
        if (categoryName == null || categoryName.isBlank()) {
            throw new IllegalArgumentException("AssetAttention categoryName must not be blank");
        }
        if (lifecycle == null) {
            throw new IllegalArgumentException("AssetAttention lifecycle must not be null");
        }
        if (openEventCount < 0) {
            throw new IllegalArgumentException("AssetAttention openEventCount must not be negative");
        }
        if (batteryPercent != null && (batteryPercent < 0 || batteryPercent > 100)) {
            throw new IllegalArgumentException("AssetAttention batteryPercent must be in [0,100]");
        }
        if (telemetryAgeMs != null && telemetryAgeMs < 0) {
            throw new IllegalArgumentException("AssetAttention telemetryAgeMs must not be negative");
        }
    }
}
