package com.drones.vision.warehouse.domain.model;

/**
 * Whether a discovery mechanism's remote/external dependency answered its most recent scan
 * (docs/plans/active/ASSET-FLOWS-PLAN.md &sect;2, A3).
 *
 * <p>Exists to close a specific honesty gap: {@link
 * com.drones.vision.warehouse.domain.port.DeviceDiscoveryPort#scan} answers an empty list both when
 * nothing is out there <em>and</em> when the mechanism could not even ask (e.g. the mediamtx
 * push-registry scanner's Control API being down) — a pilot reading the found-devices inbox cannot
 * tell those apart from the candidate list alone. A source's status is reported alongside its
 * candidates, not folded into them.
 */
public enum SourceStatus {

    /** The mechanism completed its most recent scan attempt normally, whether or not it found anything. */
    OK,

    /**
     * The mechanism's remote/external dependency could not be reached, or answered with something
     * this station cannot trust, on its most recent scan attempt — distinct from "reached fine,
     * nothing found".
     */
    UNREACHABLE
}
