package com.drones.vision.api.dto;

import com.drones.vision.flight.domain.model.ParameterDrift;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code GET /api/assets/{assetId}/usages/{usageId}/drift}
 * (docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1, frozen wire contract, O13): {@code { "drift":
 * [ParameterDriftRow] } }.
 *
 * <p>An empty {@code drift} list is a correct, non-error {@code 200} — "nothing to compare" (no
 * previous flight, or either endpoint snapshot missing), never a {@code 404}; see {@link
 * com.drones.vision.flight.application.VehicleProfileService#driftFromPreviousFlight} for the
 * comparison itself (the previous flight's {@code POSTFLIGHT} snapshot against this flight's
 * {@code PREFLIGHT} one).
 *
 * @param drift every parameter that changed, sorted by parameter name, or empty when there is
 *              nothing to compare
 */
public record ParameterDriftResponse(List<ParameterDriftRowResponse> drift) {

    /** One {@link ParameterDrift}, verbatim. */
    public record ParameterDriftRowResponse(String parameterName, double previousValue, double currentValue,
                                             Instant previousObservedAt, Instant currentObservedAt) {

        public static ParameterDriftRowResponse from(ParameterDrift drift) {
            return new ParameterDriftRowResponse(drift.parameterName(), drift.previousValue(), drift.currentValue(),
                    drift.previousObservedAt(), drift.currentObservedAt());
        }
    }

    /**
     * Maps a domain drift list to its wire representation.
     *
     * @param drift the drift rows to map, in the order to serve them
     * @return the response body wrapping {@code drift}
     */
    public static ParameterDriftResponse from(List<ParameterDrift> drift) {
        return new ParameterDriftResponse(drift.stream().map(ParameterDriftRowResponse::from).toList());
    }
}
