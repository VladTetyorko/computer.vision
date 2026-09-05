package com.drones.vision.api.dto;

/**
 * Request body for {@code POST /api/discovery/inbox/{id}/attach} (docs/plans/active/
 * SOURCE-ONBOARDING-2-PLAN.md &sect;3.2 C1) — the atomic server-side twin of {@code
 * RegisterDiscoveryCandidateRequest}, for the case an operator already has an existing asset in
 * mind rather than wanting a new one.
 *
 * @param assetId the existing asset to attach the candidate's device to, as a canonical UUID string
 */
public record AttachDiscoveryCandidateRequest(String assetId) {
}
