package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.codec.FrameSink;
import com.drones.mavlink.codec.MavFrame;
import com.drones.mavlink.config.MavlinkCoreSettings;
import com.drones.mavlink.session.Correlator;
import com.drones.mavlink.session.MavlinkNode;
import com.drones.mavlink.session.MavlinkSession;
import com.drones.mavlink.session.MessageFilter;
import com.drones.mavlink.session.PeerDirectory;
import com.drones.mavlink.session.Subscription;
import com.drones.mavlink.transport.UdpListenLink;

import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.Telemetry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One shared MAVLink gateway per distinct bind address ({@code host:port}), reference-counted
 * across every {@link MavlinkTelemetrySource#open} call that targets it (docs/plans/active/
 * DRONE-INFRA-PLAN.md I-a; docs/plans/active/MAVLINK-CORE-PLAN.md W4 rebuilt this class directly on
 * {@code mavlink-core} in place of the pre-W4 {@code MavlinkSocketHub}, which hand-rolled the
 * socket/read-thread/dispatch machinery {@code mavlink-core}'s L1-L3 now owns) — the fleet-gateway
 * core that lets N aircraft coexist on the one well-known GCS port (14550) every telemetry radio
 * pushes to by default.
 *
 * <h2>What this class owns</h2>
 * <ul>
 *   <li>a {@link UdpListenLink} — binds {@code host:port} immediately, in this constructor;</li>
 *   <li>a {@link MavlinkSession}, constructed with {@link MavlinkNode#groundStation()} (sysid 255 /
 *       compid 190 -- the same GCS identity this adapter has always used) — the composition root
 *       that owns the link's reader thread, {@link PeerDirectory}, {@link Correlator}, {@link
 *       com.drones.mavlink.session.Dispatcher}, and {@link FrameSink};</li>
 *   <li>a {@link VehicleClaimPolicy} — <b>project</b> policy (which {@code Device} owns which
 *       sysid), deliberately split from the session's own <b>protocol</b> facts (who is on the
 *       air) per docs/plans/active/MAVLINK-CORE-PLAN.md §3.4;</li>
 *   <li>one {@link MavlinkTelemetryDecoder} per live claim, created fresh by {@link
 *       VehicleClaimPolicy} on every claim/re-election — a decoder's accumulated fields belong to
 *       one physical vehicle, and reusing one across a claim change would leak the old vehicle's
 *       stale values into the new one's first samples. This rule is load-bearing and unchanged
 *       from the pre-W4 design.</li>
 * </ul>
 * It subscribes to {@code session.dispatcher()} once, for every frame; each dispatched frame is
 * handed to {@link VehicleClaimPolicy#resolve} to find the owning registration (by sysid alone --
 * see that class's own javadoc for why), and the frame's raw payload is fed to that registration's
 * decoder, submitting the resulting {@link Telemetry} to its publisher.
 *
 * <h2>Demux: by system id only, never source address</h2>
 * Unchanged from the pre-W4 design: a companion computer or telemetry radio relaying several
 * vehicles is itself the one physical source for every one of them, so source-address demux would
 * be <i>less</i> useful, not more, even though {@code mavlink-core}'s own transport layer could now
 * expose it. This gateway demultiplexes by {@code sysid} alone, exactly how MAVLink's own
 * application layer already identifies a vehicle.
 *
 * <h2>Claim semantics</h2>
 * <ul>
 *   <li><b>Pinned</b> ({@code StreamDescriptor.options["sysid"]} set — see
 *       {@link MavlinkTelemetrySource}) claims exactly that sysid, permanently, the first time a
 *       message from it arrives; it never re-elects.</li>
 *   <li><b>Unpinned</b> claims the first sysid heard on this socket that nothing else already
 *       claims. If its claimed sysid then falls silent for the configured silence window
 *       (production default 30s), it may re-elect to a different, still-unclaimed sysid — checked
 *       lazily, only when a message from some other still-unclaimed sysid next arrives (no timer,
 *       no thread beyond the session's own reader thread). See {@link VehicleClaimPolicy}.</li>
 *   <li><b>Unclaimed</b> — a sysid heard on this socket that no registration currently wants is
 *       recorded in a small bounded registry (see {@link #unclaimedVehicles()}) instead of
 *       silently dropped, for {@code MavlinkHeartbeatScanner}'s plug-and-fly discovery.</li>
 * </ul>
 *
 * <h2>Command TX seam</h2>
 * Unlike the pre-W4 hub (receive-only, with a narrow hand-rolled ack-waiter seam into its own read
 * loop), this gateway exposes its session's real collaborators directly: {@link #sink()} ({@link
 * FrameSink}, for sending), {@link #correlator()} ({@link Correlator}, for awaiting a reply) and
 * {@link #peers()} ({@link PeerDirectory}, for reachability checks) — see {@link #commandTarget}
 * for the adapter-facing summary a caller checks <i>before</i> ever touching those. {@code
 * MavlinkFlightCommander}/{@code MavlinkManualControlSender} build {@code mavlink-core}'s own
 * {@code CommandService}/{@code ManualControlService} directly from these rather than this class
 * hand-rolling a send-and-await seam of its own — the whole point of W4.
 *
 * <h2>Threading</h2>
 * {@link #register}/{@link #unregister}/{@link #unclaimedVehicles()}/{@link
 * #claimedVehicles()}/{@link #commandTarget(DeviceId)} are called from whatever thread calls
 * {@link MavlinkTelemetrySource#open}/{@code close}/the TX port classes; frame routing and claim/
 * re-election decisions happen only on the underlying session's one reader thread for this
 * gateway's single link. All mutable claim/registration state lives in {@link VehicleClaimPolicy},
 * guarded by its own monitor.
 *
 * <p>Not a domain/port type — package-private, owned entirely by {@link MavlinkTelemetrySource},
 * the only class that constructs, registers with, or queries one.
 */
final class MavlinkGateway {

    private final UdpListenLink link;
    private final MavlinkSession session;
    private final VehicleClaimPolicy claimPolicy;
    private final Subscription subscription;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Binds {@code bindHost:port} immediately (throws {@link IOException} on a bind conflict --
     * unlike the pre-W4 hub, which bound lazily on its own background thread and only ever
     * surfaced a bind failure asynchronously via {@code onError}; {@code mavlink-core}'s {@link
     * UdpListenLink} binds synchronously in its own constructor, so this gateway does too and lets
     * a bind failure propagate synchronously from {@link MavlinkTelemetrySource#open} instead --
     * see that class's own javadoc).
     *
     * @param settings supplies {@link MavlinkSettings#silenceWindow()} (unpinned re-election
     *                 window), {@link MavlinkSettings#maxUnclaimedVehicles()} (bounded unclaimed
     *                 registry cap), and {@link MavlinkSettings#closeJoinTimeout()} (how long
     *                 {@link #close()} awaits the session's reader thread)
     */
    MavlinkGateway(String bindHost, int port, MavlinkSettings settings) throws IOException {
        this.link = new UdpListenLink(bindHost, port);
        this.session = new MavlinkSession(MavlinkNode.groundStation(), coreSettings(settings));
        session.addLink(link);
        this.claimPolicy = new VehicleClaimPolicy(
                session.peers(), settings.silenceWindow().toMillis(), settings.maxUnclaimedVehicles());
        this.subscription = session.dispatcher().subscribe(MessageFilter.any(), this::onFrame);
    }

    /** Once closed (last registration released), never reused. */
    boolean isClosed() {
        return closed.get();
    }

    /**
     * Registers a device's interest in this gateway. {@code pinnedSysid} {@code null} means
     * unpinned (first-unclaimed-wins, re-electable); a non-null value pins the registration to
     * exactly that system id.
     */
    VehicleRegistration register(DeviceId deviceId, Integer pinnedSysid, SubmissionPublisher<Telemetry> publisher) {
        VehicleRegistration registration = new VehicleRegistration(deviceId, pinnedSysid, publisher);
        claimPolicy.add(registration);
        return registration;
    }

    /**
     * Releases a device's registration and its claim, if any, so another registration may pick it
     * up. Closes this gateway once this was the last registration.
     *
     * @return {@code true} once this gateway has no remaining registrations — the caller should
     *         evict it from {@code MavlinkTelemetrySource}'s gateway map so the next {@code open()}
     *         for this bind address builds a fresh one
     */
    boolean unregister(VehicleRegistration registration) {
        boolean empty = claimPolicy.remove(registration);
        if (empty) {
            close();
        }
        return empty;
    }

    /** Vehicles heard on this gateway's socket that no registration currently claims (docs/plans/active/DRONE-INFRA-PLAN.md I-b). */
    List<UnclaimedVehicle> unclaimedVehicles() {
        return claimPolicy.unclaimedVehicles();
    }

    /** Vehicles currently claimed by an open device on this gateway's socket (docs/plans/active/DRONE-INFRA-PLAN.md I-b). */
    List<ClaimedVehicle> claimedVehicles() {
        return claimPolicy.claimedVehicles();
    }

    /**
     * {@code deviceId}'s current command-TX coordinates on this gateway — its sysid, last-known
     * firmware/mavType, and last-seen UDP reply address — or {@code null} if it holds no claim
     * right now. A non-null result with a null {@link CommandTarget#sourceAddress()} never
     * happens: a claim always implies at least one message has been heard from it.
     */
    CommandTarget commandTarget(DeviceId deviceId) {
        return claimPolicy.commandTarget(deviceId);
    }

    /** This gateway's session {@link FrameSink}, for a TX port class to build a {@code mavlink-core} service on. */
    FrameSink sink() {
        return session.sink();
    }

    /** This gateway's session {@link Correlator}, for a TX port class to build a {@code mavlink-core} service on. */
    Correlator correlator() {
        return session.correlator();
    }

    /** This gateway's session {@link PeerDirectory}, for a TX port class to build a {@code mavlink-core} service on. */
    PeerDirectory peers() {
        return session.peers();
    }

    private void onFrame(MavFrame frame) {
        int sysid = frame.header().system().value();
        VehicleRegistration owner = claimPolicy.resolve(sysid);
        if (owner != null) {
            Telemetry sample = owner.decoder.accept(sysid, frame.payload());
            if (sample != null) {
                owner.publisher.submit(sample);
            }
        }
    }

    /**
     * Idempotent. Closes the link first (unblocks the session's reader thread's next poll, per
     * this module's close-the-socket-to-unblock-the-reader idiom) then the session (stops and
     * joins that thread, bounded by the settings' close-join timeout) — matching the pre-W4 hub's
     * own shutdown ordering.
     */
    private void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        subscription.close();
        link.close();
        session.close();
    }

    private static MavlinkCoreSettings coreSettings(MavlinkSettings settings) {
        return MavlinkCoreSettings.defaults().withCloseJoinTimeout(settings.closeJoinTimeout());
    }

    /** A sysid heard on this gateway's socket that no registration currently claims (docs/plans/active/DRONE-INFRA-PLAN.md I-b). */
    record UnclaimedVehicle(int sysid, String firmware, Integer mavType, Instant lastHeard) {
    }

    /** A sysid on this gateway's socket currently claimed by an open device (docs/plans/active/DRONE-INFRA-PLAN.md I-b). */
    record ClaimedVehicle(int sysid, DeviceId deviceId, String firmware, Integer mavType, Instant lastHeard) {
    }

    /**
     * A currently-claimed vehicle's command-TX coordinates (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1):
     * which sysid, its firmware/mavType (for RTL mode-number resolution — {@code null} until a
     * {@code HEARTBEAT} has actually arrived), and where to send a reply.
     */
    record CommandTarget(int sysid, String firmware, Integer mavType, InetSocketAddress sourceAddress) {
    }
}
