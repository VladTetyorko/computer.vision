package com.drones.vision.api.dto;

import com.drones.vision.api.support.EnumParsing;
import com.drones.vision.domain.model.Verification.VerificationState;

/**
 * Body of {@code POST /api/map/marks/{id}/verify} (docs/MAP-REWORK-PLAN.md §4.1) — a manager's
 * review decision.
 *
 * <p>{@code "UNVERIFIED"} parses here but is rejected downstream by {@code MarkService#verify} as a
 * {@code 400}: verification is a decision, and "no decision" is not one. The parse is deliberately
 * left permissive so the error message names the real rule rather than "unknown value".
 *
 * @param decision {@code "CONFIRMED"} or {@code "REJECTED"}
 */
public record VerifyMarkRequest(String decision) {

    /**
     * @return the decision as a domain {@link VerificationState}
     * @throws IllegalArgumentException if {@code decision} is missing or unrecognized (→ 400)
     */
    public VerificationState toDecision() {
        return EnumParsing.require(VerificationState.class, "decision", decision);
    }
}
