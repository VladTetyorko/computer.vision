package com.drones.mavlink.service;

import com.drones.mavlink.PeerId;
import com.drones.mavlink.codec.FrameSink;
import com.drones.mavlink.codec.MavFrame;
import com.drones.mavlink.config.MavlinkCoreSettings;
import com.drones.mavlink.session.Correlator;
import com.drones.mavlink.session.MatchKey;

import io.dronefleet.mavlink.common.CommandAck;
import io.dronefleet.mavlink.common.CommandInt;
import io.dronefleet.mavlink.common.CommandLong;
import io.dronefleet.mavlink.common.MavCmd;
import io.dronefleet.mavlink.common.MavResult;
import io.dronefleet.mavlink.util.EnumValue;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MAVLink's command microservice (plan §2.2): {@code COMMAND_LONG} and {@code COMMAND_INT}, awaited
 * as {@code COMMAND_ACK} matched on {@code (origin sysid, command id)} only. Built on
 * {@link RequestResponse} — the retry/extension mechanics live there; this class only supplies
 * Command's own policy: how to build the two payload shapes, how a {@code COMMAND_ACK} maps to a
 * verdict, and how a {@code MAV_RESULT} maps to an outcome.
 *
 * <h2>{@code confirmation} vs. {@code IN_PROGRESS}</h2>
 * A silent retry (no reply of any kind within {@link #timeout}) resends with {@code confirmation}
 * incremented — the vehicle's own way of telling a retransmit from a fresh command (plan §2.2). A
 * reply of {@code MAV_RESULT_IN_PROGRESS} is not silence: it is data, so it never triggers a resend —
 * it only extends how long we keep waiting for a truly terminal result, capped at
 * {@value #MAX_IN_PROGRESS_EXTENSIONS} extensions so a vehicle that reports progress forever cannot
 * wedge a caller's future indefinitely. If that cap is reached, the exchange still completes — with
 * {@link CommandOutcome.Status#IN_PROGRESS} rather than silently hanging or reporting a bare timeout.
 *
 * <h2>{@code COMMAND_INT} has no {@code confirmation} field</h2>
 * The spec gives {@code COMMAND_INT} no retry-counter field at all — {@link #sendInt}'s payload
 * builder ignores the attempt number it is handed (accepted, not a bug: see that method's own note).
 */
public final class CommandService {

    /** {@code COMMAND_ACK}'s wire message id (MAVLink common.xml) — mirrors {@code DefaultCorrelator}'s own. */
    public static final int COMMAND_ACK_MESSAGE_ID = 77;

    /**
     * Bounds how many times a single exchange re-extends its deadline in response to
     * {@code MAV_RESULT_IN_PROGRESS} before giving up and reporting {@link CommandOutcome.Status#IN_PROGRESS}
     * as terminal. Not a spec number — the spec places no bound on how many progress updates a
     * long-running command may send — and not exposed as configuration: a caller that genuinely
     * needs a different budget builds its own {@link RequestResponse} exchange directly.
     */
    private static final int MAX_IN_PROGRESS_EXTENSIONS = 20;

    private final RequestResponse requestResponse;
    private final Duration timeout;
    private final int retries;

    public CommandService(FrameSink sink, Correlator correlator, Duration timeout, int retries) {
        this.requestResponse = new RequestResponse(correlator, sink);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive, got " + timeout);
        }
        this.timeout = timeout;
        if (retries < 0) {
            throw new IllegalArgumentException("retries must be >= 0, got " + retries);
        }
        this.retries = retries;
    }

    public CommandService(FrameSink sink, Correlator correlator, MavlinkCoreSettings settings) {
        this(sink, correlator, Objects.requireNonNull(settings, "settings").commandTimeout(), settings.commandRetries());
    }

    /**
     * Sends a {@code COMMAND_LONG} to {@code target} and awaits its {@code COMMAND_ACK}. Never
     * throws for a protocol-level outcome (denial, timeout, ...) — those are values in the returned
     * {@link CommandOutcome}; the returned future fails only for a send-level fault (e.g. the target
     * has never been heard from — see {@code RoutingFrameSink}).
     */
    public CompletableFuture<CommandOutcome> sendLong(PeerId target, MavCmd command,
                                                        float param1, float param2, float param3, float param4,
                                                        float param5, float param6, float param7) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(command, "command");
        RequestResponse.AttemptPayload payload = attempt -> CommandLong.builder()
                .targetSystem(target.system().value())
                .targetComponent(target.component().value())
                .command(command)
                .confirmation(attempt)
                .param1(param1).param2(param2).param3(param3).param4(param4)
                .param5(param5).param6(param6).param7(param7)
                .build();
        return exchange(target, command, payload);
    }

    /**
     * Sends a {@code COMMAND_INT} to {@code target} and awaits its {@code COMMAND_ACK} — the
     * spec-preferred shape for anything positional (integer lat/lon, an explicit coordinate frame;
     * plan D7). {@code frame} is {@code io.dronefleet.mavlink.common.MavFrame}, the wire coordinate-
     * frame enum — spelled out fully qualified here because this module's own {@link MavFrame}
     * (an envelope: header + payload + link) shares the same simple name.
     *
     * <p>{@code COMMAND_INT} carries no {@code confirmation} field (the spec's own field list for
     * this message has none), so unlike {@link #sendLong} a retry resends byte-identical params —
     * there is nothing else for the vehicle to key a retry-vs-fresh distinction on for this message.
     */
    public CompletableFuture<CommandOutcome> sendInt(PeerId target, io.dronefleet.mavlink.common.MavFrame frame,
                                                       MavCmd command, float param1, float param2, float param3,
                                                       float param4, int x, int y, float z) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(command, "command");
        RequestResponse.AttemptPayload payload = attempt -> CommandInt.builder()
                .targetSystem(target.system().value())
                .targetComponent(target.component().value())
                .frame(frame)
                .command(command)
                .current(0)
                .autocontinue(0)
                .param1(param1).param2(param2).param3(param3).param4(param4)
                .x(x).y(y).z(z)
                .build();
        return exchange(target, command, payload);
    }

    private CompletableFuture<CommandOutcome> exchange(PeerId target, MavCmd command, RequestResponse.AttemptPayload payload) {
        int commandId = EnumValue.of(command).value();
        MatchKey key = new MatchKey(target.system(), COMMAND_ACK_MESSAGE_ID, commandId);
        AtomicBoolean sawInProgress = new AtomicBoolean(false);
        AtomicInteger extensionsUsed = new AtomicInteger();
        RequestResponse.ReplyClassifier classifier = reply -> classify(reply, sawInProgress, extensionsUsed);
        return requestResponse.exchange(target, key, timeout, retries, payload, classifier)
                .thenApply(CommandService::terminalOutcome)
                .exceptionallyCompose(error -> recoverFromTimeout(error, sawInProgress));
    }

    /** {@code null} = terminal; a positive {@link Duration} = keep waiting, no resend. */
    private Duration classify(MavFrame reply, AtomicBoolean sawInProgress, AtomicInteger extensionsUsed) {
        CommandAck ack = reply.as(CommandAck.class);
        MavResult result = ack.result().entry();
        if (result != MavResult.MAV_RESULT_IN_PROGRESS) {
            return null;
        }
        sawInProgress.set(true);
        if (extensionsUsed.incrementAndGet() > MAX_IN_PROGRESS_EXTENSIONS) {
            return null; // give up extending -- report this same (still IN_PROGRESS) reply as terminal
        }
        return timeout;
    }

    private static CommandOutcome terminalOutcome(MavFrame reply) {
        CommandAck ack = reply.as(CommandAck.class);
        MavResult result = ack.result().entry();
        return new CommandOutcome(statusFor(result), result, ack.resultParam2(), ack);
    }

    private static CommandOutcome.Status statusFor(MavResult result) {
        if (result == null) {
            // The library returns entry() == null when the wire value matches no declared MavResult
            // constant -- an unrecognized/future result code. Treat conservatively as a failure
            // rather than guessing at a more specific bucket.
            return CommandOutcome.Status.FAILED;
        }
        return switch (result) {
            case MAV_RESULT_ACCEPTED -> CommandOutcome.Status.ACCEPTED;
            case MAV_RESULT_TEMPORARILY_REJECTED -> CommandOutcome.Status.TEMPORARILY_REJECTED;
            case MAV_RESULT_DENIED -> CommandOutcome.Status.DENIED;
            case MAV_RESULT_UNSUPPORTED -> CommandOutcome.Status.UNSUPPORTED;
            case MAV_RESULT_FAILED -> CommandOutcome.Status.FAILED;
            case MAV_RESULT_IN_PROGRESS -> CommandOutcome.Status.IN_PROGRESS;
            case MAV_RESULT_CANCELLED -> CommandOutcome.Status.CANCELLED;
        };
    }

    private static CompletableFuture<CommandOutcome> recoverFromTimeout(Throwable error, AtomicBoolean sawInProgress) {
        if (unwrap(error) instanceof TimeoutException) {
            CommandOutcome outcome = sawInProgress.get()
                    ? new CommandOutcome(CommandOutcome.Status.IN_PROGRESS, MavResult.MAV_RESULT_IN_PROGRESS, -1, null)
                    : new CommandOutcome(CommandOutcome.Status.NO_ACK, null, -1, null);
            return CompletableFuture.completedFuture(outcome);
        }
        return CompletableFuture.failedFuture(error);
    }

    private static Throwable unwrap(Throwable t) {
        return (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) ? t.getCause() : t;
    }

    /**
     * A {@code COMMAND_LONG}/{@code COMMAND_INT} exchange's terminal result. {@code mavResult} and
     * {@code ack} are {@code null} for {@link Status#NO_ACK} (nothing was ever heard); {@code ack} is
     * also {@code null} for the rare {@link Status#IN_PROGRESS}-by-extension-exhaustion case (the
     * last reply frame is deliberately not retained past classification — a caller wanting the raw
     * frame history should observe replies via a {@code Dispatcher} subscription instead).
     *
     * @param resultCode {@code CommandAck.resultParam2()} — an additional, command-specific result
     *                   parameter (e.g. which param of a rejected command was invalid); {@code -1}
     *                   when no ack was ever seen
     */
    public record CommandOutcome(Status status, MavResult mavResult, int resultCode, CommandAck ack) {

        public enum Status {
            ACCEPTED, TEMPORARILY_REJECTED, DENIED, UNSUPPORTED, FAILED, CANCELLED, IN_PROGRESS, NO_ACK
        }
    }
}
