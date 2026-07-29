package com.drones.vision.api.dto;

import com.drones.vision.application.AssetAttention;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One row of {@code GET /api/fleet/summary}'s {@code assets} array (docs/MVP3-PLAN.md C-a) — one
 * asset's attention-relevant facts, everything the manager dashboard's attention queue needs
 * without a second poll per asset.
 *
 * <p>{@code streamId}, {@code batteryPercent}, and {@code telemetryAgeMs} are omitted from the JSON
 * entirely (rather than serialized as {@code null}) when unavailable — see {@link
 * AssetAttention}'s own javadoc for exactly when that is. There is deliberately no {@code
 * sourceState} field — see {@link AssetAttention}'s javadoc for why it was left out rather than
 * faked.
 *
 * @param assetId          asset identity, as a canonical UUID string
 * @param displayName      human-readable name
 * @param categoryId       the asset's category slug
 * @param categoryName     human-readable category name
 * @param lifecycle        {@code ACTIVE}, {@code DEACTIVATED}, or {@code DELETED}
 * @param streaming        whether any of the asset's devices currently has an active stream
 * @param streamId         the streaming device's stream id, as a canonical UUID string, or absent
 *                         when {@code streaming} is {@code false}
 * @param batteryPercent   percent, or absent if the asset has never reported telemetry
 * @param telemetryAgeMs   milliseconds since the freshest telemetry sample, or absent under the
 *                         same condition as {@code batteryPercent}
 * @param openEventCount   how many {@code OPEN} detection events currently name this asset
 * @param flightMode       the freshest telemetry sample's flight-controller mode name (e.g.
 *                         {@code "RTL"}), absent under the same condition as {@code batteryPercent}
 *                         (docs/FC-INTEGRATIONS-PLAN.md F-b)
 * @param armed            the freshest telemetry sample's armed flag, absent under the same
 *                         condition as {@code flightMode}
 * @param failsafe         the freshest telemetry sample's failsafe flag, absent under the same
 *                         condition as {@code flightMode}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssetAttentionResponse(String assetId, String displayName, String categoryId, String categoryName,
                                      String lifecycle, boolean streaming, String streamId, Double batteryPercent,
                                      Long telemetryAgeMs, int openEventCount, String flightMode, Boolean armed,
                                      Boolean failsafe) {

    /**
     * Maps an {@link AssetAttention} read model to its wire representation.
     *
     * @param attention the row to map
     * @return the response body element for {@code attention}
     */
    public static AssetAttentionResponse from(AssetAttention attention) {
        return new AssetAttentionResponse(
                attention.assetId().value().toString(),
                attention.displayName(),
                attention.categoryId().slug(),
                attention.categoryName(),
                attention.lifecycle().name(),
                attention.streaming(),
                attention.streamId() == null ? null : attention.streamId().value().toString(),
                attention.batteryPercent(),
                attention.telemetryAgeMs(),
                attention.openEventCount(),
                attention.flightMode(),
                attention.armed(),
                attention.failsafe());
    }
}
