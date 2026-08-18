package com.drones.vision.platform;

/**
 * How a subsystem {@link SubsystemStatusPort} reports on is doing right now.
 *
 * <p>Ordered worst-to-best is deliberately NOT the declaration order — see {@code
 * SystemStatusController} (vision-api) for the severity used to roll many {@link SubsystemStatus}
 * values up into one overall verdict. {@link #DISABLED} is excluded from that rollup entirely: a
 * subsystem an operator has deliberately switched off (docs/plans/active/SYSTEM-STATUS-PLAN.md §7.2's
 * "honest status over optimistic status") must never read as a fault.
 */
public enum Health {

    /** Working normally. */
    OK,

    /** Working, but impaired — e.g. reconnecting, elevated loss, a recoverable outage in progress. */
    DEGRADED,

    /** Not working — a confirmed outage. */
    DOWN,

    /** Deliberately switched off by configuration; not a fault, excluded from the overall rollup. */
    DISABLED,

    /** Could not be determined — e.g. the provider itself threw, or no data exists yet. */
    UNKNOWN
}
