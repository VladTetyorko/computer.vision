package com.drones.mavlink.session;

import com.drones.mavlink.transport.LinkId;

import java.time.Instant;

/**
 * Per-link radio-quality telemetry, keyed by {@link LinkId} rather than {@link
 * com.drones.mavlink.PeerId} — a deliberate, separate port from {@link LinkHealth}
 * (LINK-PAIRING-PLAN.md §3.1 ⚠ objection): {@code LinkHealth.Health} already collapses to
 * "whichever link this peer was most recently heard on," and folding a per-link RSSI field into it
 * would either go stale the moment a peer is heard on a second link, or force {@code
 * MavlinkLinkStatusProvider} to reason about a dimension it doesn't need. This port answers a
 * different question — "how good is this <i>link</i> right now" — that a future election layer
 * (L3) needs and {@code LinkHealth} was never shaped to answer.
 */
public interface LinkQuality {

    /** @return the last known quality for {@code id}, or {@code null} if no RADIO_STATUS has ever been heard on it */
    Quality of(LinkId id);

    /**
     * One RADIO_STATUS (#109) snapshot. Every numeric field is nullable-boxed rather than a
     * primitive default because "never heard" and "heard a genuine zero" must stay distinguishable
     * — a primitive {@code int} default of {@code 0} would silently claim "zero RSSI," a real and
     * very different reading from "no radio has ever reported in."
     *
     * @param fixed a corrected-packet count in the real MAVLink spec (dronefleet's {@code
     *              RadioStatus.fixed()} is an {@code int}, not a boolean) — this port follows the
     *              frozen contract's literal {@code Boolean} shape (true iff nonzero), a deliberate
     *              simplification: L1 only needs "is FEC currently correcting anything," not the count
     */
    record Quality(LinkId linkId, Instant lastRadioStatusAt, Integer rssi, Integer remoteRssi,
                    Integer noise, Integer rxErrors, Boolean fixed) {
    }
}
