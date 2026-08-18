package com.drones.mavlink.service;

import com.drones.mavlink.PeerId;
import com.drones.mavlink.config.MavlinkCoreSettings;
import com.drones.mavlink.session.MavlinkNode;
import com.drones.mavlink.session.MavlinkSession;
import com.drones.mavlink.transport.UdpListenLink;

import io.dronefleet.mavlink.common.MavParamType;
import io.dronefleet.mavlink.common.ParamRequestRead;
import io.dronefleet.mavlink.common.ParamSet;
import io.dronefleet.mavlink.minimal.MavAutopilot;
import io.dronefleet.mavlink.minimal.MavType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ParameterService} over a real loopback {@link MavlinkSession} against {@link FakeVehicle} —
 * same harness shape as {@link CommandServiceTest}, because the interesting failures here are
 * correlation failures and those only exist once frames actually travel a wire and come back
 * through {@code DefaultCorrelator}.
 */
class ParameterServiceTest {

    private static final Duration TIMEOUT = Duration.ofMillis(300);
    private static final int RETRIES = 2;
    private static final Duration AWAIT = Duration.ofSeconds(10);

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void readsANamedParameterAndReportsWhatTheVehicleAnswered() throws Exception {
        try (Harness h = Harness.start(111)) {
            CompletableFuture<ParameterOutcome> future = h.parameters.read(h.target, "SR2_EXTRA2");
            answerOnceAsync(h.vehicle, "SR2_EXTRA2", 5f, MavParamType.MAV_PARAM_TYPE_INT8, 17, 1234);

            ParameterOutcome outcome = future.get(AWAIT.toSeconds(), TimeUnit.SECONDS);

            assertEquals(ParameterOutcome.Status.OK, outcome.status());
            assertEquals("SR2_EXTRA2", outcome.value().name());
            assertEquals(5f, outcome.value().value());
            assertEquals(MavParamType.MAV_PARAM_TYPE_INT8, outcome.value().type());
            assertEquals(17, outcome.value().index());
            assertEquals(1234, outcome.value().count());
        }
    }

    /** The request must address the parameter by name, never by index — indexes shift across firmware builds. */
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void theReadRequestCarriesTheNameAndTheReadByNameIndexSentinel() throws Exception {
        try (Harness h = Harness.start(112)) {
            h.parameters.read(h.target, "BATT_CAPACITY");

            ParamRequestRead request = h.vehicle.awaitFrame(ParamRequestRead.class, AWAIT);

            assertEquals("BATT_CAPACITY", request.paramId());
            assertEquals(ParameterService.READ_BY_NAME, request.paramIndex());
            assertEquals(h.target.system().value(), request.targetSystem());
        }
    }

