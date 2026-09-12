package com.drones.vision.perception.domain.model;

import java.util.Map;

/**
 * One claim <strong>one</strong> contributor made about <strong>one</strong> object, kept
 * attributed rather than merged into the object's own state (docs/plans/active/
 * CV-ORCHESTRATION-PLAN.md §4.4) — "who said this" is a question a merged map cannot answer.
 * Algorithm-shaped facts (predicted box terms, assoc cost weights, memory distance) belong here,
 * in the ledger, never on {@link ObjectState} — the mirror describes the object, the ledger
 * describes the algorithm.
 *
 * @param contributorId who made the claim; must not be blank
 * @param claim         the claim itself, free-form; defensively copied to an immutable map,
 *                      never {@code null}
 */
public record ObjectEvidence(String contributorId, Map<String, String> claim) {

    public ObjectEvidence {
        if (contributorId == null || contributorId.isBlank()) {
            throw new IllegalArgumentException("ObjectEvidence contributorId must not be blank");
        }
        if (claim == null) {
            throw new IllegalArgumentException("ObjectEvidence claim must not be null");
        }
        claim = Map.copyOf(claim);
    }
}
