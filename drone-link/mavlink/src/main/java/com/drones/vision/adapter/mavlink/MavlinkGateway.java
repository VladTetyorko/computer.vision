package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.CompId;
import com.drones.mavlink.PeerId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.codec.FrameSink;
import com.drones.mavlink.codec.MavFrame;
import com.drones.mavlink.config.MavlinkCoreSettings;
import com.drones.mavlink.service.HeartbeatService;
import com.drones.mavlink.session.Correlator;
import com.drones.mavlink.session.DefaultTxScheduler;
import com.drones.mavlink.session.LinkHealth;
import com.drones.mavlink.session.MavlinkNode;
import com.drones.mavlink.session.MavlinkSession;
import com.drones.mavlink.session.MessageFilter;
import com.drones.mavlink.session.PeerDirectory;
import com.drones.mavlink.session.Subscription;
import com.drones.mavlink.transport.MavlinkLink;
import com.drones.mavlink.transport.UdpListenLink;

import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.Telemetry;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
 *       from the pre-W4 design;</li>
 *   <li>a {@link MavlinkMessageInventory} (docs/plans/active/DRONE-ONBOARDING-PLAN.md O1) — a
 *       second, independent dispatcher subscription that passively counts every message type heard
 *       from every peer, claimed or not; see {@link #messageInventory()}.</li>
 *   <li>optionally, a {@link MavlinkConnectRemediator} (docs/plans/active/DRONE-ONBOARDING-PLAN.md
 *       O8) — a third, independent dispatcher subscription, constructed only when {@link
 *       MavlinkSettings.Onboarding#requestMessagesOnConnect()} is {@code true}, that fires
 *       Mechanism A ({@code MAV_CMD_SET_MESSAGE_INTERVAL}) the instant a peer is learned.</li>
 *   <li>a {@link MavlinkStreamNegotiator} (docs/plans/active/MAVLINK-COMMANDS-PLAN.md P2) —
 *       unconditional, unlike {@link MavlinkConnectRemediator} — wired into {@link
 *       VehicleClaimPolicy}'s {@code onClaimed} hook so a claim (not merely a learned peer) fires
 *       {@code MAV_CMD_REQUEST_MESSAGE(AUTOPILOT_VERSION)} and the configured {@code
 *       MAV_CMD_SET_MESSAGE_INTERVAL} set; see that class's own javadoc for how it differs from
 *       Mechanism A above.</li>
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
 * <h2>Standing lobby hold (claim-free) — docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §11 Z2b</h2>
 * {@link #holdLobby()}/{@link #releaseLobby()} let {@link MavlinkTelemetrySource} keep this gateway
 * bound with <b>zero</b> device registrations, for zero-config discovery: nothing here is ever
 * added to {@link VehicleClaimPolicy}'s own registrations, so a lobby hold can never claim a sysid
 * an unpinned real device would otherwise get — every heard-but-unclaimed sysid still lands in
 * {@link #unclaimedVehicles()} exactly as it always has. Its only two effects are (1) suppressing
 * {@link #unregister}'s self-close while zero devices are registered, and (2) transmitting a GCS
 * {@code HEARTBEAT} (via a {@link HeartbeatService} this hold owns outright) so a PX4-convention
 * vehicle broadcasting to this port locks unicast onto us the moment it is heard — see {@link
 * #holdLobby()}'s own javadoc for the mechanism and the known "vision never initiates" line this
 * flips.
 *
 * <h2>Threading</h2>
 * {@link #register}/{@link #unregister}/{@link #unclaimedVehicles()}/{@link
 * #claimedVehicles()}/{@link #commandTarget(DeviceId)}/{@link #holdLobby()}/{@link #releaseLobby()}
 * are called from whatever thread calls {@link MavlinkTelemetrySource#open}/{@code close}/{@code
 * holdLobby}/{@code releaseLobby}/the TX port classes; frame routing and claim/re-election
 * decisions happen only on the underlying session's one reader thread for this gateway's single
 * link. All mutable claim/registration state lives in {@link VehicleClaimPolicy}, guarded by its
 * own monitor; the lobby hold's own state is guarded independently (see {@link #holdLobby()}).
 *
 * <p>Not a domain/port type — package-private, owned entirely by {@link MavlinkTelemetrySource},
 * the only class that constructs, registers with, or queries one.
 */
final class MavlinkGateway {

    private static final System.Logger LOG = System.getLogger(MavlinkGateway.class.getName());

    private final MavlinkLink link;
    private final MavlinkCoreSettings coreSettings;
    private final MavlinkSession session;
    private final VehicleClaimPolicy claimPolicy;
    private final Subscription subscription;
    private final MavlinkMessageInventory messageInventory;
    private final MavlinkConnectRemediator connectRemediator;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean lobbyHeld = new AtomicBoolean(false);
    private final AtomicReference<LobbyHeartbeat> lobbyHeartbeat = new AtomicReference<>();

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
        this(new UdpListenLink(bindHost, port), settings);
    }

    /**
     * Test-only seam (java-clean-code skill §3's "inject a clock or a backoff bound" allowance):
     * builds this gateway around an already-constructed {@link MavlinkLink} instead of binding a
     * fresh {@link UdpListenLink}, so a test can simulate a genuine link failure (FLEET-RADIO-PLAN.md
     * F7/R4) with a hand-built {@code MavlinkLink} double rather than sabotaging a real socket via
     * reflection. The production constructor above delegates here rather than duplicating this
     * body, so the two can never drift apart.
     */
    MavlinkGateway(MavlinkLink link, MavlinkSettings settings) {
        this.link = link;
        this.coreSettings = coreSettings(settings);
        this.session = new MavlinkSession(MavlinkNode.groundStation(), coreSettings);
        session.addLink(link);
        // FLEET-RADIO-PLAN.md R4/F7: mavlink-core used to swallow a genuine read failure into a
        // silent reader-thread exit. Wired before any registration exists, so a failure occurring
        // the instant after bind still reaches every registration this gateway ever accumulates.
        session.onLinkFailure((linkId, cause) -> handleLinkFailure(cause));
        // MAVLINK-COMMANDS-PLAN.md P2: built before claimPolicy so its onClaimed hook can be wired
        // in below. Unconditional (no settings.onboarding()-style flag) -- see its own javadoc.
        MavlinkStreamNegotiator streamNegotiator = new MavlinkStreamNegotiator(session.sink(), session.correlator(), settings);
        this.claimPolicy = new VehicleClaimPolicy(
                session.peers(), settings.silenceWindow().toMillis(), settings.maxUnclaimedVehicles(),
                streamNegotiator::negotiate);
        this.subscription = session.dispatcher().subscribe(MessageFilter.any(), this::onFrame);
        // A second, independent subscription (docs/plans/active/DRONE-ONBOARDING-PLAN.md O1,
        // §3.2's "passive inventory") -- deliberately not folded into onFrame's routing/decode
        // subscription above, so a bug in one can never affect the other, and so the inventory
        // keeps counting sysids nobody has claimed (see MavlinkMessageInventory's own javadoc).
        this.messageInventory = new MavlinkMessageInventory(session.dispatcher(), settings.inventory());
        // Wave O8's Mechanism A: constructed -- and its own third subscription registered -- only
        // when the flag is on. With it off, this field stays null and no subscription exists at
        // all, so "flag off" is structurally "cannot send a command," not merely "chose not to."
        this.connectRemediator = settings.onboarding().requestMessagesOnConnect()
                ? new MavlinkConnectRemediator(session.dispatcher(), session.sink(), session.correlator(), settings)
                : null;
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
     * up. Closes this gateway once this call leaves it with no remaining registrations <b>and</b>
     * no standing lobby hold (see {@link #holdLobby()}) — a lobby hold keeps a gateway with zero
     * devices alive exactly as if a device were still registered against it.
     *
     * @return {@code true} once this call has actually closed the gateway — the caller should
     *         evict it from {@code MavlinkTelemetrySource}'s gateway map so the next {@code open()}/
     *         {@code holdLobby()} for this bind address builds a fresh one
     */
    boolean unregister(VehicleRegistration registration) {
        boolean empty = claimPolicy.remove(registration);
        if (empty && !lobbyHeld.get()) {
            close();
            return true;
        }
        return false;
    }

    /**
     * Marks this gateway as held by the zero-config standing lobby (docs/plans/active/
     * ZERO-CONFIG-ONBOARDING-CONTEXT.md §11 Z2b) — a <b>claim-free</b> hold that only prevents
     * {@link #unregister}'s self-close from firing while this gateway has zero device
     * registrations. It is never added to {@link VehicleClaimPolicy}'s own {@code registrations},
     * so it can never win a sysid a real, unpinned device would otherwise claim — see {@code
     * MavlinkTelemetrySource#holdLobby(int)} for the caller-facing contract this implements.
     * Idempotent: a call while already held starts nothing a second time.
     *
     * <p>On the transition into "held," starts a GCS {@code HEARTBEAT} broadcast on a fresh {@link
     * DefaultTxScheduler}/{@link HeartbeatService} pair that this hold owns outright — created here,
     * torn down by whichever of {@link #releaseLobby()}/{@link #close()} happens first (see {@link
     * #stopLobbyHeartbeat()}). This is the "vision never initiates MAVLink traffic" line the
     * zero-config plan flips: a vehicle broadcasting to this port per the PX4 broadcast-until-heard
     * convention locks unicast onto the first GCS heartbeat it hears, closing the handshake without
     * an operator ever touching a form. Identity is already GCS 255/190 through this gateway's own
     * {@link #session} (see the constructor) — {@link HeartbeatService} only supplies the message
     * <i>content</i>, defaulted to {@link com.drones.mavlink.service.HeartbeatContent#groundStation()}.
     * Accepted caveat (documented, not fixed here): {@link HeartbeatService} replies on every link
     * it has heard <i>any</i> peer on, so several vehicles announcing simultaneously on the same
     * link take turns rather than all locking on at once — every vehicle transmitting at least once
     * a second still gets its own lock-on within a few seconds.
     */
    void holdLobby() {
        if (!lobbyHeld.compareAndSet(false, true)) {
            return;
        }
        DefaultTxScheduler scheduler = new DefaultTxScheduler(coreSettings.closeJoinTimeout());
        HeartbeatService heartbeatService = new HeartbeatService(session.sink(), scheduler, session.peers(), coreSettings);
        heartbeatService.start();
        lobbyHeartbeat.set(new LobbyHeartbeat(scheduler, heartbeatService));
        if (closed.get()) {
            // Closed concurrently -- e.g. a link failure racing this call on the session's own
            // reader thread (handleLinkFailure) -- between our CAS above and here: close() already
            // ran and, by its own idempotent-CAS guard, will never run again to stop what we just
            // started. Clean up ourselves so a hold racing a link failure can never leak a scheduler
            // thread.
            stopLobbyHeartbeat();
        }
        LOG.log(Level.INFO, () -> "MAVLink lobby hold acquired on " + link.id() + "; GCS heartbeat TX started");
    }

    /**
     * Releases a hold acquired by {@link #holdLobby()}. Idempotent: releasing while not held does
     * nothing. Always stops the GCS heartbeat TX the hold started (a released lobby must never keep
     * transmitting); closes this gateway too, exactly like {@link #unregister} would, if it now has
     * neither a hold nor any device registration left.
     */
    void releaseLobby() {
        if (!lobbyHeld.compareAndSet(true, false)) {
            return;
        }
        stopLobbyHeartbeat();
        LOG.log(Level.INFO, () -> "MAVLink lobby hold released on " + link.id());
        if (claimPolicy.isEmpty()) {
            close();
        }
    }

    /** {@code true} while a hold from {@link #holdLobby()} is in effect. */
    boolean isLobbyHeld() {
        return lobbyHeld.get();
    }

    private void stopLobbyHeartbeat() {
        LobbyHeartbeat heartbeat = lobbyHeartbeat.getAndSet(null);
        if (heartbeat != null) {
            heartbeat.close();
        }
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
     * {@link LinkHealth.Health} for every currently-claimed vehicle on this gateway's socket, keyed
     * by {@link DeviceId} (FLEET-RADIO-PLAN.md D4) — {@code mavlink-link}'s {@code
     * SubsystemStatusPort} plumbing (docs/plans/active/SYSTEM-STATUS-PLAN.md §4.2, this session's
     * {@link MavlinkSession#health()} first production caller; every prior call site was test-only).
     * Same {@code (sysid, TARGET_COMPONENT_AUTOPILOT)} identity {@link #commandTarget} and {@link
     * VehicleClaimPolicy#commandTarget} already use.
     *
     * <p>Keyed rather than a bare {@code List} so a rollup across several gateways (see {@link
     * MavlinkTelemetrySource#claimedVehicleHealth()}) can name <i>which</i> device a bad reading
     * belongs to — averaging a fleet's drop rates into one number, as the pre-D4 {@code
     * MavlinkLinkStatusProvider} did, destroys exactly the fact an operator needs to fix one radio.
     */
    Map<DeviceId, LinkHealth.Health> claimedVehicleHealth() {
        LinkHealth health = session.health();
        Map<DeviceId, LinkHealth.Health> result = new HashMap<>();
        for (ClaimedVehicle vehicle : claimedVehicles()) {
            PeerId peerId = new PeerId(new SysId(vehicle.sysid()), new CompId(MavlinkFlightCommander.TARGET_COMPONENT_AUTOPILOT));
            result.put(vehicle.deviceId(), health.of(peerId));
        }
        return result;
    }

    /**
     * Invoked (via {@link MavlinkSession#onLinkFailure}) on the dying reader thread the instant this
     * gateway's socket suffers a genuine I/O failure (FLEET-RADIO-PLAN.md F7/D5) — closes every
     * registered device's publisher exceptionally with {@code cause} so downstream sees a real
     * failure rather than a quiet end-of-stream, then tears this gateway down exactly like {@link
     * #unregister} would once its last registration left, so {@code MavlinkTelemetrySource}'s {@code
     * closeRuntime}/{@code open} paths still observe a consistently-closed gateway ({@link
     * #isClosed()}) rather than one merely abandoned.
     *
     * <p>Must not block or throw back into the caller — the listener contract {@link
     * MavlinkSession#onLinkFailure}'s javadoc documents. {@link
     * VehicleClaimPolicy#closeAllPublishersExceptionally} and {@link #close()} are both fast,
     * non-blocking, in-memory operations (no network I/O, no join of the very thread calling this),
     * so running them inline here honors that contract without needing a hand-off to another thread.
     */
    private void handleLinkFailure(IOException cause) {
        LOG.log(Level.WARNING, "MAVLink link failed for gateway on link " + link.id() + "; closing its publishers", cause);
        claimPolicy.closeAllPublishersExceptionally(cause);
        close();
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

    /**
     * This gateway's passive per-sysid message inventory (docs/plans/active/DRONE-ONBOARDING-PLAN.md
     * O1) — every message type heard on this socket, claimed or not, with a rolling count/Hz and a
     * bytes/s estimate per peer. Never {@code null}: created alongside {@link #session} in the
     * constructor and lives for this gateway's whole lifetime.
     */
    MavlinkMessageInventory messageInventory() {
        return messageInventory;
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
     *
     * <p>Package-private rather than private for exactly one caller besides {@link #unregister}:
     * {@code MavlinkVehicleConfigurator} opens its own registration-less gateway when it must probe
     * an address no device is streaming from yet, and closes that one itself. The invariant this
     * relaxes is only "gateways die when their last registration goes"; the invariant that matters —
     * <b>never close a gateway you did not open</b> — is enforced by that class's lease, because a
     * borrowed gateway backs a live device's telemetry.
     */
    void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stopLobbyHeartbeat();
        subscription.close();
        messageInventory.close();
        if (connectRemediator != null) {
            connectRemediator.close();
        }
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

    /**
     * The lobby hold's own TX resources (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §11
     * Z2b) — created by {@link #holdLobby()}, torn down together by {@link #stopLobbyHeartbeat()}.
     * A private, gateway-owned pairing, not a reusable type: nothing outside this class ever sees
     * one.
     */
    private record LobbyHeartbeat(DefaultTxScheduler scheduler, HeartbeatService service) {
        void close() {
            service.stop();
            scheduler.close();
        }
    }
}
