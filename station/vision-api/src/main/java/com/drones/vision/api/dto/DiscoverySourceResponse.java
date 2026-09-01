package com.drones.vision.api.dto;

import com.drones.vision.warehouse.application.discovery.SourceHealth;

/**
 * One entry of {@link DiscoveryInboxResponse#sources()} (docs/plans/active/ASSET-FLOWS-PLAN.md
 * &sect;2, A3) — a discovery mechanism's own reachability, reported alongside the candidate list so
 * a client can tell "this source is unreachable" apart from "reachable, nothing found" instead of
 * both collapsing into an empty candidate list.
 *
 * @param id     the discovery mechanism's key (e.g. {@code "mediamtx"}, {@code "mdns"}, {@code
 *               "onvif"}, {@code "v4l2"}, {@code "mavlink"})
 * @param status {@code "OK"} or {@code "UNREACHABLE"}
 */
public record DiscoverySourceResponse(String id, String status) {

    /**
     * Maps a domain {@link SourceHealth} to its wire representation.
     *
     * @param health the source health to map
     * @return the response body element for {@code health}
     */
    public static DiscoverySourceResponse from(SourceHealth health) {
        return new DiscoverySourceResponse(health.id(), health.status().name());
    }
}
