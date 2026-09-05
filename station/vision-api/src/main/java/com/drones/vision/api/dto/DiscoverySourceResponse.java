package com.drones.vision.api.dto;

import com.drones.vision.warehouse.application.discovery.SourceHealth;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * One entry of {@link DiscoveryInboxResponse#sources()} (docs/plans/active/ASSET-FLOWS-PLAN.md
 * &sect;2, A3) — a discovery mechanism's own reachability, reported alongside the candidate list so
 * a client can tell "this source is unreachable" apart from "reachable, nothing found" instead of
 * both collapsing into an empty candidate list.
 *
 * @param id         the discovery mechanism's key (e.g. {@code "mediamtx"}, {@code "mdns"}, {@code
 *                   "onvif"}, {@code "v4l2"}, {@code "mavlink"})
 * @param status     {@code "OK"}, {@code "UNREACHABLE"}, or {@code "NEVER_SCANNED"}
 * @param lastScanAt when this mechanism was last scanned; absent when {@code status} is {@code
 *                   "NEVER_SCANNED"} (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md &sect;3.2 C2)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiscoverySourceResponse(String id, String status, Instant lastScanAt) {

    /**
     * Maps a domain {@link SourceHealth} to its wire representation.
     *
     * @param health the source health to map
     * @return the response body element for {@code health}
     */
    public static DiscoverySourceResponse from(SourceHealth health) {
        return new DiscoverySourceResponse(health.id(), health.status().name(), health.lastScanAt());
    }
}
