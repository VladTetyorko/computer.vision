package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.CompId;
import com.drones.mavlink.PeerId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.codec.FrameSink;
import com.drones.mavlink.service.CapabilityReport;
import com.drones.mavlink.service.CapabilityService;
import com.drones.mavlink.service.CommandService;
import com.drones.mavlink.service.MessageIntervalService;
import com.drones.mavlink.session.Correlator;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * The platform-side stream negotiation every real GCS performs the moment it connects to a vehicle
 * (docs/plans/active/MAVLINK-COMMANDS-PLAN.md D2c): on claim, ask what the aircraft is ({@code
 * MAV_CMD_REQUEST_MESSAGE(AUTOPILOT_VERSION)}, one-shot) and ask it to stream what this platform's
 * cockpit actually reads ({@code MAV_CMD_SET_MESSAGE_INTERVAL}, one command per message id). Before
 * this class existed nothing in this module ever sent either command — {@code
 * MavlinkFlightCommander#capabilities(Device)} reported "whatever {@code HEARTBEAT}/{@code
 * SYS_STATUS} happened to arrive" rather than an actual identity probe, and a vehicle that starts up
 * streaming almost nothing (the O4 Gotchas entry measured four message types on a fresh SITL) simply
 * stayed starved unless an operator flipped on {@link MavlinkConnectRemediator}'s opt-in mechanism.
 *
 * <h2>Claim, not connect — and why that is a different trigger than {@link MavlinkConnectRemediator}</h2>
 * {@link MavlinkGateway} invokes {@link #negotiate} from {@link VehicleClaimPolicy}'s {@code
 * onClaimed} hook, fired exactly once per claim event (an initial claim <b>and</b> a silence-window
 * re-election — see that class's constructor javadoc). {@link MavlinkConnectRemediator} fires on a
 * different, earlier signal: any newly-learned peer on the link, claimed or not. The two mechanisms
 * are deliberately independent, not layered:
 * <ul>
 *   <li>this class only ever spends a retry budget on a sysid this station's own {@link
 *       VehicleClaimPolicy} has actually claimed — never on, say, a second GCS instance's own
 *       traffic passing through the same shared hub port;</li>
 *   <li>this class also sends the {@code AUTOPILOT_VERSION} probe {@link MavlinkConnectRemediator}
 *       never did; that one only ever calls {@link MessageIntervalService};</li>
 *   <li>this class is unconditional — see below — where {@link MavlinkConnectRemediator} exists at
 *       all only when {@link MavlinkSettings.Onboarding#requestMessagesOnConnect()} is {@code true}.</li>
 * </ul>
 * A vehicle can therefore be negotiated with twice, by two independent mechanisms, if the operator
 * has also turned Mechanism A on — see below for why the two are not actually allowed to overlap on
 * the wire.
 *
 * <h2>Deliberately unconditional — no enable flag of its own</h2>
 * Unlike {@link MavlinkConnectRemediator}'s {@code requestMessagesOnConnect} guardrail,
 * {@link MavlinkSettings.StreamNegotiation} carries no flag of its own — see that record's own
 * javadoc for why O1-SYNTHESIS.md's D2c section treats "add a platform caller for {@code
 * REQUEST_MESSAGE}/{@code SET_MESSAGE_INTERVAL}" as baseline GCS behaviour this module was simply
 * missing, not an opt-in feature.
 *
 * <h2>Except: the interval chain steps aside for Mechanism A — a real wire-level constraint, not a policy choice</h2>
 * {@code COMMAND_ACK} for {@code MAV_CMD_SET_MESSAGE_INTERVAL} correlates purely on {@code (origin
 * sysid, command id)} — the spec gives it no way to echo back <i>which message id</i> a request asked
 * for (see {@link #negotiateStreams}'s own note). Two independently-triggered {@code
 * SET_MESSAGE_INTERVAL} chains to the <b>same</b> peer therefore cannot both hold a live correlator
 * await for that one shared key at once, no matter how carefully each chain serialises its own
 * requests internally — {@link MavlinkConnectRemediator#remediate} and {@link #negotiateStreams}
 * fire from two completely independent triggers (a learned peer vs. a claim) with no coordination
 * between them, so with both active the loser's registration attempt fails outright ({@code
 * DefaultCorrelator} throws rather than silently orphan the winner's waiter) — a real collision this
 * class's own test suite reproduced once P2 and an operator-enabled Mechanism A were exercised
 * together. Rather than coordinate two independent classes over a shared mutable resource neither one
 * owns, the constructor reads {@link MavlinkSettings.Onboarding#requestMessagesOnConnect()} once and
 * {@link #negotiateStreams} is skipped entirely when it is {@code true} — Mechanism A, once an
 * operator has explicitly turned it on, already owns this exact job (with its own operator-configured
 * message set), so P2's fixed six-message default would be pure redundancy on the very same wire
 * key even without the collision. The {@code AUTOPILOT_VERSION} probe is unaffected either way —
 * {@link MavlinkConnectRemediator} never sends it, and it correlates on an entirely different key
 * (see below) — so {@link #negotiate} always still fires it.
 *
 * <h2>Honest failure handling: negotiation can never fail a claim</h2>
 * {@link #negotiate} returns {@code void} and neither of its two exchanges is ever awaited
 * synchronously — every outcome, including a send-level fault, is only ever logged. An
 * {@code UNSUPPORTED}/{@code DENIED} ack for a {@code SET_MESSAGE_INTERVAL} request means exactly
 * what it says on this protocol: the vehicle does not implement stream negotiation (older firmware,
 * a minimal simulator) and will simply keep sending its own default set — logged once at INFO, not a
 * failure. {@code NO_ACK} after {@link MavlinkSettings#commandRetries()} attempts is logged at
 * WARNING (a live link that never answers at all is a more concerning signal than an explicit
 * refusal), but is equally non-fatal: the claim this negotiation followed has already happened by
 * the time any of these outcomes resolves, and nothing here can undo it.
 *
 * <h2>Concurrency: independent probe, sequential interval chain</h2>
 * The capability probe and the interval requests correlate on different {@link
 * com.drones.mavlink.session.MatchKey}s ({@code CorrelationKeys#forAutopilotVersion} vs. {@code
 * CorrelationKeys#forCommandAck} keyed on {@code MAV_CMD_SET_MESSAGE_INTERVAL}'s command id) and so
 * run fully concurrently with each other. The interval requests among themselves must not — see
 * above for why the ack correlates on command id alone. {@link #negotiateStreams} chains them via
 * {@link CompletableFuture#thenCompose}, sending request {@code n+1} only once request {@code n}'s
 * exchange has resolved — the exact pattern {@link MavlinkConnectRemediator#remediate} already
 * established; see that method's own javadoc for the fuller argument. Neither chain ever blocks the
 * caller: {@code thenCompose} on an incomplete future only registers a continuation.
 *
 * <h2>Threading</h2>
 * No internal locking and no per-sysid state, unlike {@link MavlinkConnectRemediator} (which tracks
 * "newly learned" across every frame and so needs both). This class needs neither: {@link
 * VehicleClaimPolicy} already guarantees {@link #negotiate} runs exactly once per claim event, always
 * from inside its own monitor on the session's single reader thread — safe to call from there
 * precisely because every send this method starts is fire-and-forget async, never a blocking {@code
 * .get()}/{@code .join()}.
 *
 * <h2>Known scope boundary: {@code capabilities()} is not wired to this probe's result</h2>
 * {@code MavlinkFlightCommander#capabilities(Device)} still reports only what {@code HEARTBEAT}/
 * {@code SYS_STATUS} happened to decode — this wave adds the probe call but does not yet cache or
 * expose its {@link CapabilityReport}. See this module's MODULE.md Gotchas for the reasoning.
 */
final class MavlinkStreamNegotiator {

    private static final System.Logger LOG = System.getLogger(MavlinkStreamNegotiator.class.getName());

    /** The autopilot component every ArduPilot/PX4 vehicle answers on — same convention the rest of this module uses. */
    private static final int TARGET_COMPONENT_AUTOPILOT = MavlinkFlightCommander.TARGET_COMPONENT_AUTOPILOT;

    private final CapabilityService capabilityService;
    private final MessageIntervalService messageIntervalService;
    private final List<MavlinkSettings.Onboarding.MessageRequest> streams;

    /**
     * {@code true} once an operator has turned Mechanism A on ({@link
     * MavlinkSettings.Onboarding#requestMessagesOnConnect()}) — see this class's own javadoc ("Except:
     * the interval chain steps aside for Mechanism A") for why {@link #negotiateStreams} must then be
     * skipped rather than left to race {@link MavlinkConnectRemediator} for the same correlator key.
     */
    private final boolean intervalChainOwnedByMechanismA;

    /**
     * @param sink       the gateway's session {@link FrameSink}, to build both underlying {@code
     *                   mavlink-core} services on
     * @param correlator the gateway's session {@link Correlator}, likewise
     * @param settings   supplies {@link MavlinkSettings#ackTimeout()}/{@link
     *                   MavlinkSettings#commandRetries()} for both exchanges — the same
     *                   retry-eligible-command budget {@code MavlinkFlightCommander} uses
     *                   (docs/plans/active/MAVLINK-COMMANDS-PLAN.md P1) rather than a negotiation-
     *                   specific budget of its own, {@link
     *                   MavlinkSettings.StreamNegotiation#streams()} for the message set to request,
     *                   and {@link MavlinkSettings.Onboarding#requestMessagesOnConnect()} to decide
     *                   whether {@link #negotiateStreams} must step aside for Mechanism A
     */
    MavlinkStreamNegotiator(FrameSink sink, Correlator correlator, MavlinkSettings settings) {
        Objects.requireNonNull(sink, "sink must not be null");
        Objects.requireNonNull(correlator, "correlator must not be null");
        Objects.requireNonNull(settings, "settings must not be null");
        this.capabilityService = new CapabilityService(sink, correlator, settings.ackTimeout(), settings.commandRetries());
        this.messageIntervalService = new MessageIntervalService(
                new CommandService(sink, correlator, settings.ackTimeout(), settings.commandRetries()));
        this.streams = settings.streamNegotiation().streams();
        this.intervalChainOwnedByMechanismA = settings.onboarding().requestMessagesOnConnect();
    }

    /**
     * Fires both exchanges for {@code sysid} — the capability probe always, the stream-interval chain
     * only when Mechanism A does not already own it (see this class's own javadoc) — and returns
     * immediately. Intended to be called exactly once per claim event; see this class's own javadoc
     * for why the caller ({@link VehicleClaimPolicy}) already guarantees that.
     */
    void negotiate(int sysid) {
        PeerId target = new PeerId(new SysId(sysid), new CompId(TARGET_COMPONENT_AUTOPILOT));
        probeCapabilities(sysid, target);
        if (!intervalChainOwnedByMechanismA) {
            negotiateStreams(sysid, target);
        }
    }

    private void probeCapabilities(int sysid, PeerId target) {
        capabilityService.requestAutopilotVersion(target)
                .handle((report, error) -> {
                    logCapabilityOutcome(sysid, report, error);
                    return null;
                });
    }

    private void negotiateStreams(int sysid, PeerId target) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (MavlinkSettings.Onboarding.MessageRequest request : streams) {
            chain = chain.thenCompose(ignored -> sendOne(sysid, target, request));
        }
    }

    private CompletableFuture<Void> sendOne(int sysid, PeerId target, MavlinkSettings.Onboarding.MessageRequest request) {
        return messageIntervalService.setMessageInterval(target, request.messageId(), request.interval())
                .handle((outcome, error) -> {
                    logIntervalOutcome(sysid, request, outcome, error);
                    return null;
                });
    }

    private static void logCapabilityOutcome(int sysid, CapabilityReport report, Throwable error) {
        if (error != null) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "on-claim AUTOPILOT_VERSION probe for sysid " + sysid + " could not be sent: " + error);
        } else if (report.status() == CapabilityReport.Status.NO_REPLY) {
            LOG.log(System.Logger.Level.INFO,
                    () -> "on-claim AUTOPILOT_VERSION probe for sysid " + sysid
                            + " went unanswered -- vehicle does not implement it");
        } else {
            LOG.log(System.Logger.Level.INFO,
                    () -> "on-claim AUTOPILOT_VERSION probe for sysid " + sysid
                            + " answered: firmware " + report.firmwareVersion() + " (" + report.maturity() + ")");
        }
    }

    private static void logIntervalOutcome(int sysid, MavlinkSettings.Onboarding.MessageRequest request,
                                            CommandService.CommandOutcome outcome, Throwable error) {
        if (error != null) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "on-claim MAV_CMD_SET_MESSAGE_INTERVAL for message " + request.messageId()
                            + " to sysid " + sysid + " could not be sent: " + error);
            return;
        }
        switch (outcome.status()) {
            case ACCEPTED -> LOG.log(System.Logger.Level.INFO,
                    () -> "on-claim MAV_CMD_SET_MESSAGE_INTERVAL accepted for message " + request.messageId()
                            + " at " + request.interval() + " on sysid " + sysid);
            case UNSUPPORTED, DENIED -> LOG.log(System.Logger.Level.INFO,
                    () -> "on-claim MAV_CMD_SET_MESSAGE_INTERVAL for message " + request.messageId()
                            + " to sysid " + sysid + " was " + outcome.status()
                            + " -- vehicle does not support stream negotiation, keeping its own default rate");
            default -> LOG.log(System.Logger.Level.WARNING,
                    () -> "on-claim MAV_CMD_SET_MESSAGE_INTERVAL for message " + request.messageId()
                            + " to sysid " + sysid + " was " + outcome.status());
        }
    }
}
