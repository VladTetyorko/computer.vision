package com.drones.mavlink.api;

import com.drones.mavlink.PeerId;
import com.drones.mavlink.config.MavlinkCoreSettings;
import com.drones.mavlink.service.CommandService;

import io.dronefleet.mavlink.common.MavCmd;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one {@link CommandGateway} implementation — over {@link CommandService}. Per plan §5.1, this
 * is the one class in this package allowed to depend on L3/L4; the frozen seam itself
 * ({@link CommandRequest}, {@link CommandOutcome}, {@link VehicleKeyResolver}, {@link CommandGateway})
 * stays L0-only so a broker adapter can import <i>just</i> that seam without ever naming this class
 * or anything it pulls in — see this module's MODULE.md for the API.md table-vs-deliverables tension
 * this resolves.
 *
 * <h2>B3 — the safety-critical part</h2>
 * {@link #submit} is idempotent within {@code commandDedupeWindow}: a repeat of a still-live
 * {@code correlationId} returns the <b>same</b> {@link CompletionStage} (not a new dispatch — see the
 * dedupe-map reservation below), and a repeat of an already-completed one returns
 * {@link CommandOutcome.Status#DUPLICATE}. Kafka is at-least-once; this is what stops a redelivered
 * message from arming an aircraft twice. Reservation happens atomically via
 * {@link ConcurrentHashMap#compute} on {@code correlationId} — the mapping function either returns
 * the still-fresh existing entry (a genuine duplicate) or installs a fresh, not-yet-completed one
 * (this caller is the one that gets to dispatch); either way exactly one dispatch ever happens per
 * live {@code correlationId}, even under concurrent callers.
 */
public final class DefaultCommandGateway implements CommandGateway {

    private final CommandService commandService;
    private final VehicleKeyResolver resolver;
    private final Duration dedupeWindow;
    private final Map<String, DedupeEntry> dedupe = new ConcurrentHashMap<>();

    public DefaultCommandGateway(CommandService commandService, VehicleKeyResolver resolver, Duration dedupeWindow) {
        this.commandService = Objects.requireNonNull(commandService, "commandService");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(dedupeWindow, "dedupeWindow");
        if (dedupeWindow.isZero() || dedupeWindow.isNegative()) {
            throw new IllegalArgumentException("dedupeWindow must be positive, got " + dedupeWindow);
        }
        this.dedupeWindow = dedupeWindow;
    }

    public DefaultCommandGateway(CommandService commandService, VehicleKeyResolver resolver, MavlinkCoreSettings settings) {
        this(commandService, resolver, Objects.requireNonNull(settings, "settings").commandDedupeWindow());
    }

    @Override
    public CompletionStage<CommandOutcome> submit(CommandRequest request) {
        Objects.requireNonNull(request, "request");
        // Kind/param validity is checked synchronously, before any dedupe bookkeeping, so a
        // malformed request throws here rather than silently occupying a correlationId slot or
        // completing UNREACHABLE (per the frozen contract: "only a malformed request throws").
        CommandSpec spec = CommandSpec.forRequest(request);

        prunedExpired();
        Instant now = Instant.now();
        boolean[] isNew = {false};
        DedupeEntry entry = dedupe.compute(request.correlationId(), (id, existing) -> {
            if (existing != null && !existing.expired(now, dedupeWindow)) {
                return existing;
            }
            isNew[0] = true;
            return new DedupeEntry(new CompletableFuture<>(), now);
        });

        if (!isNew[0]) {
            if (!entry.stage().isDone()) {
                return entry.stage(); // live repeat -- identical outcome, no second dispatch
            }
            return CompletableFuture.completedFuture(duplicate(request, Instant.now()));
        }

        dispatch(request, spec).thenAccept(entry.stage()::complete);
        return entry.stage();
    }

    private CompletableFuture<CommandOutcome> dispatch(CommandRequest request, CommandSpec spec) {
        PeerId target = resolver.resolve(request.vehicleKey());
        if (target == null) {
            return CompletableFuture.completedFuture(unreachable(request, Instant.now()));
        }
        return commandService
                .sendLong(target, spec.command(), spec.p1(), spec.p2(), spec.p3(), spec.p4(), spec.p5(), spec.p6(), spec.p7())
                .thenApply(outcome -> toApiOutcome(request, outcome))
                .exceptionally(error -> unreachable(request, Instant.now()));
    }

    /** Opportunistic, lazy sweep -- no background thread; bounded by how often {@link #submit} is called. */
    private void prunedExpired() {
        Instant now = Instant.now();
        dedupe.values().removeIf(entry -> entry.expired(now, dedupeWindow));
    }

    private static CommandOutcome toApiOutcome(CommandRequest request, CommandService.CommandOutcome outcome) {
        CommandOutcome.Status status = switch (outcome.status()) {
            case ACCEPTED -> CommandOutcome.Status.ACCEPTED;
            case IN_PROGRESS -> CommandOutcome.Status.IN_PROGRESS;
            case TEMPORARILY_REJECTED, DENIED, FAILED, CANCELLED -> CommandOutcome.Status.DENIED;
            case UNSUPPORTED -> CommandOutcome.Status.UNSUPPORTED;
            case NO_ACK -> CommandOutcome.Status.NO_ACK;
        };
        String detail = outcome.mavResult() != null ? outcome.mavResult().toString() : outcome.status().toString();
        return new CommandOutcome(request.correlationId(), status, outcome.resultCode(), detail, Instant.now());
    }

    private static CommandOutcome unreachable(CommandRequest request, Instant at) {
        return new CommandOutcome(request.correlationId(), CommandOutcome.Status.UNREACHABLE, 0,
                "vehicle not resolvable, or resolvable but never heard from", at);
    }

    private static CommandOutcome duplicate(CommandRequest request, Instant at) {
        return new CommandOutcome(request.correlationId(), CommandOutcome.Status.DUPLICATE, 0,
                "duplicate of an already-completed command within the dedupe window", at);
    }

    private record DedupeEntry(CompletableFuture<CommandOutcome> stage, Instant registeredAt) {
        boolean expired(Instant now, Duration window) {
            return Duration.between(registeredAt, now).compareTo(window) > 0;
        }
    }

    /**
     * The kind→command mapping table (the wave brief's own words: "keep the kind→command mapping in
     * one table — it is the thing most likely to grow"). Deliberately domain-free: {@code SET_MODE}'s
     * numeric {@code custom_mode} is not resolved here (this module has no firmware-aware mode-name
     * table — that stays project policy, plan D5) — the caller supplies it already-resolved as
     * {@code params[0]}. {@code RTL} uses the spec's own dedicated, firmware-agnostic
     * {@code MAV_CMD_NAV_RETURN_TO_LAUNCH} rather than a custom-mode number for exactly the same
     * reason — see this module's MODULE.md for why this differs from {@code adapter-mavlink}'s
     * current DO_SET_MODE-based RTL (a deliberate, non-breaking improvement; W4 wires the adapter to
     * this gateway, nothing consumes the old path through this module).
     */
    private record CommandSpec(MavCmd command, float p1, float p2, float p3, float p4, float p5, float p6, float p7) {

        private static final float ARM_FORCE_MAGIC = 21196f;
        private static final float MAV_MODE_FLAG_CUSTOM_MODE_ENABLED = 1f;

        static CommandSpec forRequest(CommandRequest request) {
            List<Double> p = request.params();
            return switch (parseKind(request.kind())) {
                case ARM -> new CommandSpec(MavCmd.MAV_CMD_COMPONENT_ARM_DISARM,
                        1f, request.force() ? ARM_FORCE_MAGIC : 0f, 0, 0, 0, 0, 0);
                case DISARM -> new CommandSpec(MavCmd.MAV_CMD_COMPONENT_ARM_DISARM,
                        0f, request.force() ? ARM_FORCE_MAGIC : 0f, 0, 0, 0, 0, 0);
                case SET_MODE -> new CommandSpec(MavCmd.MAV_CMD_DO_SET_MODE,
                        MAV_MODE_FLAG_CUSTOM_MODE_ENABLED, requireParam(p, 0, "SET_MODE"), 0, 0, 0, 0, 0);
                case RTL -> new CommandSpec(MavCmd.MAV_CMD_NAV_RETURN_TO_LAUNCH, 0, 0, 0, 0, 0, 0, 0);
                case SET_MESSAGE_INTERVAL -> new CommandSpec(MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL,
                        requireParam(p, 0, "SET_MESSAGE_INTERVAL"), requireParam(p, 1, "SET_MESSAGE_INTERVAL"),
                        0, 0, 0, 0, 0);
                case REQUEST_MESSAGE -> new CommandSpec(MavCmd.MAV_CMD_REQUEST_MESSAGE,
                        requireParam(p, 0, "REQUEST_MESSAGE"), 0, 0, 0, 0, 0, 0);
            };
        }

        private static Kind parseKind(String kind) {
            try {
                return Kind.valueOf(kind);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown CommandRequest.kind: \"" + kind + "\" -- expected one of "
                        + List.of(Kind.values()), e);
            }
        }

        private static float requireParam(List<Double> params, int index, String kind) {
            if (index >= params.size()) {
                throw new IllegalArgumentException(
                        "CommandRequest.kind=" + kind + " requires at least " + (index + 1) + " param(s), got " + params.size());
            }
            return params.get(index).floatValue();
        }

        private enum Kind {
            ARM, DISARM, SET_MODE, RTL, SET_MESSAGE_INTERVAL, REQUEST_MESSAGE
        }
    }
}
