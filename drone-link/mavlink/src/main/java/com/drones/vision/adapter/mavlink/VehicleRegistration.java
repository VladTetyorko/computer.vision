package com.drones.vision.adapter.mavlink;

import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.Telemetry;

import java.util.concurrent.SubmissionPublisher;

/**
 * One device's interest in a {@link MavlinkGateway}'s socket: a possible sysid pin, and the
 * resolved claim/decoder state (docs/plans/active/DRONE-INFRA-PLAN.md I-a, re-plumbed onto
 * {@code mavlink-core} by docs/plans/active/MAVLINK-CORE-PLAN.md W4). A top-level, package-private
 * mutable struct shared by {@link MavlinkGateway} (socket/session lifecycle, dispatch) and
 * {@link VehicleClaimPolicy} (claim bookkeeping) so neither type nests inside the other.
 *
 * <p>Deliberately a plain mutable struct, not a record — every field past the three constructor
 * arguments changes after construction as messages arrive. Fields are package-private with no
 * accessors: this type has exactly two collaborators ({@link MavlinkGateway}, {@link
 * VehicleClaimPolicy}), both in this package, and both already document their own locking
 * discipline around it — getters/setters here would add ceremony without adding safety.
 *
 * <h2>W4 simplification</h2>
 * Pre-W4, this struct also carried {@code lastHeardMillis}/{@code firmware}/{@code mavType}/
 * {@code lastSourceAddress}, refreshed on every message under {@link VehicleClaimPolicy}'s own
 * lock. Those are all now protocol facts {@code mavlink-core}'s own {@code PeerDirectory} already
 * tracks per peer (see plan §3.4's split) — {@link VehicleClaimPolicy} reads them from there
 * instead of duplicating the bookkeeping here, so this struct shrinks to just the claim itself
 * (which sysid, which decoder).
 *
 * <h2>Threading</h2>
 * The three constructor fields are immutable. {@code claimedSysid}/{@code decoder} are mutated
 * only under {@link VehicleClaimPolicy}'s own lock, on whichever thread calls {@link
 * VehicleClaimPolicy#resolve} (the gateway's one dispatcher-subscription callback) — read from a
 * caller thread only under that same lock (e.g. {@link VehicleClaimPolicy#claimedVehicles()},
 * {@link VehicleClaimPolicy#remove}).
 */
final class VehicleRegistration {
    final DeviceId deviceId;
    final Integer pinnedSysid;
    final SubmissionPublisher<Telemetry> publisher;

    Integer claimedSysid;
    MavlinkTelemetryDecoder decoder;

    VehicleRegistration(DeviceId deviceId, Integer pinnedSysid, SubmissionPublisher<Telemetry> publisher) {
        this.deviceId = deviceId;
        this.pinnedSysid = pinnedSysid;
        this.publisher = publisher;
    }
}
