package com.drones.vision.api.dto;

import java.math.BigInteger;

/**
 * Request body for {@code POST /api/devices/{id}/pairing} (docs/plans/active/
 * LINK-PAIRING-PLAN.md §3.3) — the explicit pairing endpoint for a manually registered device that
 * never went through discovery's "adopt is one motion" (§7 ruling 3).
 *
 * @param heardSysid  the sysid the vehicle is currently announcing itself with, or {@code null} if
 *                    unknown — {@code null} is treated as "no sysid heard" and always results in a
 *                    freshly assigned one ({@code sysidPushRequired = true} in the response), the
 *                    same outcome a heard value outside 1-250 gets from {@code PairingService#pair}
 * @param hardwareUid the flight-controller hardware uid, as a decimal string, or {@code null} if
 *                    not yet known
 */
public record PairDeviceRequest(Integer heardSysid, String hardwareUid) {

    /**
     * The heard sysid to pass to {@link com.drones.vision.warehouse.application.pairing.PairingService#pair},
     * defaulting an absent value to {@code 0} (never a valid vehicle sysid, so it always falls
     * through to a fresh assignment).
     *
     * @return the effective heard sysid
     */
    public int effectiveHeardSysid() {
        return heardSysid == null ? 0 : heardSysid;
    }

    /**
     * Parses {@link #hardwareUid}.
     *
     * @return the parsed hardware uid, or {@code null} if absent
     * @throws IllegalArgumentException if {@link #hardwareUid} is present but not a valid decimal
     *                                   integer
     */
    public BigInteger parsedHardwareUid() {
        if (hardwareUid == null || hardwareUid.isBlank()) {
            return null;
        }
        try {
            return new BigInteger(hardwareUid.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("hardwareUid must be a decimal integer: " + hardwareUid, e);
        }
    }
}
