package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.CompId;
import com.drones.mavlink.PeerId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.codec.FrameSink;
import com.drones.mavlink.codec.MavFrame;
import com.drones.mavlink.service.CommandService;
import com.drones.mavlink.service.MessageIntervalService;
import com.drones.mavlink.session.Correlator;
import com.drones.mavlink.session.Dispatcher;
import com.drones.mavlink.session.MessageFilter;
import com.drones.mavlink.session.Subscription;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Mechanism A, fired automatically the instant a gateway learns a peer (docs/plans/active/
 * DRONE-ONBOARDING-PLAN.md wave O8, §4a/§3.2): request the configured message set via
 * {@code MAV_CMD_SET_MESSAGE_INTERVAL} so a link that streams almost nothing — the O4 Gotchas entry
 * measured a fresh SITL at four message types over a UDP {@code --out} channel — starts streaming
 * what the platform needs, with no operator action. Per {@link MessageIntervalService}'s own
 * contract this is a runtime request, not a write: nothing persists, nothing survives a reboot,
 * nothing can be left in a bad state.
 *
 * <h2>Structurally off by default</h2>
 * {@link MavlinkGateway} constructs one of these only when {@link
 * MavlinkSettings.Onboarding#requestMessagesOnConnect()} is {@code true}. With the flag {@code
 * false}, no instance is ever created, so no {@link Dispatcher} subscription exists and no command
 * can be sent — not "chose not to," but "cannot." The plan calls this its one deliberate exception to
 * "nothing automatic from discovery" (§6.2 rule 3), so the exception is scoped as tightly as the flag
 * itself can make it: this class, and nothing else in this module, ever sends a command that no
 * operator asked for.
 *
 * <h2>Idempotent per peer, and what "reconnect" means here</h2>
 * A newly observed system id receives the configured message set exactly once. What happens when
 * that system id then falls silent and later reappears is a genuine judgement call, not an
 * oversight: {@code MAV_CMD_SET_MESSAGE_INTERVAL} is session-scoped on the wire, but ArduPilot keeps
 * honouring it for as long as the autopilot itself stays powered — only a reboot forgets it. MAVLink
 * gives an observer no way to tell "the same aircraft, still configured, radio blipped" apart from
 * "a fresh boot, back to starved defaults": there is no boot counter and no session identifier on the
 * wire. This class resolves that ambiguity toward the safe side: a system id is treated as newly
 * learned again once it has gone unheard for longer than {@link MavlinkSettings#silenceWindow()} —
 * the same threshold {@link VehicleClaimPolicy} already uses to decide a vehicle is gone, so this
 * needs no configuration of its own. Re-requesting an aircraft that never rebooted costs nothing
 * (ArduPilot simply re-confirms the rate it already honours); under-requesting one that did reboot
 * would silently leave the platform back in the exact starved state this mechanism exists to close.
 *
 * <h2>One request in flight per peer, never a burst</h2>
 * A naive loop that fires every configured request at once would be wrong, not merely inefficient:
 * {@code COMMAND_ACK} correlates purely on {@code (origin sysid, command id)} — it never echoes
 * back which message id a {@code MAV_CMD_SET_MESSAGE_INTERVAL} asked for — so two in-flight
 * requests to the same peer collide on one {@code Correlator} key, and a second {@code await} for a
 * key that already has a live waiter is rejected outright ({@code DefaultCorrelator} throws rather
 * than silently orphan the first). {@link #remediate} therefore chains the message set via {@link
 * CompletableFuture#thenCompose}: request {@code n+1} is sent only once request {@code n}'s exchange
 * has resolved (acked or timed out) — still without blocking the calling thread, because {@code
 * thenCompose} on an incomplete future only registers a continuation and returns.
 *
 * <p>Not thread-safe on its own — guarded by one private monitor, the same "one lock protects
 * mutable claim-adjacent state" convention {@link MavlinkMessageInventory}/{@link
 * MavlinkGateway}/{@link VehicleClaimPolicy} already follow. All mutation happens on the session's
 * one reader thread, matching {@link MavlinkGateway#onFrame}'s own directness — {@link
 * #remediate}'s chain is only ever *started* there; later links in the chain run on whichever
 * thread completes the previous stage (a {@code Correlator} waiter's own timeout scheduler, or a
 * later RX frame — see {@link CommandService}'s own javadoc), never blocking that first call. Every
 * outcome is only ever logged, never awaited — nothing here has an operator waiting on a result the
 * way {@code MavlinkVehicleConfigurator#requestMessageInterval} does.
 */
final class MavlinkConnectRemediator {

    private static final System.Logger LOG = System.getLogger(MavlinkConnectRemediator.class.getName());

    /** The autopilot component every ArduPilot/PX4 vehicle answers on — same convention the rest of this module uses. */
    private static final int TARGET_COMPONENT_AUTOPILOT = MavlinkFlightCommander.TARGET_COMPONENT_AUTOPILOT;

    /**
     * Bounds memory the same way {@link MavlinkMessageInventory}/{@link VehicleClaimPolicy} already
     * do: an unbounded number of distinct system ids scanning or flooding this link must not grow
     * this map without bound. Least-recently-seen is evicted first, exactly like those two classes.
     */
    private static final int MAX_TRACKED_SYSIDS = 128;

    private final Object lock = new Object();
    private final MessageIntervalService messageIntervalService;
    private final List<MavlinkSettings.Onboarding.MessageRequest> requests;
    private final long silenceWindowMillis;
    private final Map<Integer, Instant> lastSeen;
    private final Subscription subscription;

    /**
     * @param dispatcher  the gateway's session dispatcher — this class registers its own independent
     *                    {@link MessageFilter#any()} subscription, deliberately separate from {@link
     *                    MavlinkGateway#onFrame}'s routing subscription and {@link
     *                    MavlinkMessageInventory}'s counting one, so a bug in any one of the three can
     *                    never affect the other two
     * @param sink        the gateway's session {@link FrameSink}, to build a {@link CommandService} on
     * @param correlator  the gateway's session {@link Correlator}, to build a {@link CommandService} on
     * @param settings    supplies {@link MavlinkSettings#ackTimeout()}/the onboarding capability-retry
     *                    budget for the underlying {@link CommandService} (the same budget {@code
     *                    MavlinkVehicleConfigurator#requestMessageInterval} already uses for the same
     *                    kind of exchange), {@link MavlinkSettings#silenceWindow()} for the
     *                    newly-learned-again threshold, and {@link
     *                    MavlinkSettings.Onboarding#onConnectMessageRequests()} for what to ask for
     */
    MavlinkConnectRemediator(Dispatcher dispatcher, FrameSink sink, Correlator correlator, MavlinkSettings settings) {
        Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        Objects.requireNonNull(sink, "sink must not be null");
        Objects.requireNonNull(correlator, "correlator must not be null");
        Objects.requireNonNull(settings, "settings must not be null");
        this.messageIntervalService = new MessageIntervalService(
                new CommandService(sink, correlator, settings.ackTimeout(), settings.onboarding().capabilityRetries()));
        this.requests = settings.onboarding().onConnectMessageRequests();
        this.silenceWindowMillis = settings.silenceWindow().toMillis();
        this.lastSeen = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Instant> eldest) {
                return size() > MAX_TRACKED_SYSIDS;
            }
        };
        this.subscription = dispatcher.subscribe(MessageFilter.any(), this::onFrame);
    }

    /** Idempotent via {@link Subscription#close()}'s own contract. */
    void close() {
        subscription.close();
    }

    private void onFrame(MavFrame frame) {
        int sysid = frame.header().system().value();
        Instant now = frame.receivedAt();
        boolean newlyLearned;
        synchronized (lock) {
            Instant previous = lastSeen.get(sysid);
            newlyLearned = previous == null
                    || Duration.between(previous, now).toMillis() > silenceWindowMillis;
            lastSeen.put(sysid, now);
        }
        if (newlyLearned) {
            remediate(sysid);
        }
    }

    /**
     * Sends this peer's whole message set, one request at a time -- <b>never</b> the obvious
     * fire-and-forget loop. Every {@code MAV_CMD_SET_MESSAGE_INTERVAL} request awaits {@code
     * COMMAND_ACK} on the exact same {@link com.drones.mavlink.session.MatchKey}: {@link
     * com.drones.mavlink.session.CorrelationKeys#forCommandAck} keys purely on {@code (origin
     * sysid, command id)} -- {@code COMMAND_ACK} does not echo back which message id the command
     * asked for, so two in-flight requests to the same peer for the same command are
     * indistinguishable to {@link Correlator}. Registering a second {@code await} for a key that
     * already has a live waiter is not silently queued -- {@code DefaultCorrelator} throws {@link
     * IllegalStateException} rather than orphan the first waiter forever. Chaining via {@link
     * CompletableFuture#thenCompose} sends request {@code n+1} only once request {@code n}'s
     * exchange has resolved (acked or timed out), which serialises the sends without blocking this
     * method's caller -- {@link Dispatcher}'s "MUST NOT block" contract is about the calling thread,
     * not about how long the whole exchange eventually takes to settle.
     */
    private void remediate(int sysid) {
        PeerId target = new PeerId(new SysId(sysid), new CompId(TARGET_COMPONENT_AUTOPILOT));
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (MavlinkSettings.Onboarding.MessageRequest request : requests) {
            chain = chain.thenCompose(ignored -> sendOne(sysid, target, request));
        }
    }

    private CompletableFuture<Void> sendOne(int sysid, PeerId target,
                                             MavlinkSettings.Onboarding.MessageRequest request) {
        return messageIntervalService.setMessageInterval(target, request.messageId(), request.interval())
                .handle((outcome, error) -> {
                    logOutcome(sysid, request, outcome, error);
                    return null;
                });
    }

    private static void logOutcome(int sysid, MavlinkSettings.Onboarding.MessageRequest request,
                                    CommandService.CommandOutcome outcome, Throwable error) {
        if (error != null) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "on-connect MAV_CMD_SET_MESSAGE_INTERVAL for message " + request.messageId()
                            + " to sysid " + sysid + " could not be sent: " + error);
        } else if (outcome.status() != CommandService.CommandOutcome.Status.ACCEPTED) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "on-connect MAV_CMD_SET_MESSAGE_INTERVAL for message " + request.messageId()
                            + " to sysid " + sysid + " was not accepted: " + outcome.status());
        } else {
            LOG.log(System.Logger.Level.INFO,
                    () -> "on-connect MAV_CMD_SET_MESSAGE_INTERVAL accepted for message " + request.messageId()
                            + " at " + request.interval() + " on sysid " + sysid);
        }
    }
}
