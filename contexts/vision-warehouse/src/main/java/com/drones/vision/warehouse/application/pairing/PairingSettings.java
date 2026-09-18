package com.drones.vision.warehouse.application.pairing;

/**
 * Tunable pairing policy: the sysid range {@link PairingService#pair} ever assigns to a newly
 * paired vehicle (docs/plans/active/LINK-PAIRING-PLAN.md §7 ruling 2, config key {@code
 * vision.pairing.sysid-range}). One settings record, not a growing constructor — the convention
 * {@code UsagePhaseSettings}/{@code StreamPipelineSettings} already established.
 *
 * <p>Sysid 1 is ArduPilot's (and the rover firmware's) factory default: an assigned sysid must
 * never equal it, or an unpaired newcomer collides with a paired vehicle on the shared lobby.
 * 251-255 are reserved for ground control stations by MAVLink convention (this station is
 * 255/190). {@link #defaults()} therefore starts the assignable range at 10, leaving 2-9 free for
 * callers who want extra headroom below it, and stops at 250. A heard sysid that a device already
 * announces itself with is kept as-is by {@link PairingService#pair} regardless of this range, as
 * long as it is not already claimed by another pairing (§7 ruling 3) — the range only bounds
 * freshly *assigned* sysids, never ones simply observed on the wire.
 *
 * @param sysidRangeMin lowest sysid ever assigned to a newly paired vehicle
 * @param sysidRangeMax highest sysid ever assigned
 */
public record PairingSettings(int sysidRangeMin, int sysidRangeMax) {

    public PairingSettings {
        if (sysidRangeMin < 1 || sysidRangeMax > 254) {
            throw new IllegalArgumentException(
                    "PairingSettings range must fall within 1-254: " + sysidRangeMin + "-" + sysidRangeMax);
        }
        if (sysidRangeMin > sysidRangeMax) {
            throw new IllegalArgumentException(
                    "PairingSettings sysidRangeMin must not exceed sysidRangeMax: "
                            + sysidRangeMin + "-" + sysidRangeMax);
        }
    }

    /**
     * The documented default range, 10-250.
     *
     * @return default settings
     */
    public static PairingSettings defaults() {
        return new PairingSettings(10, 250);
    }
}