    /**
     * The duplicate-key case the wave brief names: a vehicle that answers the same read twice (a
     * retransmit, or an unsolicited echo after another GCS wrote it). The second arrival must find no
     * live waiter and be dropped — never throw "a live await is already registered", never wedge a
     * later read of the same parameter on that same key.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void aResentParamValueIsDroppedAndTheSameParameterStaysReadableAfterwards() throws Exception {
        try (Harness h = Harness.start(113)) {
            CompletableFuture<ParameterOutcome> first = h.parameters.read(h.target, "SR2_EXTRA2");
            h.vehicle.awaitFrame(ParamRequestRead.class, AWAIT);
            h.vehicle.replyParamValue("SR2_EXTRA2", 5f, MavParamType.MAV_PARAM_TYPE_INT8, 17, 1234);
            assertEquals(ParameterOutcome.Status.OK, first.get(AWAIT.toSeconds(), TimeUnit.SECONDS).status());

            // The duplicate lands after the exchange has already released the key.
            h.vehicle.replyParamValue("SR2_EXTRA2", 5f, MavParamType.MAV_PARAM_TYPE_INT8, 17, 1234);
            Thread.sleep(100);

            CompletableFuture<ParameterOutcome> second = h.parameters.read(h.target, "SR2_EXTRA2");
            h.vehicle.awaitFrame(ParamRequestRead.class, AWAIT);
            h.vehicle.replyParamValue("SR2_EXTRA2", 9f, MavParamType.MAV_PARAM_TYPE_INT8, 17, 1234);

            ParameterOutcome outcome = second.get(AWAIT.toSeconds(), TimeUnit.SECONDS);
            assertEquals(ParameterOutcome.Status.OK, outcome.status());
            assertEquals(9f, outcome.value().value(), "the second read must see the fresh value, not the duplicate");
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void writesAParameterAndAcceptsTheEchoedValueWhenItMatches() throws Exception {
        try (Harness h = Harness.start(114)) {
            CompletableFuture<ParameterOutcome> future =
                    h.parameters.write(h.target, "SR2_EXTRA2", 5f, MavParamType.MAV_PARAM_TYPE_INT8);

            ParamSet set = h.vehicle.awaitFrame(ParamSet.class, AWAIT);
            assertEquals("SR2_EXTRA2", set.paramId());
            assertEquals(5f, set.paramValue());
            h.vehicle.replyParamValue("SR2_EXTRA2", 5f, MavParamType.MAV_PARAM_TYPE_INT8, 17, 1234);

            ParameterOutcome outcome = future.get(AWAIT.toSeconds(), TimeUnit.SECONDS);
            assertEquals(ParameterOutcome.Status.OK, outcome.status());
            assertTrue(outcome.ok());
        }
    }

    /** The clamp case: the vehicle acks the write, then reports something else. Only the read-back catches it. */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void aWriteWhoseReadBackDiffersIsReportedAsAMismatchWithBothValues() throws Exception {
        try (Harness h = Harness.start(115)) {
            CompletableFuture<ParameterOutcome> future =
                    h.parameters.write(h.target, "SR2_EXTRA2", 50f, MavParamType.MAV_PARAM_TYPE_INT8);

            h.vehicle.awaitFrame(ParamSet.class, AWAIT);
            h.vehicle.replyParamValue("SR2_EXTRA2", 10f, MavParamType.MAV_PARAM_TYPE_INT8, 17, 1234);

            ParameterOutcome outcome = future.get(AWAIT.toSeconds(), TimeUnit.SECONDS);

            assertEquals(ParameterOutcome.Status.MISMATCH, outcome.status());
            assertEquals(10f, outcome.value().value(), "MISMATCH must still report what the vehicle actually holds");
            assertTrue(outcome.detail().contains("50"), outcome.detail());
            assertTrue(outcome.detail().contains("10"), outcome.detail());
        }
    }

