package com.drones.vision.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code POST /api/assets/{assetId}/remediate} (docs/plans/active/
 * DRONE-ONBOARDING-PLAN.md §8.1, frozen). Assembled by {@code RemediationOrchestrator}, one entry
 * per requested (feature, applicable remedy) pair — not a straight mapping off a single domain
 * type, since no application service in {@code vision-flight} composes "attempt every requested
 * feature's remedy, then re-probe" end to end (see this wave's report).
 *
 * <p>No {@code @JsonInclude(NON_NULL)}: §8.1's own example shows {@code "previousValue": null,
 * "newValue": null, "detail": null} as literal, always-present fields.
 *
 * @param requestedAt when this remediation attempt started
 * @param verifiedAt  when the post-remediation re-probe completed, or {@code null} if no action was
 *                    actually dispatched to the vehicle (nothing to verify)
 * @param actions     one outcome per (feature, applicable remedy) pair considered
 * @param reprobe     the re-evaluated readiness after remediation, or {@code null} alongside {@code
 *                    verifiedAt == null}
 */
public record RemediationResultResponse(Instant requestedAt, Instant verifiedAt,
                                         List<RemediationActionResponse> actions,
                                         ReadinessReportResponse reprobe) {

    /**
     * One remediation action's outcome.
     *
     * @param action        the remedy kind attempted (e.g. {@code "MESSAGE_INTERVAL"}), or {@code
     *                      null} when no remedy applies to the feature at all (mirrors {@link
     *                      com.drones.vision.flight.domain.model.FeatureReadiness#remedy()}'s own
     *                      nullability)
     * @param messageId     the MAVLink message id involved, when {@code action ==
     *                      "MESSAGE_INTERVAL"} and known; {@code null} otherwise
     * @param intervalMicros the requested interval in microseconds, when {@code action ==
     *                      "MESSAGE_INTERVAL"} and known; {@code null} otherwise
     * @param outcome       {@code "ACCEPTED"|"DENIED"|"NO_ACK"|"UNSUPPORTED"}
     * @param previousValue the parameter's pre-write value, for a {@code PARAM_WRITE} outcome;
     *                      {@code null} otherwise (this wave never dispatches {@code PARAM_WRITE} —
     *                      see the orchestrator's javadoc)
     * @param newValue      the parameter's post-write value; {@code null} otherwise
     * @param detail        an honest sentence explaining {@code outcome} (C7)
     */
    public record RemediationActionResponse(String action, Integer messageId, Long intervalMicros, String outcome,
                                             Double previousValue, Double newValue, String detail) {
    }
}
