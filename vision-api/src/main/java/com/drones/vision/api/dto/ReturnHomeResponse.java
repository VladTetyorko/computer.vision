package com.drones.vision.api.dto;

import com.drones.vision.domain.model.CommandResult;

/**
 * Response body for {@code POST /api/assets/{assetId}/return-home} (docs/plans/active/DRONE-INFRA-PLAN.md I-e,
 * Stage 1's frozen wire contract).
 *
 * @param result {@code "ACCEPTED"} or {@code "NO_ACK"} — {@link CommandResult#name()} verbatim,
 *               the same enum-name-as-wire-value idiom {@code DetectionEventResponse#state} already
 *               uses
 */
public record ReturnHomeResponse(String result) {

    public static ReturnHomeResponse from(CommandResult result) {
        return new ReturnHomeResponse(result.name());
    }
}