    /**
     * Silence is terminal once the retry budget is spent — the exchange must not keep resending
     * forever, and the number of {@code PARAM_REQUEST_READ}s on the wire must be exactly
     * {@code 1 + retries}.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void silenceEndsAsNoReplyAfterExactlyTheRetryBudgetAndIsNotRetriedAgain() throws Exception {
        try (Harness h = Harness.start(116)) {
            AtomicInteger requests = new AtomicInteger();
            Thread counter = new Thread(() -> {
                try {
                    while (true) {
                        h.vehicle.awaitFrame(ParamRequestRead.class, Duration.ofSeconds(5));
                        requests.incrementAndGet();
                    }
                } catch (Throwable ignored) {
                    // teardown or timeout -- best-effort count only
                }
            }, "count-param-reads");
            counter.setDaemon(true);
            counter.start();

            ParameterOutcome outcome = h.parameters.read(h.target, "NO_SUCH_PARAM")
                    .get(AWAIT.toSeconds(), TimeUnit.SECONDS);

            assertEquals(ParameterOutcome.Status.NO_REPLY, outcome.status());
            assertNull(outcome.value());
            int seen = requests.get();
            // Give any (incorrect) further retry a full window to appear before asserting the count.
            Thread.sleep(TIMEOUT.multipliedBy(2).toMillis());
            assertEquals(RETRIES + 1, seen, "expected exactly one send plus " + RETRIES + " retries");
            assertEquals(seen, requests.get(), "the exchange kept resending after completing");
        }
    }

    /**
     * A {@code PARAM_VALUE} whose name is not the one requested must not satisfy the read. The
     * discriminator is only a routing hash, so exact-name verification is the half of the design
     * that makes a collision harmless — this drives that half directly by having the fake vehicle
     * answer a different name on the same conversation.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void aParamValueNamingADifferentParameterNeverSatisfiesTheRead() throws Exception {
        try (Harness h = Harness.start(117)) {
            CompletableFuture<ParameterOutcome> future = h.parameters.read(h.target, "SR2_EXTRA2");
            h.vehicle.awaitFrame(ParamRequestRead.class, AWAIT);
            h.vehicle.replyParamValue("SR2_EXTRA1", 99f, MavParamType.MAV_PARAM_TYPE_INT8, 16, 1234);

            // A wrong-named reply on a different key is simply uncorrelated, so the read runs its
            // full retry budget and ends honestly rather than reporting the other parameter's value.
            ParameterOutcome outcome = future.get(AWAIT.toSeconds(), TimeUnit.SECONDS);

            assertEquals(ParameterOutcome.Status.NO_REPLY, outcome.status());
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void readAllRunsEveryNameConcurrentlyAndKeepsAnUnansweredOneHonest() throws Exception {
        try (Harness h = Harness.start(118)) {
            CompletableFuture<Map<String, ParameterOutcome>> future =
                    h.parameters.readAll(h.target, List.of("SR2_EXTRA1", "SR2_EXTRA2", "GONE_MISSING", "SR2_EXTRA1"));

            answerOnceAsync(h.vehicle, "SR2_EXTRA1", 4f, MavParamType.MAV_PARAM_TYPE_INT8, 16, 1234);
            answerOnceAsync(h.vehicle, "SR2_EXTRA2", 5f, MavParamType.MAV_PARAM_TYPE_INT8, 17, 1234);

            Map<String, ParameterOutcome> results = future.get(AWAIT.toSeconds(), TimeUnit.SECONDS);

            assertEquals(3, results.size(), "the duplicate name must be collapsed, not awaited twice");
            assertEquals(4f, results.get("SR2_EXTRA1").value().value());
            assertEquals(5f, results.get("SR2_EXTRA2").value().value());
            assertEquals(ParameterOutcome.Status.NO_REPLY, results.get("GONE_MISSING").status());
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void aNameTheWireCannotCarryIsRejectedBeforeAnythingIsSent() throws Exception {
        try (Harness h = Harness.start(119)) {
            assertThrows(IllegalArgumentException.class,
                    () -> h.parameters.read(h.target, "A_VERY_LONG_PARAMETER_NAME"));
            assertThrows(IllegalArgumentException.class, () -> h.parameters.read(h.target, "  "));
            assertThrows(IllegalArgumentException.class,
                    () -> h.parameters.write(h.target, "A_VERY_LONG_PARAMETER_NAME", 1f, MavParamType.MAV_PARAM_TYPE_INT8));

            // Rejection is a caller-side fault: nothing may reach the aircraft, and in particular no
            // waiter may be left registered (which would poison a later, valid read of that name).
            long deadline = System.nanoTime() + Duration.ofMillis(400).toNanos();
            while (System.nanoTime() < deadline) {
                var frame = h.vehicle.pollAny(Duration.ofMillis(100));
                if (frame != null) {
                    assertFalse(frame.is(ParamRequestRead.class) || frame.is(ParamSet.class),
                            "a rejected name still put " + frame.payload().getClass().getSimpleName() + " on the wire");
                }
            }
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void requestsAutopilotVersionAndDecodesTheVersionAndCapabilityBits() throws Exception {
        try (Harness h = Harness.start(120)) {
            CompletableFuture<CapabilityReport> future = h.capabilities.requestAutopilotVersion(h.target);

            Thread responder = new Thread(() -> {
                try {
                    h.vehicle.awaitFrame(io.dronefleet.mavlink.common.CommandLong.class, AWAIT);
                    // 4.5.7, FIRMWARE_VERSION_TYPE_OFFICIAL (255); bit 0 = MISSION_FLOAT, bit 2 = MISSION_INT.
                    h.vehicle.replyAutopilotVersion(0x040507FFL, 0b101, 9L, 1, 2);
                } catch (Throwable ignored) {
                    // teardown
                }
            }, "reply-autopilot-version");
            responder.setDaemon(true);
            responder.start();

            CapabilityReport report = future.get(AWAIT.toSeconds(), TimeUnit.SECONDS);

            assertEquals(CapabilityReport.Status.OK, report.status());
            assertEquals("4.5.7", report.firmwareVersion());
            assertEquals(CapabilityReport.Maturity.OFFICIAL, report.maturity());
            assertEquals(9L, report.boardVersion());
            assertNotNull(report.raw());
            assertTrue(report.supports(io.dronefleet.mavlink.common.MavProtocolCapability.MAV_PROTOCOL_CAPABILITY_MISSION_FLOAT));
            assertTrue(report.supports(io.dronefleet.mavlink.common.MavProtocolCapability.MAV_PROTOCOL_CAPABILITY_MISSION_INT));
            assertFalse(report.supports(io.dronefleet.mavlink.common.MavProtocolCapability.MAV_PROTOCOL_CAPABILITY_FTP));
        }
    }

    /** Betaflight and older firmware never implement this message — that is an answer, not a fault. */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void anUnansweredCapabilityRequestCompletesAsNoReplyRatherThanFailing() throws Exception {
        try (Harness h = Harness.start(121)) {
            CapabilityReport report = h.capabilities.requestAutopilotVersion(h.target)
                    .get(AWAIT.toSeconds(), TimeUnit.SECONDS);

            assertEquals(CapabilityReport.Status.NO_REPLY, report.status());
            assertNull(report.firmwareVersion());
            assertNull(report.raw());
            assertTrue(report.capabilities().isEmpty());
        }
    }

