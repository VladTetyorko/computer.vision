package com.drones.vision.flight.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * An observed snapshot of one aircraft's capability, exactly what the PROBE stage
 * (docs/plans/active/DRONE-ONBOARDING-PLAN.md §3.1) actually learned — never a firmware {@code
 * switch}, per §5.1's doctrine borrowed from {@code DeviceCategory}. {@code linkKey} is the identity
 * used before a {@code DeviceId} exists ({@code "udp://0.0.0.0:14550#7"}, §3.1's candidate key);
 * once attached to a registered device the profile is persisted keyed by that device's id via {@link
 * com.drones.vision.flight.domain.port.VehicleProfileRepositoryPort}, mirroring how {@code
 * TelemetryRepositoryPort} keys a sample by {@code UsageId} rather than carrying it as a field.
 *
 * <p><b>C7, structurally</b>: {@link #complete()} is {@code false} whenever any half of the probe
 * went unanswered, and {@link #incompleteReason()} must then say which — this type cannot represent
 * "probably fine, didn't check" the way a fabricated default would. {@link #firmware()}/{@link
 * #firmwareVersion()}/{@link #vehicleKind()}/{@link #capabilityBitmask()} are each independently
 * nullable for the same reason: an unanswered {@code AUTOPILOT_VERSION} request means exactly those
 * fields are {@code null}, not a guess.
 *
 * @param linkKey            candidate identity before/independent of a {@code DeviceId}
 * @param observedAt         when this snapshot was taken
 * @param sysid              the MAVLink system id, a one-byte field (0-255), or {@code null} if the
 *                           link key carries no sysid
 * @param firmware           {@code "ardupilot"} | {@code "generic"} | {@code "px4"} | {@code null}
 *                           (never heard)
 * @param firmwareVersion    from {@code AUTOPILOT_VERSION}; {@code null} when unanswered
 * @param vehicleKind        e.g. {@code "quadcopter"}; {@code null} when unknown
 * @param capabilityBitmask  the autopilot's own MAVLink capability bitmask; {@code null} when
 *                           unanswered — deliberately not validated as non-negative, since a
 *                           {@code uint64} bitmask with its high bit set is a legitimate value that
 *                           reads as a negative {@code long}
 * @param capabilityFlags    the bitmask decoded into named flags (e.g. {@code "MAVLINK2"}); empty,
 *                           never {@code null}, when {@code capabilityBitmask} is {@code null}
 * @param messages           the passive message inventory; defensively copied
 * @param parameters         only the parameters actually read; defensively copied
 * @param linkBytesPerSecond measured link throughput; {@code null} when not measured
 * @param complete           whether every half of the probe answered
 * @param incompleteReason   which half did not, and why; {@code null} iff {@code complete}, non-blank
 *                           otherwise
 */
public record VehicleProfile(
        String linkKey,
        Instant observedAt,
        Integer sysid,
        String firmware,
        String firmwareVersion,
        String vehicleKind,
        Long capabilityBitmask,
        List<String> capabilityFlags,
        List<MessageObservation> messages,
        List<ParameterReading> parameters,
        Long linkBytesPerSecond,
        boolean complete,
        String incompleteReason) {

    /** MAVLink {@code sysid} is a one-byte field. */
    private static final int MIN_SYSID = 0;
    private static final int MAX_SYSID = 255;

    public VehicleProfile {
        if (linkKey == null || linkKey.isBlank()) {
            throw new IllegalArgumentException("VehicleProfile linkKey must not be blank");
        }
        if (observedAt == null) {
            throw new IllegalArgumentException("VehicleProfile observedAt must not be null");
        }
        if (sysid != null && (sysid < MIN_SYSID || sysid > MAX_SYSID)) {
            throw new IllegalArgumentException(
                    "VehicleProfile sysid must be in [" + MIN_SYSID + "," + MAX_SYSID + "]: " + sysid);
        }
        if (linkBytesPerSecond != null && linkBytesPerSecond < 0) {
            throw new IllegalArgumentException(
                    "VehicleProfile linkBytesPerSecond must not be negative: " + linkBytesPerSecond);
        }
        if (complete && incompleteReason != null) {
            throw new IllegalArgumentException("VehicleProfile incompleteReason must be null when complete");
        }
        if (!complete && (incompleteReason == null || incompleteReason.isBlank())) {
            throw new IllegalArgumentException(
                    "VehicleProfile incompleteReason must say why when the probe is incomplete (C7)");
        }
        capabilityFlags = List.copyOf(capabilityFlags == null ? List.of() : capabilityFlags);
        messages = List.copyOf(messages == null ? List.of() : messages);
        parameters = List.copyOf(parameters == null ? List.of() : parameters);
    }
}
