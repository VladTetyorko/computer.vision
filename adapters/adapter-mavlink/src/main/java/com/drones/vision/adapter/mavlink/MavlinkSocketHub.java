package com.drones.vision.adapter.mavlink;

import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.Telemetry;

import io.dronefleet.mavlink.MavlinkConnection;
import io.dronefleet.mavlink.MavlinkMessage;
import io.dronefleet.mavlink.minimal.Heartbeat;

import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One shared {@link DatagramSocket} + one dedicated read thread per distinct bind address
 * ({@code host:port}), reference-counted across every {@link MavlinkTelemetrySource#open}
 * call that targets it (docs/DRONE-INFRA-PLAN.md I-a) — the fleet-gateway core that lets N
 * aircraft coexist on the one well-known GCS port (14550) every telemetry radio pushes to by
 * default, instead of the pre-I-a world of one socket per device.
 *
 * <h2>Demux: by system id only, never source address</h2>
 * A datagram is only attributable to a MAVLink system <b>after</b> it has been parsed —
 * {@link MavlinkMessage#getOriginSystemId()} exists only on the decoded message, not the raw
 * {@link java.net.DatagramPacket}. {@link MavlinkUdpInputStream} (this adapter's existing bridge
 * from a socket to the continuous byte stream {@link MavlinkConnection} needs) already
 * concatenates successive datagrams into one logical byte stream and does not preserve which
 * source address any given byte came from — a pre-existing simplification this task did not
 * revisit. Re-architecting that bridge to carry a source address alongside every byte (or every
 * frame) would be real surgery on a component whose entire job is "look like a continuous
 * stream" to a library with no concept of datagram boundaries. Source-address demux was
 * therefore <b>descoped</b>: this hub demultiplexes by {@code sysid} alone, exactly how MAVLink's
 * own application layer already identifies a vehicle (every message on the wire carries its
 * origin system id; a well-behaved sender never reuses another vehicle's sysid on the same
 * link). This is also more correct in the field than source-address demux would be, since a
 * companion computer or telemetry radio relaying multiple vehicles is itself the one source
 * address for every one of them.
 *
 * <h2>Claim semantics</h2>
 * <ul>
 *   <li><b>Pinned</b> ({@code StreamDescriptor.options["sysid"]} set — see
 *       {@link MavlinkTelemetrySource}) claims exactly that sysid, permanently, the first time a
 *       message from it arrives; it never re-elects.</li>
 *   <li><b>Unpinned</b> claims the first sysid heard on this socket that nothing else already
 *       claims (today's single-device behavior, generalized per socket). If its claimed sysid
 *       then falls silent for {@code silenceWindowMillis} (production default 30s), it may
 *       re-elect to a different, still-unclaimed sysid — fixing the pre-I-a "no re-adoption"
 *       gotcha. The check is entirely lazy: there is no timer, only a comparison made against
 *       the current wall clock the next time a message from some other unclaimed sysid arrives
 *       (see {@link #claim}) — no threads beyond the one read loop.</li>
 *   <li><b>Unclaimed</b> — a sysid heard on this socket that no registration (pinned or unpinned)
 *       currently wants is recorded in a small bounded registry (see {@link
 *       #unclaimedVehicles()}) instead of silently dropped, for docs/DRONE-INFRA-PLAN.md I-b's
 *       future plug-and-fly discovery to consume.</li>
 * </ul>
 * Each claim or re-election creates a <b>fresh</b> {@link MavlinkTelemetryDecoder} for the
 * claiming registration: a decoder's accumulated fields (position, battery, flight state, ...)
 * belong to one physical vehicle, and reusing one across a re-election would leak the old
 * vehicle's stale values into the new one's first samples.
 *
 * <h2>Threading</h2>
 * {@link #register}/{@link #unregister}/{@link #unclaimedVehicles()} are called from whatever
 * thread calls {@link MavlinkTelemetrySource#open}/{@code close}; message routing and claim/
 * re-election decisions happen only on this hub's own dedicated read thread. All mutable state
 * shared between them ({@code registrations}, {@code claimsBySysid}, {@code unclaimed}) is
 * guarded by one monitor ({@link #lock}) — no concurrent collections, no volatile fields beyond
 * the socket/thread handles {@link #unregister} must reach from a caller thread to shut down.
 *
 * <p>Not a domain/port type — package-private, owned entirely by {@link MavlinkTelemetrySource},
 * the only class that constructs, registers with, or queries one.
 */
final class MavlinkSocketHub {

    private static final long CLOSE_JOIN_TIMEOUT_MILLIS = 5_000L;
    static final int MAX_UNCLAIMED_VEHICLES = 32;

    private final String bindHost;
    private final int port;
    private final long silenceWindowMillis;

    private final Object lock = new Object();
    private final List<VehicleRegistration> registrations = new ArrayList<>();
    private final Map<Integer, VehicleRegistration> claimsBySysid = new HashMap<>();
    private final LinkedHashMap<Integer, UnclaimedVehicle> unclaimed = new LinkedHashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Thread readThread;
    private volatile DatagramSocket socket;

    MavlinkSocketHub(String bindHost, int port, long silenceWindowMillis) {
        this.bindHost = bindHost;
        this.port = port;
        this.silenceWindowMillis = silenceWindowMillis;
    }

    /** Once closed (last registration released, or the read loop hit an unrecoverable error), never reused. */
    boolean isClosed() {
        return closed.get();
    }

    /**
     * Registers a device's interest in this socket, starting the shared read thread on the first
     * registration. {@code pinnedSysid} {@code null} means unpinned (first-unclaimed-wins,
     * re-electable); a non-null value pins the registration to exactly that system id.
     */
    VehicleRegistration register(DeviceId deviceId, Integer pinnedSysid, SubmissionPublisher<Telemetry> publisher) {
        VehicleRegistration registration = new VehicleRegistration(deviceId, pinnedSysid, publisher);
        boolean first;
        synchronized (lock) {
            first = registrations.isEmpty();
            registrations.add(registration);
        }
        if (first) {
            readThread = new Thread(this::runReadLoop, "mavlink-telemetry-hub-" + bindHost + "-" + port);
            readThread.setDaemon(true);
            readThread.start();
        }
        return registration;
    }

    /**
     * Releases a device's registration and its claim, if any, so another registration may pick
     * up its sysid. Shuts down the shared socket/thread once this was the last registration.
     *
     * @return {@code true} once this hub has no remaining registrations — the caller should evict
     *         it from {@code MavlinkTelemetrySource}'s hub map so the next {@code open()} for
     *         this bind address builds a fresh hub
     */
    boolean unregister(VehicleRegistration registration) {
        int remaining;
        synchronized (lock) {
            registrations.remove(registration);
            if (registration.claimedSysid != null) {
                claimsBySysid.remove(registration.claimedSysid, registration);
            }
            remaining = registrations.size();
        }
        if (remaining == 0) {
            shutdown();
        }
        return remaining == 0;
    }

    /** Vehicles heard on this socket that no registration currently claims (docs/DRONE-INFRA-PLAN.md I-b). */
    List<UnclaimedVehicle> unclaimedVehicles() {
        synchronized (lock) {
            return List.copyOf(unclaimed.values());
        }
    }

    /**
     * Vehicles currently claimed by an open device on this socket (docs/DRONE-INFRA-PLAN.md I-b):
     * lets a discovery scan label an already-registered vehicle instead of inviting a duplicate
     * asset. {@code firmware}/{@code mavType} are {@code null} until a {@code HEARTBEAT} from the
     * claiming vehicle has actually arrived (the same "unknown until observed" honesty {@link
     * #unclaimedVehicles()} already follows).
     */
    List<ClaimedVehicle> claimedVehicles() {
        synchronized (lock) {
            List<ClaimedVehicle> result = new ArrayList<>(registrations.size());
            for (VehicleRegistration r : registrations) {
                if (r.claimedSysid != null) {
                    result.add(new ClaimedVehicle(
                            r.claimedSysid, r.deviceId, r.firmware, r.mavType, Instant.ofEpochMilli(r.lastHeardMillis)));
                }
            }
            return List.copyOf(result);
        }
    }

    private void runReadLoop() {
        boolean errored = false;
        DatagramSocket sock = null;
        try {
            sock = new DatagramSocket(null);
            sock.setReuseAddress(true);
            sock.bind(new InetSocketAddress(bindHost, port));
            socket = sock;

            MavlinkConnection connection = MavlinkConnection.create(
                    new MavlinkUdpInputStream(sock), OutputStream.nullOutputStream());

            while (!closed.get()) {
                // Blocks; malformed/garbage datagrams are resynced past internally by
                // MavlinkConnection/MavlinkFrameReader -- see MavlinkTelemetrySource's javadoc.
                MavlinkMessage<?> message = connection.next();
                routeMessage(message);
            }
        } catch (Exception e) {
            if (shutdown()) {
                // shutdown() winning the CAS means this was NOT a close()-induced SocketException:
                // a genuine unrecoverable failure -- signal every live registration's publisher,
                // per TelemetrySourcePort's onError contract (generalized from the single-device
                // idiom to every registration this hub currently serves).
                errored = true;
                for (VehicleRegistration registration : snapshotRegistrations()) {
                    registration.publisher.closeExceptionally(e);
                }
            }
        } finally {
            closeQuietly(sock);
            if (!errored) {
                for (VehicleRegistration registration : snapshotRegistrations()) {
                    registration.publisher.close();
                }
            }
        }
    }

    private void routeMessage(MavlinkMessage<?> message) {
        int sysid = message.getOriginSystemId();
        long now = System.currentTimeMillis();
        VehicleRegistration owner;
        synchronized (lock) {
            owner = claimsBySysid.get(sysid);
            if (owner != null) {
                owner.lastHeardMillis = now;
            } else {
                owner = claim(sysid, now);
            }
            if (owner != null) {
                captureHeartbeatInfo(owner, message);
            } else {
                recordUnclaimed(sysid, message, now);
            }
        }
        if (owner != null) {
            Telemetry sample = owner.decoder.accept(message);
            if (sample != null) {
                owner.publisher.submit(sample);
            }
        }
    }

    /**
     * Must be called while holding {@link #lock}. Refreshes a claimed registration's firmware/
     * mavType label from a {@code HEARTBEAT} (docs/DRONE-INFRA-PLAN.md I-b: lets {@link
     * #claimedVehicles()} label a claimed vehicle the same way {@link #recordUnclaimed} already
     * labels an unclaimed one) — a no-op for every other message type.
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
        r.decoder = new MavlinkTelemetryDecoder(r.deviceId); // fresh state -- see class javadoc
        claimsBySysid.put(sysid, r);
        unclaimed.remove(sysid);
    }

    /** Must be called while holding {@link #lock}. */
    private void recordUnclaimed(int sysid, MavlinkMessage<?> message, long nowMillis) {
        UnclaimedVehicle previous = unclaimed.remove(sysid); // remove-then-put refreshes recency order
        String firmware = previous == null ? null : previous.firmware();
        Integer mavType = previous == null ? null : previous.mavType();
        if (message.getPayload() instanceof Heartbeat heartbeat) {
            firmware = MavlinkTelemetryDecoder.firmwareLabel(heartbeat.autopilot().value());
            mavType = heartbeat.type().value();
        }
        if (unclaimed.size() >= MAX_UNCLAIMED_VEHICLES) {
            Iterator<Integer> oldest = unclaimed.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        unclaimed.put(sysid, new UnclaimedVehicle(sysid, firmware, mavType, Instant.ofEpochMilli(nowMillis)));
    }

    private List<VehicleRegistration> snapshotRegistrations() {
        synchronized (lock) {
            return List.copyOf(registrations);
        }
    }

    /** @return {@code true} if this call performed the close (i.e. it was not already requested/underway) */
    private boolean shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return false;
        }
        closeQuietly(socket); // unblocks a pending receive()
        Thread thread = readThread;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt(); // best-effort; a blocked receive() may not respond to this alone
            try {
                thread.join(CLOSE_JOIN_TIMEOUT_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return true;
    }

    private static void closeQuietly(DatagramSocket socket) {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    /** One device's interest in this hub's socket: a possible sysid pin, and the resolved claim/decoder state. */
    static final class VehicleRegistration {
        private final DeviceId deviceId;
        private final Integer pinnedSysid;
        private final SubmissionPublisher<Telemetry> publisher;

        // Mutated only on the hub's own read thread, always under MavlinkSocketHub#lock; read from a
        // caller thread only under that same lock (in unregister()/claimedVehicles()) -- see
        // MavlinkSocketHub's own "Threading" javadoc section for why plain fields (no volatile) are
        // sufficient here.
        private Integer claimedSysid;
        private long lastHeardMillis;
        private MavlinkTelemetryDecoder decoder;
        private String firmware; // docs/DRONE-INFRA-PLAN.md I-b -- from the most recent HEARTBEAT, null until one arrives
        private Integer mavType; // ditto

        private VehicleRegistration(DeviceId deviceId, Integer pinnedSysid, SubmissionPublisher<Telemetry> publisher) {
            this.deviceId = deviceId;
            this.pinnedSysid = pinnedSysid;
            this.publisher = publisher;
        }
    }

    /** A sysid heard on the hub's socket that no registration currently claims (docs/DRONE-INFRA-PLAN.md I-b). */
    record UnclaimedVehicle(int sysid, String firmware, Integer mavType, Instant lastHeard) {
    }

    /** A sysid on this hub's socket currently claimed by an open device (docs/DRONE-INFRA-PLAN.md I-b). */
    record ClaimedVehicle(int sysid, DeviceId deviceId, String firmware, Integer mavType, Instant lastHeard) {
    }
}
