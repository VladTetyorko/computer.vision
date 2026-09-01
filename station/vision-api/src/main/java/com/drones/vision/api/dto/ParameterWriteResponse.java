package com.drones.vision.api.dto;

import com.drones.vision.flight.domain.model.ParameterWriteOutcome;

/**
 * Response body for {@code POST /api/assets/{id}/parameters} (docs/plans/active/FLEET-RADIO-PLAN.md
 * R5) — mirrors domain {@link ParameterWriteOutcome} field-for-field.
 *
 * <p>No {@code @JsonInclude(NON_NULL)} — same convention {@code VehicleProfileResponse}/{@code
 * RemediationResultResponse} already use for this domain: {@code previousValue}/{@code newValue}/
 * {@code detail} being absent-vs-{@code null} is itself meaningful (C7), so every field is always
 * present, literal {@code null} where nothing was read.
 *
 * @param parameterName the parameter written — {@link ParameterWriteOutcome#parameterName()}, which
 *                      is the spelling actually sent to the vehicle, not necessarily the one the
 *                      request named (see {@code AssetParameterController})
 * @param outcome       {@code "ACCEPTED"|"DENIED"|"NO_ACK"|"UNSUPPORTED"} — {@link
 *                      com.drones.vision.flight.domain.model.RemediationResultCode#name()} verbatim
 * @param previousValue the value read back before the write; {@code null} if never read
 * @param newValue      the value read back after the write; {@code null} unless the write was
 *                      accepted and read back
 * @param detail        an honest sentence explaining {@code outcome}; nullable
 */
public record ParameterWriteResponse(String parameterName, String outcome, Double previousValue, Double newValue,
                                      String detail) {

    public static ParameterWriteResponse from(ParameterWriteOutcome outcome) {
        return new ParameterWriteResponse(outcome.parameterName(), outcome.outcome().name(), outcome.previousValue(),
                outcome.newValue(), outcome.detail());
    }
}
