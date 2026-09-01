package com.drones.vision.api.dto;

import java.util.List;

/**
 * Response body for {@code GET /api/discovery/inbox} (docs/plans/active/ASSET-FLOWS-PLAN.md
 * &sect;2, A3) — the found-devices list plus, alongside it, every discovery mechanism's own
 * reachability, so a client can render "mediamtx unreachable" instead of an ambiguous empty list.
 *
 * <p>Wire contract frozen by ASSET-FLOWS-PLAN.md &sect;2: this endpoint used to answer a bare
 * {@code DiscoveryCandidateResponse[]}; it now answers this envelope, {@code candidates} carrying
 * exactly that same array under its own key.
 *
 * @param candidates every candidate currently in the inbox, exactly {@code
 *                   DiscoveryCandidateResponse::from} mapped, same order as before this wave
 * @param sources    one entry per registered discovery mechanism, {@code id}/{@code status}
 */
public record DiscoveryInboxResponse(List<DiscoveryCandidateResponse> candidates,
                                      List<DiscoverySourceResponse> sources) {
}
