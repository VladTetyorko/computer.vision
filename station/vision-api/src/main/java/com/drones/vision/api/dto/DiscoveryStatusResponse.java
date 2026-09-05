package com.drones.vision.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code GET /api/discovery/status} (docs/plans/active/
 * SOURCE-ONBOARDING-2-PLAN.md &sect;3.2 C2) — "the most important endpoint in the plan": one
 * screen answering whether zero-config onboarding is actually working right now, without an
 * operator having to correlate {@code GET /api/discovery/inbox}, {@code GET /api/system/network},
 * and the MAVLink lobby's own logs by hand.
 *
 * @param sweepSeconds    the discovery-inbox sweep's configured interval
 * @param lastSweepAt     when the sweep runner last completed a sweep; absent before the first one
 * @param telemetryIntake the standing MAVLink lobby's own reachability/decode counters
 * @param videoIntake     mediamtx publish reachability; absent when mediamtx publish is unconfigured
 * @param sources         one entry per registered discovery mechanism (mirrors {@code
 *                        DiscoveryInboxResponse#sources()})
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiscoveryStatusResponse(int sweepSeconds, Instant lastSweepAt, TelemetryIntakeResponse telemetryIntake,
                                       VideoIntakeResponse videoIntake, List<DiscoverySourceResponse> sources) {
}
