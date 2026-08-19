package com.drones.mavlink.service;

import com.drones.mavlink.CompId;
import com.drones.mavlink.PeerId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.codec.FrameSink;
import com.drones.mavlink.codec.MavFrame;
import com.drones.mavlink.codec.MavHeader;
import com.drones.mavlink.session.Correlator;
import com.drones.mavlink.session.MatchKey;
import com.drones.mavlink.transport.LinkId;
import com.drones.mavlink.transport.LinkPeer;

import io.dronefleet.mavlink.common.MavParamType;
import io.dronefleet.mavlink.common.ParamValue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The half of the parameter design that a loopback test structurally cannot reach.
 *
 * <p>{@code CorrelationKeys} hashes a ≤16-character {@code param_id} into a 64-bit discriminator to
 * <b>route</b> a {@code PARAM_VALUE}, and {@link ParameterService} <b>verifies</b> the name by exact
 * compare on arrival. Over a real link the two halves are inseparable: a wrong-named reply hashes to
 * a different key and never lands on our waiter at all, so no amount of loopback testing exercises
 * the verification. Only a genuine FNV-1a 64 collision would — an event no fleet will produce.
 *
 * <p>So this test replaces the {@link Correlator} with one that hands the service a scripted reply
 * on the key it asked for, whatever that reply is named. That is exactly what a collision looks
 * like from {@link ParameterService}'s side, and it lets the safety claim in that class's javadoc be
 * asserted rather than argued: a foreign name is never reported as the requested parameter's value,
 * and a legitimate reply arriving behind one is still accepted.
 */
class ParameterNameVerificationTest {

    private static final PeerId TARGET = new PeerId(new SysId(42), new CompId(1));
    private static final Duration TIMEOUT = Duration.ofMillis(50);
    private static final Duration AWAIT = Duration.ofSeconds(5);

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void aForeignNamedReplyOnOurOwnKeyIsNeverReportedAsTheRequestedParameter() throws Exception {
        ScriptedCorrelator correlator = new ScriptedCorrelator(List.of(
                paramValue("OTHER_PARAM", 99f), paramValue("OTHER_PARAM", 99f), paramValue("OTHER_PARAM", 99f),
                paramValue("OTHER_PARAM", 99f), paramValue("OTHER_PARAM", 99f), paramValue("OTHER_PARAM", 99f),
                paramValue("OTHER_PARAM", 99f), paramValue("OTHER_PARAM", 99f), paramValue("OTHER_PARAM", 99f),
                paramValue("OTHER_PARAM", 99f)));
        ParameterService service = new ParameterService(new RecordingSink(), correlator, TIMEOUT, 0);

        ParameterOutcome outcome = service.read(TARGET, "SR2_EXTRA2").get(AWAIT.toSeconds(), TimeUnit.SECONDS);

        assertEquals(ParameterOutcome.Status.NO_REPLY, outcome.status());
        assertNull(outcome.value(), "a foreign parameter's value must never be handed back as ours");
        assertTrue(outcome.detail().contains("OTHER_PARAM"), outcome.detail());
        assertTrue(outcome.detail().contains("SR2_EXTRA2"), outcome.detail());
    }

    /** The guard extends the wait; it must not consume the real answer that arrives behind the noise. */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void aLegitimateReplyArrivingAfterAForeignOneIsStillAccepted() throws Exception {
        ScriptedCorrelator correlator = new ScriptedCorrelator(List.of(
                paramValue("OTHER_PARAM", 99f),
                paramValue("SR2_EXTRA2", 5f)));
        ParameterService service = new ParameterService(new RecordingSink(), correlator, TIMEOUT, 0);

        ParameterOutcome outcome = service.read(TARGET, "SR2_EXTRA2").get(AWAIT.toSeconds(), TimeUnit.SECONDS);

        assertEquals(ParameterOutcome.Status.OK, outcome.status());
        assertEquals(5f, outcome.value().value());
    }

    /** Extending the deadline must never look like silence — no resend may follow a foreign reply. */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void aForeignReplyExtendsTheWaitWithoutResendingTheRequest() throws Exception {
        ScriptedCorrelator correlator = new ScriptedCorrelator(List.of(
                paramValue("OTHER_PARAM", 99f),
                paramValue("OTHER_PARAM", 99f),
                paramValue("SR2_EXTRA2", 5f)));
        RecordingSink sink = new RecordingSink();
        ParameterService service = new ParameterService(sink, correlator, TIMEOUT, 3);

        service.read(TARGET, "SR2_EXTRA2").get(AWAIT.toSeconds(), TimeUnit.SECONDS);

        assertEquals(1, sink.sent.size(), "a foreign reply is data, not silence -- it must not trigger a resend");
    }

    private static ParamValue paramValue(String name, float value) {
        return ParamValue.builder()
                .paramId(name)
                .paramValue(value)
                .paramType(MavParamType.MAV_PARAM_TYPE_INT8)
                .paramIndex(1)
                .paramCount(2)
                .build();
    }

    /**
     * Completes every {@link #await} with the next scripted payload, on whatever key was asked for —
     * i.e. it pretends every scripted reply collided onto the requester's key. Once the script is
     * exhausted it behaves like a vehicle gone silent.
     */
    private static final class ScriptedCorrelator implements Correlator {

        private final Deque<Object> scripted;

        ScriptedCorrelator(List<Object> replies) {
            this.scripted = new ArrayDeque<>(replies);
        }

        @Override
        public CompletableFuture<MavFrame> await(MatchKey key, Duration timeout) {
            Object next = scripted.poll();
            if (next == null) {
                return CompletableFuture.failedFuture(new TimeoutException("scripted silence"));
            }
            return CompletableFuture.completedFuture(new MavFrame(
                    new MavHeader(2, 0, key.system(), new CompId(1), key.messageId(), 0, 0, false),
                    next, new LinkId("scripted"), new LinkPeer("127.0.0.1", 14550), Instant.EPOCH));
        }

        @Override
        public void cancel(MatchKey key) {
            // nothing to release -- every future this correlator hands out is already complete
        }
    }

    private static final class RecordingSink implements FrameSink {

        private final List<Object> sent = new ArrayList<>();

        @Override
        public void send(Object payload, PeerId target) {
            sent.add(payload);
        }

        @Override
        public void broadcast(Object payload, LinkId link) {
            sent.add(payload);
        }
    }
}
