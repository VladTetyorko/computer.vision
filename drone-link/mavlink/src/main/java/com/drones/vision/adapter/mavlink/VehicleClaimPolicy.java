package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.CompId;
import com.drones.mavlink.PeerId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.session.HeartbeatInfo;
import com.drones.mavlink.session.Peer;
import com.drones.mavlink.session.PeerDirectory;
import com.drones.vision.kernel.DeviceId;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * Project policy — "which {@code Device} owns which sysid" — split out from what protocol facts
 * {@code mavlink-core}'s own {@link PeerDirectory} already tracks (docs/plans/active/MAVLINK-CORE-PLAN.md
 * §3.4, the sharpest cut in that plan): pinned-sysid claims, unpinned first-unclaimed-wins, 30s-
 * silence re-election, and the bounded (32) unclaimed registry. This is the direct successor to
 * pre-W4's {@code VehicleClaimRegistry} — same claim/re-election mechanism — but it no longer
 * parses {@code HEARTBEAT} fields itself: firmware/mavType/last-heard now come from {@link
 * PeerDirectory}/{@link Peer#heartbeat()}, which {@link MavlinkGateway}'s underlying {@code
 * MavlinkSession} already maintains for free from every decoded frame. {@code PeerDirectory}
 * answers "who is on the air" (protocol fact, owned by {@code mavlink-core}); this class answers
 * "which {@code Device} owns them" (project policy, owned here).
 *
 * <h2>Vehicle identity: sysid + the autopilot component</h2>
 * MAVLink identity is strictly {@code (sysid, compid)}, and {@link PeerDirectory} tracks peers at
 * that granularity — but this adapter's claim policy, like every version before it, demultiplexes
 * by <b>sysid alone</b> (see {@link MavlinkGateway}'s own javadoc for why: source-address/component
 * demux was descoped long before this wave, and remains the more correct signal for a companion
 * computer or radio relaying several vehicles). To turn a claimed sysid into a concrete {@link
 * PeerId} for querying {@code PeerDirectory} (firmware, last-heard, reply address), this class
 * looks up {@code (sysid, }{@value MavlinkFlightCommander#TARGET_COMPONENT_AUTOPILOT}{@code )} —
 * the autopilot component id every producer this module talks to (real ArduPilot firmware,
 * {@code MavlinkFeedTransmitter}, every test double) already sends as, and the same component id
 * this module's own command senders have always targeted (see {@link
 * MavlinkFlightCommander#TARGET_COMPONENT_AUTOPILOT}'s own javadoc). A vehicle whose telemetry
 * arrives from some other component id would still be sysid-claimed correctly, but would report as
 * "unreachable"/firmware-unknown here until its autopilot component itself transmits — an accepted
 * simplification, unchanged in spirit from the old sysid-only design's own known limits.
 *
 * <h2>Threading</h2>
 * Guarded entirely by one private monitor ({@link #lock}), the same shape the pre-W4 registry
 * used: {@link #add}/{@link #remove}/{@link #unclaimedVehicles()}/{@link #claimedVehicles()}/
 * {@link #commandTarget} are called from whatever thread calls {@code
 * MavlinkTelemetrySource#open}/{@code close}/{@code commandTarget}; {@link #resolve} runs only on
 * {@link MavlinkGateway}'s one dispatcher-subscription callback (itself always invoked from the
 * underlying session's single reader thread for that link). No concurrent collections, no volatile
 * fields — one lock, exactly as before. {@link PeerDirectory} itself needs no locking here: it is
 * independently thread-safe (backed by a {@code ConcurrentHashMap}).
 *
 * <p>Package-private, owned entirely by {@link MavlinkGateway} — not a domain/port type.
 */
final class VehicleClaimPolicy {

    private final PeerDirectory peers;
    private final long silenceWindowMillis;
    private final int maxUnclaimedVehicles;
    private final IntConsumer onClaimed;

    private final Object lock = new Object();
    private final List<VehicleRegistration> registrations = new ArrayList<>();
    private final Map<Integer, VehicleRegistration> claimsBySysid = new HashMap<>();
    private final LinkedHashMap<Integer, MavlinkGateway.UnclaimedVehicle> unclaimed = new LinkedHashMap<>();

    /**
     * @param peers                the gateway's own session {@link PeerDirectory} -- the source of
     *                             truth for firmware/mavType/last-heard/reply-address, never
     *                             mutated here
     * @param maxUnclaimedVehicles bound on the unclaimed registry's size (docs/plans/active/
     *                             LAYERING-REFACTOR-PLAN.md §2.2's {@code
     *                             vision.mavlink.max-unclaimed-vehicles}, sourced from {@link
     *                             MavlinkSettings#maxUnclaimedVehicles()}) -- {@code
     *                             PeerDirectory} itself never evicts a once-known peer, so this
     *                             bound still matters here even though it no longer matters there
     * @param onClaimed            invoked with {@code sysid} exactly once per claim event -- both an
     *                             initial claim and a silence-window re-election (docs/plans/active/
     *                             MAVLINK-COMMANDS-PLAN.md P2) -- always from inside {@link #lock},
     *                             so on the same single reader thread every other policy method
     *                             callable from {@link #resolve} runs on; never for a lobby hold
     *                             ({@link #recordUnclaimed} never touches {@link #registrations}/
     *                             calls this). {@link MavlinkGateway} wires this to {@link
     *                             MavlinkStreamNegotiator#negotiate}, which itself only queues
     *                             non-blocking async sends -- see that class's own javadoc for why
     *                             firing it from here, inside the lock, is safe.
     */
    VehicleClaimPolicy(PeerDirectory peers, long silenceWindowMillis, int maxUnclaimedVehicles,
                        IntConsumer onClaimed) {
        this.peers = peers;
        this.silenceWindowMillis = silenceWindowMillis;
        this.maxUnclaimedVehicles = maxUnclaimedVehicles;
        this.onClaimed = Objects.requireNonNull(onClaimed, "onClaimed must not be null");
    }

    void add(VehicleRegistration registration) {
        synchronized (lock) {
            registrations.add(registration);
        }
    }

    /** @return {@code true} once no registrations remain -- caller should shut the gateway down */
    boolean remove(VehicleRegistration registration) {
        synchronized (lock) {
            registrations.remove(registration);
            if (registration.claimedSysid != null) {
                claimsBySysid.remove(registration.claimedSysid, registration);
            }
            return registrations.isEmpty();
        }
    }

    /**
     * Closes every registered device's publisher <b>exceptionally</b> with {@code cause}
     * (FLEET-RADIO-PLAN.md D5) rather than leaving them to be abandoned by a later {@code
     * unregister} — used only when the underlying socket itself has failed ({@link
     * MavlinkGateway#handleLinkFailure}), so every device sharing this gateway sees a genuine
     * failure on its {@code Flow.Subscriber}, not an unexplained silence or an orderly
     * end-of-stream. Does not itself touch {@link #registrations}/{@link #claimsBySysid} — the
     * caller closes the gateway (and, above it, {@code MavlinkTelemetrySource} evicts it from its
     * gateway map) immediately afterward, at which point this policy's own bookkeeping is moot.
     */
    void closeAllPublishersExceptionally(Throwable cause) {
        synchronized (lock) {
            for (VehicleRegistration r : registrations) {
                r.publisher.closeExceptionally(cause);
            }
        }
    }

    /**
     * {@code true} once this policy holds no registrations at all — used by {@link
     * MavlinkGateway#releaseLobby()} to decide whether the gateway itself should now close (a
     * lobby-held gateway with zero devices closes exactly like an ordinary {@link #remove} that
     * empties the registry would).
     */
    boolean isEmpty() {
        synchronized (lock) {
            return registrations.isEmpty();
        }
    }

    /** Vehicles heard on this gateway's socket that no registration currently claims (docs/plans/active/DRONE-INFRA-PLAN.md I-b). */
    List<MavlinkGateway.UnclaimedVehicle> unclaimedVehicles() {
        synchronized (lock) {
            return List.copyOf(unclaimed.values());
        }
    }

    /** Vehicles currently claimed by an open device on this gateway's socket (docs/plans/active/DRONE-INFRA-PLAN.md I-b). */
    List<MavlinkGateway.ClaimedVehicle> claimedVehicles() {
        synchronized (lock) {
            List<MavlinkGateway.ClaimedVehicle> result = new ArrayList<>(registrations.size());
            for (VehicleRegistration r : registrations) {
                if (r.claimedSysid != null) {
                    VehicleFacts facts = factsFor(r.claimedSysid);
                    result.add(new MavlinkGateway.ClaimedVehicle(
                            r.claimedSysid, r.deviceId, facts.firmware, facts.mavType, facts.lastHeard));
                }
            }
            return List.copyOf(result);
        }
    }

    /** {@code deviceId}'s command-TX coordinates on this gateway, or {@code null} if it holds no claim right now. */
    MavlinkGateway.CommandTarget commandTarget(DeviceId deviceId) {
        synchronized (lock) {
            for (VehicleRegistration r : registrations) {
                if (r.deviceId.equals(deviceId) && r.claimedSysid != null) {
                    VehicleFacts facts = factsFor(r.claimedSysid);
                    return new MavlinkGateway.CommandTarget(r.claimedSysid, facts.firmware, facts.mavType, facts.sourceAddress);
                }
            }
            return null;
        }
    }

    /**
     * Resolves {@code sysid} against the current claims: returns its owning registration if one
     * already claims it, attempts a new claim/re-election if nobody does yet, or records it as
     * unclaimed. Called only from {@link MavlinkGateway}'s dispatcher-subscription callback, once
     * per inbound frame.
     *
     * @return the owning {@link VehicleRegistration}, or {@code null} if this sysid belongs to no
     *         registration (pinned elsewhere, or every unpinned registration already holds a live claim)
     */
    VehicleRegistration resolve(int sysid) {
        synchronized (lock) {
            VehicleRegistration owner = claimsBySysid.get(sysid);
            if (owner == null) {
                owner = claim(sysid);
            }
            if (owner == null) {
                recordUnclaimed(sysid);
            }
            return owner;
        }
    }

    /** Must be called while holding {@link #lock}. Assigns {@code sysid} to a waiting registration, if any. */
    private VehicleRegistration claim(int sysid) {
        for (VehicleRegistration r : registrations) {
            if (r.pinnedSysid != null && r.pinnedSysid == sysid && r.claimedSysid == null) {
                assignClaim(r, sysid);
                return r;
            }
        }
        for (VehicleRegistration r : registrations) {
            if (r.pinnedSysid != null) {
                continue;
            }
            if (r.claimedSysid == null) {
                assignClaim(r, sysid);
                return r;
            }
            if (isSilent(r.claimedSysid)) {
                claimsBySysid.remove(r.claimedSysid, r); // re-election: release the stale claim first
                assignClaim(r, sysid);
                return r;
            }
        }
        return null;
    }

    /** Must be called while holding {@link #lock}. {@code true} if nothing has been heard from {@code claimedSysid} for the silence window (or ever). */
    private boolean isSilent(int claimedSysid) {
        Peer peer = peerFor(claimedSysid);
        if (peer == null) {
            return true;
        }
        return Duration.between(peer.lastHeard(), Instant.now()).toMillis() > silenceWindowMillis;
    }

    /** Must be called while holding {@link #lock}. */
    private void assignClaim(VehicleRegistration r, int sysid) {
        r.claimedSysid = sysid;
        r.decoder = new MavlinkTelemetryDecoder(r.deviceId); // fresh state -- see MavlinkGateway's class javadoc
        claimsBySysid.put(sysid, r);
        unclaimed.remove(sysid);
        onClaimed.accept(sysid); // MAVLINK-COMMANDS-PLAN P2 -- fires stream negotiation for this sysid
    }

    /** Must be called while holding {@link #lock}. */
    private void recordUnclaimed(int sysid) {
        unclaimed.remove(sysid); // remove-then-put refreshes recency order
        VehicleFacts facts = factsFor(sysid);
        if (unclaimed.size() >= maxUnclaimedVehicles) {
            Iterator<Integer> oldest = unclaimed.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        unclaimed.put(sysid, new MavlinkGateway.UnclaimedVehicle(sysid, facts.firmware, facts.mavType, facts.lastHeard));
    }

    /** The autopilot-component {@link Peer} for {@code sysid} -- see this class's own javadoc for why compid is fixed. */
    private Peer peerFor(int sysid) {
        return peers.peer(new PeerId(new SysId(sysid), new CompId(MavlinkFlightCommander.TARGET_COMPONENT_AUTOPILOT)));
    }

    private VehicleFacts factsFor(int sysid) {
        Peer peer = peerFor(sysid);
        if (peer == null) {
            return new VehicleFacts(null, null, Instant.now(), null);
        }
        HeartbeatInfo heartbeat = peer.heartbeat();
        String firmware = heartbeat == null ? null : MavlinkTelemetryDecoder.firmwareLabel(heartbeat.autopilot());
        Integer mavType = heartbeat == null ? null : heartbeat.mavType();
        InetSocketAddress sourceAddress = new InetSocketAddress(peer.address().host(), peer.address().port());
        return new VehicleFacts(firmware, mavType, peer.lastHeard(), sourceAddress);
    }

    /** {@code sourceAddress} is {@code null} only when {@code sysid} has never actually been heard (see {@link #peerFor}). */
    private record VehicleFacts(String firmware, Integer mavType, Instant lastHeard, InetSocketAddress sourceAddress) {
    }
}