    private static void answerOnceAsync(FakeVehicle vehicle, String name, float value, MavParamType type,
                                        int index, int count) {
        Thread responder = new Thread(() -> {
            try {
                vehicle.awaitFrame(ParamRequestRead.class, AWAIT);
                vehicle.replyParamValue(name, value, type, index, count);
            } catch (Throwable ignored) {
                // teardown
            }
        }, "reply-param-" + name);
        responder.setDaemon(true);
        responder.start();
    }

    private static final class Harness implements AutoCloseable {
        final MavlinkSession session;
        final ParameterService parameters;
        final CapabilityService capabilities;
        final FakeVehicle vehicle;
        final PeerId target;

        private Harness(MavlinkSession session, ParameterService parameters, CapabilityService capabilities,
                        FakeVehicle vehicle, PeerId target) {
            this.session = session;
            this.parameters = parameters;
            this.capabilities = capabilities;
            this.vehicle = vehicle;
            this.target = target;
        }

        static Harness start(int vehicleSysid) throws Exception {
            UdpListenLink listenLink = new UdpListenLink("127.0.0.1", 0);
            MavlinkSession session = new MavlinkSession(MavlinkNode.groundStation(), MavlinkCoreSettings.defaults());
            session.addLink(listenLink);
            int port = Integer.parseInt(listenLink.id().value().substring(listenLink.id().value().lastIndexOf(':') + 1));

            FakeVehicle vehicle = FakeVehicle.start("127.0.0.1", port, vehicleSysid, 1,
                    MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, MavType.MAV_TYPE_QUADROTOR);
            PeerId target = vehicle.id();
            awaitPeerKnown(session, target, Duration.ofSeconds(10));

            return new Harness(session,
                    new ParameterService(session.sink(), session.correlator(), TIMEOUT, RETRIES),
                    new CapabilityService(session.sink(), session.correlator(), TIMEOUT, RETRIES),
                    vehicle, target);
        }

        private static void awaitPeerKnown(MavlinkSession session, PeerId target, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (session.peers().peer(target) != null) {
                    return;
                }
                Thread.sleep(20);
            }
            throw new AssertionError("expected " + target + " to become known within " + timeout);
        }

        @Override
        public void close() {
            vehicle.close();
            session.close();
        }
    }
}
