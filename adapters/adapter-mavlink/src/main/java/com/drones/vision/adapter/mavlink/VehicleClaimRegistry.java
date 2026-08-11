package com.drones.vision.adapter.mavlink;

import com.drones.vision.kernel.DeviceId;

import io.dronefleet.mavlink.MavlinkMessage;
import io.dronefleet.mavlink.minimal.Heartbeat;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One {@link MavlinkSocketHub}'s vehicle-claim bookkeeping (docs/plans/active/LAYERING-REFACTOR-PLAN.md E2 split
 * out of what used to be {@code MavlinkSocketHub} itself): which {@link VehicleRegistration}s exist,
 * which sysid each currently claims, and the bounded registry of sysids nobody claims. See {@link
 * MavlinkSocketHub}'s own javadoc for the full claim/re-election/unclaimed-registry semantics this
 * class implements — this type is the mechanism, that one is still the documented contract.
 *
 * <h2>Threading</h2>
 * Guarded entirely by one private monitor ({@link #lock}), the same shape {@code MavlinkSocketHub}
 * used before this split: {@link #add}/{@link #remove}/{@link #unclaimedVehicles()}/{@link
 * #claimedVehicles()}/{@link #commandTarget} are called from whatever thread calls {@code
 * MavlinkTelemetrySource#open}/{@code close}/{@code commandTarget}; {@link #resolve} runs only on
 * the hub's own read thread. No concurrent collections, no volatile fields — one lock, exactly as
 * before.
 *
 * <p>Package-private, owned entirely by {@link MavlinkSocketHub} — not a domain/port type.
 */
final class VehicleClaimRegistry {

    private final long silenceWindowMillis;
    private final int maxUnclaimedVehicles;

    private final Object lock = new Object();
    private final List<VehicleRegistration> registrations = new ArrayList<>();
    private final Map<Integer, VehicleRegistration> claimsBySysid = new HashMap<>();
    private final LinkedHashMap<Integer, MavlinkSocketHub.UnclaimedVehicle> unclaimed = new LinkedHashMap<>();

    /**
     * @param maxUnclaimedVehicles bound on {@link #unclaimed}'s size (docs/plans/active/LAYERING-REFACTOR-PLAN.md
     *                             §2.2's {@code vision.mavlink.max-unclaimed-vehicles} — was this
     *                             class's own {@code MAX_UNCLAIMED_VEHICLES} constant, now sourced
     *                             from {@link MavlinkSettings#maxUnclaimedVehicles()})
     */
    VehicleClaimRegistry(long silenceWindowMillis, int maxUnclaimedVehicles) {
        this.silenceWindowMillis = silenceWindowMillis;
        this.maxUnclaimedVehicles = maxUnclaimedVehicles;
    }

    /** @return {@code true} if {@code registration} is now the only live registration -- caller should start the read thread */
    boolean add(VehicleRegistration registration) {
        synchronized (lock) {
            boolean first = registrations.isEmpty();
            registrations.add(registration);
            return first;
        }
    }

    /** @return {@code true} once no registrations remain -- caller should shut down the shared socket/thread */
    boolean remove(VehicleRegistration registration) {
        synchronized (lock) {
            registrations.remove(registration);
            if (registration.claimedSysid != null) {
                claimsBySysid.remove(registration.claimedSysid, registration);
            }
            return registrations.isEmpty();
        }
    }

    /** Vehicles heard on this hub's socket that no registration currently claims (docs/plans/active/DRONE-INFRA-PLAN.md I-b). */
    List<MavlinkSocketHub.UnclaimedVehicle> unclaimedVehicles() {
        synchronized (lock) {
            return List.copyOf(unclaimed.values());
        }
    }

    /** Vehicles currently claimed by an open device on this hub's socket (docs/plans/active/DRONE-INFRA-PLAN.md I-b). */
    List<MavlinkSocketHub.ClaimedVehicle> claimedVehicles() {
        synchronized (lock) {
            List<MavlinkSocketHub.ClaimedVehicle> result = new ArrayList<>(registrations.size());
            for (VehicleRegistration r : registrations) {
                if (r.claimedSysid != null) {
                    result.add(new MavlinkSocketHub.ClaimedVehicle(
                            r.claimedSysid, r.deviceId, r.firmware, r.mavType, Instant.ofEpochMilli(r.lastHeardMillis)));
                }
            }
            return List.copyOf(result);
        }
    }

    /** {@code deviceId}'s command-TX coordinates on this hub, or {@code null} if it holds no claim right now. */
    MavlinkSocketHub.CommandTarget commandTarget(DeviceId deviceId) {
        synchronized (lock) {
            for (VehicleRegistration r : registrations) {
                if (r.deviceId.equals(deviceId) && r.claimedSysid != null) {
                    return new MavlinkSocketHub.CommandTarget(r.claimedSysid, r.firmware, r.mavType, r.lastSourceAddress);
                }
            }
            return null;
        }
    }

    /**
     * Resolves {@code message}'s sysid against the current claims: refreshes an existing claim's
     * last-heard/source-address/heartbeat info, attempts a new claim/re-election if nobody owns
     * this sysid yet, or records it as unclaimed. Called only from {@link MavlinkSocketHub}'s read
     * thread, once per inbound message.
     *
     * @return the owning {@link VehicleRegistration}, or {@code null} if this sysid belongs to no
     *         registration (pinned elsewhere, or every unpinned registration already holds a live claim)
     */
    VehicleRegistration resolve(int sysid, MavlinkMessage<?> message, InetSocketAddress sourceAddress, long now) {
        synchronized (lock) {
            VehicleRegistration owner = claimsBySysid.get(sysid);
            if (owner != null) {
                owner.lastHeardMillis = now;
            } else {
                owner = claim(sysid, now);
            }
            if (owner != null) {
                owner.lastSourceAddress = sourceAddress;
                captureHeartbeatInfo(owner, message);
            } else {
                recordUnclaimed(sysid, message, now);
            }
            return owner;
        }
    }

    /** Snapshot of every live registration, for {@link MavlinkSocketHub} to signal each publisher on shutdown/error. */
    List<VehicleRegistration> snapshot() {
        synchronized (lock) {
            return List.copyOf(registrations);
        }
    }

    /**
     * Must be called while holding {@link #lock}. Refreshes a claimed registration's firmware/
     * mavType label from a {@code HEARTBEAT} (docs/plans/active/DRONE-INFRA-PLAN.md I-b) -- a no-op for every
     * other message type.
     */
    private static void captureHeartbeatInfo(VehicleRegistration r, MavlinkMessage<?> message) {
        if (message.getPayload() instanceof Heartbeat heartbeat) {
            r.firmware = MavlinkTelemetryDecoder.firmwareLabel(heartbeat.autopilot().value());
            r.mavType = heartbeat.type().value();
        }
    }

    /** Must be called while holding {@link #lock}. Assigns {@code sysid} to a waiting registration, if any. */
    private VehicleRegistration claim(int sysid, long now) {
        for (VehicleRegistration r : registrations) {
            if (r.pinnedSysid != null && r.pinnedSysid == sysid && r.claimedSysid == null) {
                assignClaim(r, sysid, now);
                return r;
            }
        }
        for (VehicleRegistration r : registrations) {
            if (r.pinnedSysid != null) {
                continue;
            }
            if (r.claimedSysid == null) {
                assignClaim(r, sysid, now);
                return r;
            }
            if (now - r.lastHeardMillis > silenceWindowMillis) {
                claimsBySysid.remove(r.claimedSysid, r); // re-election: release the stale claim first
                assignClaim(r, sysid, now);
                return r;
            }
        }
        return null;
    }

    /** Must be called while holding {@link #lock}. */
    private void assignClaim(VehicleRegistration r, int sysid, long now) {
        r.claimedSysid = sysid;
        r.lastHeardMillis = now;
        r.decoder = new MavlinkTelemetryDecoder(r.deviceId); // fresh state -- see MavlinkSocketHub's class javadoc
        claimsBySysid.put(sysid, r);
        unclaimed.remove(sysid);
    }

    /** Must be called while holding {@link #lock}. */
    private void recordUnclaimed(int sysid, MavlinkMessage<?> message, long nowMillis) {
        MavlinkSocketHub.UnclaimedVehicle previous = unclaimed.remove(sysid); // remove-then-put refreshes recency order
        String firmware = previous == null ? null : previous.firmware();
        Integer mavType = previous == null ? null : previous.mavType();
        if (message.getPayload() instanceof Heartbeat heartbeat) {
            firmware = MavlinkTelemetryDecoder.firmwareLabel(heartbeat.autopilot().value());
            mavType = heartbeat.type().value();
        }
        if (unclaimed.size() >= maxUnclaimedVehicles) {
            Iterator<Integer> oldest = unclaimed.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        unclaimed.put(sysid, new MavlinkSocketHub.UnclaimedVehicle(sysid, firmware, mavType, Instant.ofEpochMilli(nowMillis)));
    }
}
