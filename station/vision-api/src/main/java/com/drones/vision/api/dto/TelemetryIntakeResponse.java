package com.drones.vision.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * {@code telemetryIntake} on {@code GET /api/discovery/status} (docs/plans/active/
 * SOURCE-ONBOARDING-2-PLAN.md &sect;3.2 C2) — field-for-field the wire form of {@code
 * com.drones.vision.adapter.mavlink.MavlinkIntakeStatus}, whose own javadoc is this shape's
 * authoritative source; see that record for what each field means and why {@code
 * datagramsReceived}/{@code framesDecoded} together (not either alone) are the actual diagnostic.
 *
 * @param bound             whether a gateway is currently bound to the standing lobby address
 * @param bindAddress       {@code "host:port"} this status describes
 * @param lobbyHeld         whether the standing MAVLink lobby currently holds this address open
 * @param datagramsReceived total UDP datagrams received, pre-parse, monotonic
 * @param bytesReceived     total payload bytes received, pre-parse, monotonic
 * @param lastDatagramAt    when the most recent datagram arrived; absent if none ever has
 * @param framesDecoded     total MAVLink frames successfully decoded and dispatched, claimed or not
 * @param unclaimedSysids   system ids currently heard but claimed by no registered device
 * @param claimedSysids     system ids currently claimed by a registered device
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TelemetryIntakeResponse(boolean bound, String bindAddress, boolean lobbyHeld, long datagramsReceived,
                                       long bytesReceived, Instant lastDatagramAt, long framesDecoded,
                                       List<Integer> unclaimedSysids, List<Integer> claimedSysids) {
}
