package com.drones.mavlink.service;

import io.dronefleet.mavlink.common.AutopilotVersion;
import io.dronefleet.mavlink.common.MavProtocolCapability;

import java.util.Objects;
import java.util.Set;

/**
 * What a vehicle answered to {@code MAV_CMD_REQUEST_MESSAGE(AUTOPILOT_VERSION)} — decoded far
 * enough to be useful, and no further.
 *
 * <p>The two decodings live here rather than in every caller because both are easy to get wrong:
 * {@code flight_sw_version} is a packed {@code uint32}, not a number anyone should print raw, and
 * {@code capabilities} is a bitmask whose set bits are the aircraft's own claim about which
 * protocols it speaks. Turning those into a version string and a flag set is protocol knowledge and
 * belongs at this layer; deciding what they <i>mean</i> for a feature ("can this aircraft fly a
 * mission?") is domain policy and deliberately does not.
 *
 * @param firmwareVersion {@code "major.minor.patch"}, or {@code null} for {@link Status#NO_REPLY}
 * @param maturity        the low byte of {@code flight_sw_version} — DEV/ALPHA/BETA/RC/OFFICIAL
 * @param raw             the whole message, for a caller that needs a field this record omits;
 *                        {@code null} for {@link Status#NO_REPLY}
 */
public record CapabilityReport(Status status, String firmwareVersion, Maturity maturity,
                               Set<MavProtocolCapability> capabilities, long boardVersion,
                               int vendorId, int productId, AutopilotVersion raw) {

    public CapabilityReport {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(capabilities, "capabilities");
        capabilities = Set.copyOf(capabilities);
        if (status == Status.OK && raw == null) {
            throw new IllegalArgumentException("OK must carry the AUTOPILOT_VERSION it was decoded from");
        }
    }

    public enum Status {
        /** The vehicle answered with an {@code AUTOPILOT_VERSION}. */
        OK,
        /**
         * Nothing arrived within the timeout, across every retry. Expected, not exceptional: plenty
         * of firmware (Betaflight, older ArduPilot builds) never implements this message, and the
         * platform's answer is an incomplete profile rather than a fabricated one.
         */
        NO_REPLY
    }

    /** The {@code FIRMWARE_VERSION_TYPE} enum, as the low byte of {@code flight_sw_version}. */
    public enum Maturity {
        DEV, ALPHA, BETA, RC, OFFICIAL, UNKNOWN
    }

    public boolean ok() {
        return status == Status.OK;
    }

    /** {@code true} if the aircraft claims to speak {@code capability}. */
    public boolean supports(MavProtocolCapability capability) {
        return capabilities.contains(Objects.requireNonNull(capability, "capability"));
    }
}
