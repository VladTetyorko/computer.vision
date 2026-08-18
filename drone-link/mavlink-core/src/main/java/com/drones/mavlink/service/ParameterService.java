package com.drones.mavlink.service;

import com.drones.mavlink.PeerId;
import com.drones.mavlink.codec.FrameSink;
import com.drones.mavlink.codec.MavFrame;
import com.drones.mavlink.config.MavlinkCoreSettings;
import com.drones.mavlink.session.CorrelationKeys;
import com.drones.mavlink.session.Correlator;
import com.drones.mavlink.session.MatchKey;

import io.dronefleet.mavlink.common.MavParamType;
import io.dronefleet.mavlink.common.ParamRequestRead;
import io.dronefleet.mavlink.common.ParamSet;
import io.dronefleet.mavlink.common.ParamValue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MAVLink's parameter microservice (ONBOARDING-PLAN §5, wave O2): read a <b>named</b> parameter, and
 * write one with a mandatory read-back. Family A, so the retry/deadline mechanics come from
 * {@link RequestResponse}; this class supplies only Parameter's own policy — how a name becomes a
 * {@link MatchKey}, and what counts as a satisfying reply.
 *
 * <h2>Read by name, never by index</h2>
 * {@code PARAM_REQUEST_READ} can address a parameter either by {@code param_id} or by
 * {@code param_index}. This service always sends the name with {@link #READ_BY_NAME} in the index
 * field, because a parameter's index is a property of one firmware build's table and shifts between
 * versions — an index read is a correct-looking way to read the wrong parameter after an update.
 *
 * <h2>A write is a read-back, not a send</h2>
 * {@link #write} is not fire-and-forget and has no fire-and-forget variant. An autopilot answers
 * {@code PARAM_SET} with a {@code PARAM_VALUE} carrying what it <i>actually</i> stored, which is not
 * always what was asked: out-of-range values are clamped and a float lands in whatever integer width
 * the parameter really is. So the exchange completes with {@link ParameterOutcome.Status#MISMATCH}
 * unless the echoed value is bit-for-bit what was requested. This is what makes a Tier-A write
 * safely reportable to an operator (plan §6.1's "yes, with before/after values").
 *
 * <h2>How a 16-character name fits in a 64-bit discriminator</h2>
 * It does not — {@link CorrelationKeys} hashes the name to <i>route</i> the reply, and this class
 * <i>verifies</i> it by exact string compare on arrival. A {@code PARAM_VALUE} that lands on our key
 * but names a different parameter is therefore not accepted: the classifier keeps the same waiter
 * alive for another window rather than resending, bounded by {@value #MAX_FOREIGN_REPLIES} so a
 * pathological stream cannot wedge the caller's future. That bound exists for completeness, not for
 * a case anyone expects to see — an FNV-1a 64 collision between two ≤16-character parameter names
 * is not something a fleet will encounter.
 *
 * <h2>Non-goal: the full parameter download</h2>
 * {@code PARAM_REQUEST_LIST} — the "stream me all ~1200 parameters" protocol, with its own
 * missing-index gap detection and re-request loop — is deliberately not implemented. Everything this
 * platform needs (probe, readiness, Tier-A remediation) addresses a short list of parameters it can
 * name in advance, and a full download on a 2.4 kB/s telemetry link costs minutes of airtime.
 */
public final class ParameterService {

    /** {@code param_index} sentinel meaning "the name in {@code param_id} is the address" (spec convention). */
    public static final int READ_BY_NAME = -1;

    /**
     * How many wrong-named {@code PARAM_VALUE}s may land on one waiter's key before the exchange
     * gives up on it. Reachable only through a 64-bit hash collision — see this class's own note.
     */
    private static final int MAX_FOREIGN_REPLIES = 8;

    private final RequestResponse requestResponse;
    private final Duration timeout;
    private final int retries;

    public ParameterService(FrameSink sink, Correlator correlator, Duration timeout, int retries) {
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

    public ParameterService(FrameSink sink, Correlator correlator, MavlinkCoreSettings settings) {
        this(sink, correlator, Objects.requireNonNull(settings, "settings").parameter().timeout(),
                settings.parameter().retries());
    }

    /**
     * Reads one named parameter. Completes with {@link ParameterOutcome.Status#NO_REPLY} when the
     * vehicle stays silent — which is also how an autopilot says "I have no such parameter": the
     * protocol gives it no way to answer a name it does not recognise, so an unsupported parameter
     * and an unreachable one are the same observation, and this service does not pretend otherwise.
     */
    public CompletableFuture<ParameterOutcome> read(PeerId target, String name) {
        Objects.requireNonNull(target, "target");
        String paramId = requireName(name);
        RequestResponse.AttemptPayload payload = attempt -> ParamRequestRead.builder()
                .targetSystem(target.system().value())
                .targetComponent(target.component().value())
                .paramId(paramId)
                .paramIndex(READ_BY_NAME)
                .build();
        return exchange(target, paramId, payload, null);
    }

    /**
     * Reads several named parameters, all in flight at once — they occupy distinct
     * {@link MatchKey}s, so they neither collide nor serialise, and one unanswered name costs the
     * whole batch one timeout window rather than a place in a queue. Duplicates in {@code names}
     * are collapsed (two live waiters on one key is an {@link IllegalStateException} by
     * {@link Correlator}'s own contract, and would be a caller-visible fault for a harmless input).
     *
     * <p>The returned map is keyed by normalised name and preserves {@code names}' order. On a
     * genuinely constrained link a caller should chunk rather than pass a list of hundreds: this
     * method puts {@code names.size() × (1 + retries)} small packets on the air in the worst case.
     */
    public CompletableFuture<Map<String, ParameterOutcome>> readAll(PeerId target, List<String> names) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(names, "names");
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String name : names) {
            distinct.add(requireName(name));
        }
        Map<String, CompletableFuture<ParameterOutcome>> inFlight = new LinkedHashMap<>();
        for (String name : distinct) {
            inFlight.put(name, read(target, name));
        }
        return CompletableFuture.allOf(inFlight.values().toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    Map<String, ParameterOutcome> results = new LinkedHashMap<>();
                    inFlight.forEach((name, future) -> results.put(name, future.join()));
                    return Map.copyOf(results);
                });
    }

    /**
     * Writes one named parameter and verifies what the vehicle stored.
     *
     * @param type the vehicle's storage type for this parameter. ArduPilot ignores this field and
     *             uses its own table; PX4 does not, and a wrong type there is a rejected or
     *             mis-stored write — which is exactly what the read-back surfaces as
     *             {@link ParameterOutcome.Status#MISMATCH} rather than a silent success.
     */
    public CompletableFuture<ParameterOutcome> write(PeerId target, String name, float value, MavParamType type) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(type, "type");
        String paramId = requireName(name);
        RequestResponse.AttemptPayload payload = attempt -> ParamSet.builder()
                .targetSystem(target.system().value())
                .targetComponent(target.component().value())
                .paramId(paramId)
                .paramValue(value)
                .paramType(type)
                .build();
        return exchange(target, paramId, payload, value);
    }

    /** {@code expected} non-null turns this into a write's read-back check. */
    private CompletableFuture<ParameterOutcome> exchange(PeerId target, String paramId,
                                                          RequestResponse.AttemptPayload payload, Float expected) {
        MatchKey key = CorrelationKeys.forParamValue(target.system(), paramId);
        AtomicInteger foreignReplies = new AtomicInteger();
        RequestResponse.ReplyClassifier classifier = reply -> classify(reply, paramId, foreignReplies);
        return requestResponse.exchange(target, key, timeout, retries, payload, classifier)
                .thenApply(reply -> terminalOutcome(reply, paramId, expected))
                .exceptionallyCompose(error -> recoverFromTimeout(error, paramId));
    }

    /** {@code null} = terminal; a positive {@link Duration} = keep the same waiter alive, no resend. */
    private Duration classify(MavFrame reply, String paramId, AtomicInteger foreignReplies) {
        if (namesMatch(reply, paramId)) {
            return null;
        }
        if (foreignReplies.incrementAndGet() > MAX_FOREIGN_REPLIES) {
            return null; // give up -- terminalOutcome reports this as "never heard the right name"
        }
        return timeout;
    }

    private static boolean namesMatch(MavFrame reply, String paramId) {
        return CorrelationKeys.normalizeParamId(reply.as(ParamValue.class).paramId()).equals(paramId);
    }

    private static ParameterOutcome terminalOutcome(MavFrame reply, String paramId, Float expected) {
        ParamValue paramValue = reply.as(ParamValue.class);
        String reported = CorrelationKeys.normalizeParamId(paramValue.paramId());
        if (!reported.equals(paramId)) {
            return new ParameterOutcome(ParameterOutcome.Status.NO_REPLY, null,
                    "no PARAM_VALUE for \"" + paramId + "\" -- only \"" + reported + "\" kept arriving on its key");
        }
        ParameterValue value = new ParameterValue(reported, paramValue.paramValue(),
                paramValue.paramType().entry(), paramValue.paramIndex(), paramValue.paramCount());
        if (expected == null) {
            return new ParameterOutcome(ParameterOutcome.Status.OK, value, "");
        }
        // Exact float comparison on purpose: both sides are IEEE float32 off the same wire field, so
        // a vehicle that stored what was asked echoes the identical bits. Any difference at all --
        // a clamp, an integer truncation, a NaN -- is a real divergence an operator must be told
        // about, and an epsilon here would hide precisely the small ones (a 0.1 clamped to 0.0999).
        if (paramValue.paramValue() == expected) {
            return new ParameterOutcome(ParameterOutcome.Status.OK, value, "");
        }
        return new ParameterOutcome(ParameterOutcome.Status.MISMATCH, value,
                "requested " + expected + ", vehicle stored " + paramValue.paramValue());
    }

    private CompletableFuture<ParameterOutcome> recoverFromTimeout(Throwable error, String paramId) {
        if (unwrap(error) instanceof TimeoutException) {
            return CompletableFuture.completedFuture(ParameterOutcome.noReply(paramId, timeout, retries + 1));
        }
        return CompletableFuture.failedFuture(error);
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("parameter name must not be blank");
        }
        String normalized = CorrelationKeys.normalizeParamId(name);
        if (!normalized.equals(name)) {
            throw new IllegalArgumentException(
                    "parameter name \"" + name + "\" does not survive the wire's 16-char param_id field");
        }
        return normalized;
    }

    private static Throwable unwrap(Throwable t) {
        return (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
    }
}
