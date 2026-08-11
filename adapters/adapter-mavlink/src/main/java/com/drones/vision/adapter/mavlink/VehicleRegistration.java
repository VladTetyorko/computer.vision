package com.drones.vision.adapter.mavlink;

import com.drones.vision.kernel.DeviceId;
import com.drones.vision.flight.domain.model.Telemetry;

import java.net.InetSocketAddress;
import java.util.concurrent.SubmissionPublisher;

/**
 * One device's interest in a {@link MavlinkSocketHub}'s socket: a possible sysid pin, and the
 * resolved claim/decoder state (docs/plans/active/DRONE-INFRA-PLAN.md I-a). Promoted to a top-level type
 * (docs/plans/active/LAYERING-REFACTOR-PLAN.md E2) so both {@link MavlinkSocketHub} (socket/thread lifecycle,
 * dispatch) and {@link VehicleClaimRegistry} (claim bookkeeping) can share it without one nesting
 * inside the other.
 *
 * <p>Deliberately a plain mutable struct, not a record — every field past the three constructor
 * arguments changes after construction as messages arrive. Fields are package-private with no
 * accessors: this type has exactly two collaborators ({@link MavlinkSocketHub}, {@link
 * VehicleClaimRegistry}), both in this package, and both already document their own locking
 * discipline around it — getters/setters here would add ceremony without adding safety.
 *
 * <h2>Threading</h2>
 * The three constructor fields are immutable. Every other field is mutated only on {@link
 * MavlinkSocketHub}'s own read thread, always under {@link VehicleClaimRegistry}'s lock; read from
 * a caller thread only under that same lock (e.g. {@link VehicleClaimRegistry#claimedVehicles()},
 * {@link VehicleClaimRegistry#remove}) — see {@link MavlinkSocketHub}'s own "Threading" javadoc
 * section for why plain fields (no {@code volatile}) are sufficient here.
 */
final class VehicleRegistration {
    final DeviceId deviceId;
    final Integer pinnedSysid;
    final SubmissionPublisher<Telemetry> publisher;

    Integer claimedSysid;
    long lastHeardMillis;
    MavlinkTelemetryDecoder decoder;
    String firmware; // docs/plans/active/DRONE-INFRA-PLAN.md I-b -- from the most recent HEARTBEAT, null until one arrives
    Integer mavType; // ditto
    InetSocketAddress lastSourceAddress; // docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1 -- null until this claim has actually been heard from

    VehicleRegistration(DeviceId deviceId, Integer pinnedSysid, SubmissionPublisher<Telemetry> publisher) {
        this.deviceId = deviceId;
        this.pinnedSysid = pinnedSysid;
        this.publisher = publisher;
    }
}
