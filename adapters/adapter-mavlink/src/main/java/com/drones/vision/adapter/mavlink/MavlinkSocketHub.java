package com.drones.vision.adapter.mavlink;

import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.Telemetry;

import io.dronefleet.mavlink.MavlinkConnection;
import io.dronefleet.mavlink.MavlinkMessage;
import io.dronefleet.mavlink.common.CommandAck;

import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One shared {@link DatagramSocket} + one dedicated read thread per distinct bind address
 * ({@code host:port}), reference-counted across every {@link MavlinkTelemetrySource#open}
 * call that targets it (docs/plans/active/DRONE-INFRA-PLAN.md I-a) — the fleet-gateway core that lets N
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
 *       (see {@link VehicleClaimRegistry#resolve}) — no threads beyond the one read loop.</li>
 *   <li><b>Unclaimed</b> — a sysid heard on this socket that no registration (pinned or unpinned)
 *       currently wants is recorded in a small bounded registry (see {@link
 *       #unclaimedVehicles()}) instead of silently dropped, for docs/plans/active/DRONE-INFRA-PLAN.md I-b's
 *       future plug-and-fly discovery to consume.</li>
 * </ul>
 * Each claim or re-election creates a <b>fresh</b> {@link MavlinkTelemetryDecoder} for the
 * claiming registration: a decoder's accumulated fields (position, battery, flight state, ...)
 * belong to one physical vehicle, and reusing one across a re-election would leak the old
 * vehicle's stale values into the new one's first samples.
 *
 * <h2>Command TX seam (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1)</h2>
 * This hub is receive-only by construction (one read thread, no outbound traffic of its own), but
 * it is the only place that knows a claimed vehicle's <b>last-seen UDP source address</b> — the
 * one piece of information a command sender needs that {@link MavlinkTelemetryDecoder}/{@link
 * MavlinkTelemetrySource} never tracked before (source-address demux was descoped for
 * <i>routing</i>, see above, but sending a reply still needs an address to send it to). Each
 * {@link VehicleRegistration} therefore also remembers {@code lastSourceAddress}, refreshed
 * alongside {@code lastHeardMillis} on every message from that vehicle (see {@link #routeMessage}
 * and {@link MavlinkUdpInputStream#lastSourceAddress()}), exposed via {@link
 * #commandTarget(DeviceId)}. A command sender ({@code MavlinkFlightCommander}) reuses this hub's
 * own shared socket ({@link #socket()}) to send rather than opening a second one, and registers a
 * one-shot waiter for the matching {@code COMMAND_ACK} via {@link #awaitAck}/{@link
 * #cancelAckWait} — a narrow seam into this hub's own read loop instead of a second socket reader
 * duplicating {@link MavlinkUdpInputStream}'s resync/demux machinery just to watch for one reply.
 *
 * <h2>Threading</h2>
 * {@link #register}/{@link #unregister}/{@link #unclaimedVehicles()}/{@link
 * #commandTarget(DeviceId)}/{@link #awaitAck}/{@link #cancelAckWait} are called from whatever
 * thread calls {@link MavlinkTelemetrySource#open}/{@code close}/{@code commandTarget}/the flight
 * commander; message routing and claim/re-election decisions happen only on this hub's own
 * dedicated read thread. All mutable claim/registration state lives in {@link
 * VehicleClaimRegistry}, guarded by its own monitor; all pending-ack state lives in {@link
 * CommandAckRegistry}, guarded by a separate monitor — the two never need to be atomic with each
 * other (docs/plans/active/LAYERING-REFACTOR-PLAN.md E2 split this hub's original single lock into those two
 * collaborators' own locks for exactly that reason). This class's own fields are limited to the
 * socket/thread handles {@link #unregister} must reach from a caller thread to shut down (also
 * read by {@link #socket()} for command TX, for the same reason).
 *
 * <p>Not a domain/port type — package-private, owned entirely by {@link MavlinkTelemetrySource},
 * the only class that constructs, registers with, or queries one.
 */
final class MavlinkSocketHub {

    private final String bindHost;
    private final int port;
    private final long closeJoinTimeoutMillis;
    private final VehicleClaimRegistry claimRegistry;
    private final CommandAckRegistry ackRegistry = new CommandAckRegistry();

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Thread readThread;
    private volatile DatagramSocket socket;

    /**
     * @param settings supplies {@link MavlinkSettings#silenceWindow()} (unpinned re-election
     *                 window), {@link MavlinkSettings#maxUnclaimedVehicles()} (bounded unclaimed
     *                 registry cap), and {@link MavlinkSettings#closeJoinTimeout()} (how long
     *                 {@link #shutdown()} awaits the read thread) — passed as one object rather
     *                 than three primitives (docs/plans/active/LAYERING-REFACTOR-PLAN.md §1.3 rule 3), safe
     *                 since {@link MavlinkSettings} already lives in this same package.
     */
    MavlinkSocketHub(String bindHost, int port, MavlinkSettings settings) {
        this.bindHost = bindHost;
        this.port = port;
        this.closeJoinTimeoutMillis = settings.closeJoinTimeout().toMillis();
        this.claimRegistry = new VehicleClaimRegistry(settings.silenceWindow().toMillis(), settings.maxUnclaimedVehicles());
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
        if (claimRegistry.add(registration)) {
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
        boolean empty = claimRegistry.remove(registration);
        if (empty) {
            shutdown();
        }
        return empty;
    }

    /** Vehicles heard on this socket that no registration currently claims (docs/plans/active/DRONE-INFRA-PLAN.md I-b). */
    List<UnclaimedVehicle> unclaimedVehicles() {
        return claimRegistry.unclaimedVehicles();
    }

    /**
     * Vehicles currently claimed by an open device on this socket (docs/plans/active/DRONE-INFRA-PLAN.md I-b):
     * lets a discovery scan label an already-registered vehicle instead of inviting a duplicate
     * asset. {@code firmware}/{@code mavType} are {@code null} until a {@code HEARTBEAT} from the
     * claiming vehicle has actually arrived (the same "unknown until observed" honesty {@link
     * #unclaimedVehicles()} already follows).
     */
    List<ClaimedVehicle> claimedVehicles() {
        return claimRegistry.claimedVehicles();
    }

    /**
     * The current shared socket, so a command sender can push a reply through the same socket
     * this hub reads from instead of opening a second one (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1).
     * {@code null} before the read thread has bound, or once this hub has shut down.
     */
    DatagramSocket socket() {
        return socket;
    }

    /**
     * The command-TX coordinates for {@code deviceId}'s current claim on this hub — its sysid,
     * last-known firmware/mavType (from the most recent {@code HEARTBEAT}, possibly {@code null}
     * if none has arrived yet), and last-seen UDP source address (docs/plans/active/DRONE-INFRA-PLAN.md I-e
     * Stage 1) — or {@code null} if {@code deviceId} holds no claim on this hub right now (never
     * opened here, pinned to a sysid never yet heard, or an unpinned claim re-elected away).
     */
    CommandTarget commandTarget(DeviceId deviceId) {
        return claimRegistry.commandTarget(deviceId);
    }

    /**
     * Registers interest in the next {@code COMMAND_ACK} carrying {@code commandId} (a raw
     * {@code MAV_CMD_*} value) from {@code sysid} — the narrow read-loop seam described in this
     * class's own javadoc (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1). The returned future completes on
     * this hub's read thread the instant a matching ack is routed; it is never completed at all if
     * none ever arrives, so the caller must apply its own timeout (e.g. {@code
     * future.get(timeout, unit)}) and always pair this with {@link #cancelAckWait} in a {@code
     * finally} block to avoid leaking a waiter nothing will ever complete.
     */
    CompletableFuture<CommandAck> awaitAck(int sysid, int commandId) {
        return ackRegistry.await(sysid, commandId);
    }

    /**
     * Releases a waiter registered via {@link #awaitAck}. Idempotent — safe to call whether the
     * future already completed, already timed out on the caller's side, or was never actually
     * pending (e.g. this hub shut down first).
     */
    void cancelAckWait(int sysid, int commandId) {
        ackRegistry.cancel(sysid, commandId);
    }

    private void runReadLoop() {
        boolean errored = false;
        DatagramSocket sock = null;
        try {
            sock = new DatagramSocket(null);
            sock.setReuseAddress(true);
            sock.bind(new InetSocketAddress(bindHost, port));
            socket = sock;

            MavlinkUdpInputStream input = new MavlinkUdpInputStream(sock);
            MavlinkConnection connection = MavlinkConnection.create(input, OutputStream.nullOutputStream());

            while (!closed.get()) {
                // Blocks; malformed/garbage datagrams are resynced past internally by
                // MavlinkConnection/MavlinkFrameReader -- see MavlinkTelemetrySource's javadoc.
                MavlinkMessage<?> message = connection.next();
                routeMessage(message, input.lastSourceAddress());
            }
        } catch (Exception e) {
            if (shutdown()) {
                // shutdown() winning the CAS means this was NOT a close()-induced SocketException:
                // a genuine unrecoverable failure -- signal every live registration's publisher,
                // per TelemetrySourcePort's onError contract (generalized from the single-device
                // idiom to every registration this hub currently serves).
                errored = true;
                for (VehicleRegistration registration : claimRegistry.snapshot()) {
                    registration.publisher.closeExceptionally(e);
                }
            }
        } finally {
            closeQuietly(sock);
            if (!errored) {
                for (VehicleRegistration registration : claimRegistry.snapshot()) {
                    registration.publisher.close();
                }
            }
        }
    }

    private void routeMessage(MavlinkMessage<?> message, InetSocketAddress sourceAddress) {
        int sysid = message.getOriginSystemId();
        long now = System.currentTimeMillis();

        VehicleRegistration owner = claimRegistry.resolve(sysid, message, sourceAddress, now);

        if (message.getPayload() instanceof CommandAck ack) {
            CompletableFuture<CommandAck> ackWaiter = ackRegistry.claimWaiter(sysid, ack);
            if (ackWaiter != null) {
                ackWaiter.complete(ack);
            }
        }

        if (owner != null) {
            Telemetry sample = owner.decoder.accept(message);
            if (sample != null) {
                owner.publisher.submit(sample);
            }
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
                thread.join(closeJoinTimeoutMillis);
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

    /** A sysid heard on the hub's socket that no registration currently claims (docs/plans/active/DRONE-INFRA-PLAN.md I-b). */
    record UnclaimedVehicle(int sysid, String firmware, Integer mavType, Instant lastHeard) {
    }

    /** A sysid on this hub's socket currently claimed by an open device (docs/plans/active/DRONE-INFRA-PLAN.md I-b). */
    record ClaimedVehicle(int sysid, DeviceId deviceId, String firmware, Integer mavType, Instant lastHeard) {
    }

    /**
     * A currently-claimed vehicle's command-TX coordinates (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1):
     * which sysid, its firmware/mavType (for RTL mode-number resolution — {@code null} until a
     * {@code HEARTBEAT} has actually arrived, same "unknown until observed" honesty as {@link
     * UnclaimedVehicle}/{@link ClaimedVehicle}), and where to send a reply ({@code null} only in
     * the unreachable case of a claim with no traffic behind it at all — see {@link #commandTarget}).
     */
    record CommandTarget(int sysid, String firmware, Integer mavType, InetSocketAddress sourceAddress) {
    }
}
