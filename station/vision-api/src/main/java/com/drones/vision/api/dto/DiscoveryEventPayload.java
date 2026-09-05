package com.drones.vision.api.dto;

/**
 * The payload of one envelope on the {@code discovery} SSE topic (docs/plans/active/
 * SOURCE-ONBOARDING-2-PLAN.md &sect;3.2 C4) — an operator's discovery-inbox list changing.
 *
 * <p>{@link #candidate()} is today's {@link DiscoveryCandidateResponse} thirteen-field shape,
 * verbatim — the same DTO {@code GET /api/discovery/inbox} already returns per element, so a
 * client that already renders that list can apply this delta with no new mapping code.
 *
 * @param action    {@code "REPORTED"} (a sweep found something new or changed), {@code
 *                  "REGISTERED"} (an operator's {@code register}/{@code attach}), {@code
 *                  "DISMISSED"}, or {@code "RESTORED"}
 * @param candidate the candidate as it now stands
 */
public record DiscoveryEventPayload(String action, DiscoveryCandidateResponse candidate) {
}
