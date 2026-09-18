package com.drones.vision.app.config.properties;

import com.drones.vision.warehouse.application.pairing.PairingSettings;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The MAVLink sysid range {@code PairingService#pair} ever assigns to a newly paired vehicle
 * ({@code vision.pairing.sysid-range}, docs/plans/active/LINK-PAIRING-PLAN.md §7 ruling 2).
 *
 * <p>A single {@code "min-max"} string, not two properties, matching the plan's own frozen
 * spelling ({@code vision.pairing.sysid-range=10-250}) — one config key for one policy, not two
 * that could drift out of sync. Sysid 1 is ArduPilot's (and the rover firmware's) factory default
 * and must never be assigned to a paired vehicle — an unpaired newcomer would then collide with it
 * on the shared lobby; 251-255 are reserved for ground control stations by MAVLink convention (this
 * station is 255/190). The default, {@value #DEFAULT_RANGE}, leaves both exclusions with headroom
 * either side.
 *
 * @param sysidRange {@code "min-max"}, e.g. {@code "10-250"}; default {@value #DEFAULT_RANGE}
 */
@ConfigurationProperties(prefix = "vision.pairing")
public record VisionPairingProperties(@DefaultValue(VisionPairingProperties.DEFAULT_RANGE) String sysidRange) {

    static final String DEFAULT_RANGE = "10-250";

    public VisionPairingProperties {
        if (sysidRange == null || !sysidRange.matches("\\d{1,3}-\\d{1,3}")) {
            throw new IllegalArgumentException(
                    "vision.pairing.sysid-range must be \"min-max\" (e.g. \"10-250\"): " + sysidRange);
        }
    }

    /**
     * Parses {@link #sysidRange} into the settings record {@code PairingService} actually uses.
     *
     * @return the parsed settings
     */
    public PairingSettings toSettings() {
        String[] parts = sysidRange.split("-", 2);
        return new PairingSettings(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }
}
