package com.drones.vision.adapter.mavlink;

import com.drones.vision.flight.domain.model.MessageIntervalOutcome;
import com.drones.vision.flight.domain.model.MessageObservation;
import com.drones.vision.flight.domain.model.ParameterWriteOutcome;
import com.drones.vision.flight.domain.model.RemediationResultCode;
import com.drones.vision.flight.domain.model.VehicleProfile;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.perception.domain.model.FeedId;
import com.drones.vision.perception.domain.model.FeedSpec;
import com.drones.vision.warehouse.domain.model.Device;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramSocket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MavlinkVehicleConfigurator} over real loopback sockets — the honesty half of wave O4.
 *
 * <p>A {@link MavlinkFeedTransmitter} feed is a genuine MAVLink talker but a deliberately partial
 * one: it streams telemetry and answers <b>nothing</b>. That makes it the exact shape this class
 * needs, because the risk O4 carries is not "can it read an aircraft" — {@code
 * MavlinkSitlOnboardingIntegrationTest} answers that against real ArduPilot — but <b>what it claims
 * when the aircraft does not answer</b>. Plan C7: an unanswered half of the probe must surface as
 * {@code complete = false} with a stated reason, never as a plausible-looking profile with zeros and
 * empty lists that a downstream readiness check would then happily evaluate.
 */
class MavlinkVehicleConfiguratorTest {

    private static final int HEARTBEAT_MSG_ID = 0;
    private static final int GLOBAL_POSITION_INT_MSG_ID = 33;
    private static final int VFR_HUD_MSG_ID = 74;

    /** Short everywhere: nothing here is waiting on an answer that will ever come. */
    private static final MavlinkSettings IMPATIENT = MavlinkSettings.defaults().withOnboarding(
            new MavlinkSettings.Onboarding(List.of("SR2_EXTRA2", "BATT_CAPACITY"),
                    Duration.ofMillis(250), 0, Duration.ofMillis(250), 0));

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void aTalkerThatAnswersNothingYieldsAnInventoryAndAnAdmissionRatherThanAProfile() throws Exception {
        int port = freePort();
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        MavlinkTelemetrySource source = new MavlinkTelemetrySource(IMPATIENT);
        MavlinkVehicleConfigurator configurator = new MavlinkVehicleConfigurator(source, IMPATIENT);
        DeviceId deviceId = DeviceId.random();
        FeedId feed = FeedId.random();

        try {
            source.open(device(port, deviceId, Map.of("sysid", "77")));
            transmitter.start(feed, feedSpec(port, "77", 10.0));
            awaitObserved(source, port, 77, Duration.ofSeconds(20));

            // No "#sysid": the sole aircraft on the link is unambiguous, and the pre-registration
            // probe (plan D7) is the one caller that is allowed to discover which one it is.
            VehicleProfile profile = configurator.probe("udp://127.0.0.1:" + port, Duration.ofSeconds(2));

            assertEquals(77, profile.sysid(), "the one system on the link must be resolved without being named");

            // What was genuinely observed.
            assertTrue(observation(profile, HEARTBEAT_MSG_ID).isPresent(), "HEARTBEAT must appear in the inventory");
            assertEquals("HEARTBEAT", observation(profile, HEARTBEAT_MSG_ID).orElseThrow().name());
            assertEquals("GLOBAL_POSITION_INT", observation(profile, GLOBAL_POSITION_INT_MSG_ID).orElseThrow().name(),
                    "a CamelCase library class name must reach the domain as the wire name the spec uses");
            assertTrue(observation(profile, GLOBAL_POSITION_INT_MSG_ID).orElseThrow().hz() > 0,
                    "an observed message must carry a rate, not just a count");
            assertNotNull(profile.linkBytesPerSecond(), "a live link's throughput was measured");
            assertEquals("ardupilot", profile.firmware(), "HEARTBEAT's autopilot field was heard and decoded");

            // What was not. Each of these is a separate opportunity to invent something.
            assertFalse(profile.complete(), "two of the three probe sources never answered");
            assertNull(profile.firmwareVersion(), "AUTOPILOT_VERSION was never answered -- no version may be reported");
            assertNull(profile.capabilityBitmask(), "an unanswered capability request is null, never 0");
            assertTrue(profile.capabilityFlags().isEmpty());
            assertTrue(profile.parameters().isEmpty(), "an unanswered parameter read has no entry, never a zero");
            assertTrue(profile.incompleteReason().contains("AUTOPILOT_VERSION"), profile.incompleteReason());
            assertTrue(profile.incompleteReason().contains("parameter"), profile.incompleteReason());
        } finally {
            transmitter.stop(feed);
            source.close(deviceId);
        }
    }

    /**
     * The genuine timeout path, which needs a peer that is <b>reachable</b> and silent — the two
     * conditions a closed port cannot produce together. A {@link MavlinkFeedTransmitter} feed is
     * heard from (so MAVLink will route to it) and answers nothing, so every request here is really
     * sent, really retried, and really times out.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void requestsThatAreSentToAReachableAircraftAndGoUnansweredTimeOutIntoNoAck() throws Exception {
        int port = freePort();
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        MavlinkTelemetrySource source = new MavlinkTelemetrySource(IMPATIENT);
        MavlinkVehicleConfigurator configurator = new MavlinkVehicleConfigurator(source, IMPATIENT);
        DeviceId deviceId = DeviceId.random();
        FeedId feed = FeedId.random();
        String reachable = "udp://127.0.0.1:" + port + "#78";

        try {
            source.open(device(port, deviceId, Map.of("sysid", "78")));
            transmitter.start(feed, feedSpec(port, "78", 10.0));
            awaitObserved(source, port, 78, Duration.ofSeconds(20));

            long startedAt = System.nanoTime();
            MessageIntervalOutcome interval =
                    configurator.requestMessageInterval(reachable, VFR_HUD_MSG_ID, Duration.ofMillis(200));
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

            assertEquals(RemediationResultCode.NO_ACK, interval.outcome());
            assertEquals(VFR_HUD_MSG_ID, interval.messageId());
            assertTrue(elapsedMillis >= IMPATIENT.ackTimeout().toMillis(),
                    "the request must actually have been sent and waited on, not short-circuited; took "
                            + elapsedMillis + " ms");

            assertTrue(configurator.readParams(reachable, List.of("SR2_EXTRA2", "BATT_CAPACITY")).isEmpty(),
                    "names the aircraft never answers produce no readings at all -- never fabricated zeros");

            ParameterWriteOutcome write = configurator.writeParam(reachable, "SR2_EXTRA2", 5);
            assertEquals(RemediationResultCode.NO_ACK, write.outcome());
            assertNull(write.previousValue(), "nothing was read, so nothing may be offered as a restore point");
            assertNull(write.newValue(), "nothing was written, so nothing may be reported as the new value");
        } finally {
            transmitter.stop(feed);
            source.close(deviceId);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void probingAnAddressNoAircraftIsOnSaysSoInsteadOfThrowing() throws Exception {
        MavlinkTelemetrySource source = new MavlinkTelemetrySource(IMPATIENT);
        MavlinkVehicleConfigurator configurator = new MavlinkVehicleConfigurator(source, IMPATIENT);

        VehicleProfile profile = configurator.probe("udp://127.0.0.1:" + freePort(), Duration.ofSeconds(1));

        assertFalse(profile.complete());
        assertNull(profile.sysid(), "nothing was heard, so no system id may be claimed");
        assertTrue(profile.messages().isEmpty());
        assertTrue(profile.incompleteReason().contains("sysid"),
                "the reason must tell the operator how to proceed: " + profile.incompleteReason());
    }

    /**
     * The asymmetry that keeps a remediation from landing on the wrong airframe: discovery may guess
     * which aircraft is there, everything that touches one may not.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void onlyProbeMayOmitTheSysid() throws Exception {
        MavlinkTelemetrySource source = new MavlinkTelemetrySource(IMPATIENT);
        MavlinkVehicleConfigurator configurator = new MavlinkVehicleConfigurator(source, IMPATIENT);
        String unnamed = "udp://127.0.0.1:" + freePort();

        assertThrows(IllegalArgumentException.class,
                () -> configurator.requestMessageInterval(unnamed, VFR_HUD_MSG_ID, Duration.ofMillis(200)));
        assertThrows(IllegalArgumentException.class, () -> configurator.readParams(unnamed, List.of("SR2_EXTRA2")));
        assertThrows(IllegalArgumentException.class, () -> configurator.writeParam(unnamed, "SR2_EXTRA2", 5));
    }

    @Test
    void aLinkKeyTheModuleCannotAddressIsRejectedBeforeAnySocketIsTouched() {
        MavlinkVehicleConfigurator configurator =
                new MavlinkVehicleConfigurator(new MavlinkTelemetrySource(IMPATIENT), IMPATIENT);

        assertThrows(IllegalArgumentException.class, () -> configurator.probe("", Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> configurator.probe("http://127.0.0.1:14550", Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> configurator.probe("udp://127.0.0.1", Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> configurator.probe("udp://127.0.0.1:14550#999", Duration.ofSeconds(1)),
                "sysid is a one-byte field -- 999 can never be on the wire");
        assertThrows(IllegalArgumentException.class,
                () -> configurator.probe("udp://127.0.0.1:70000#1", Duration.ofSeconds(1)));
    }

    /**
     * "Never heard from" and "asked and got nothing" are both {@code NO_ACK} — nothing changed either
     * way — but they are different facts, and only one of them is about the aircraft. MAVLink cannot
     * route to a peer it has no link for, so no request is sent at all here, and the detail must not
     * describe an unsent request as an unanswered one.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void anAircraftNothingHasEverBeenHeardFromIsReportedAsUnaddressedRatherThanUnanswered() throws Exception {
        MavlinkTelemetrySource source = new MavlinkTelemetrySource(IMPATIENT);
        MavlinkVehicleConfigurator configurator = new MavlinkVehicleConfigurator(source, IMPATIENT);
        String unheard = "udp://127.0.0.1:" + freePort() + "#1";

        ParameterWriteOutcome write = configurator.writeParam(unheard, "SR2_EXTRA2", 5);
        assertEquals(RemediationResultCode.NO_ACK, write.outcome());
        assertNull(write.previousValue(), "nothing was read, so nothing may be offered as a restore point");
        assertNull(write.newValue(), "nothing was written, so nothing may be reported as the new value");
        assertTrue(write.detail().contains("was not sent"), write.detail());

        MessageIntervalOutcome interval =
                configurator.requestMessageInterval(unheard, VFR_HUD_MSG_ID, Duration.ofMillis(200));
        assertEquals(RemediationResultCode.NO_ACK, interval.outcome());
        assertEquals(VFR_HUD_MSG_ID, interval.messageId());
        assertTrue(interval.detail().contains("was not sent"), interval.detail());

        assertTrue(configurator.readParams(unheard, List.of("SR2_EXTRA2")).isEmpty());
    }

    @Test
    void supportIsDecidedByTheSameSourceThatWouldHaveToCarryTheTraffic() throws Exception {
        MavlinkTelemetrySource source = new MavlinkTelemetrySource(IMPATIENT);
        MavlinkVehicleConfigurator configurator = new MavlinkVehicleConfigurator(source, IMPATIENT);
        Device mavlink = device(freePort(), DeviceId.random(), Map.of());
        Device rtsp = new Device(DeviceId.random(), "camera", Set.of(Capability.VIDEO),
                new StreamDescriptor("rtsp", URI.create("rtsp://127.0.0.1:8554/x"), Map.of()));

        assertEquals(source.supports(mavlink), configurator.supports(mavlink));
        assertTrue(configurator.supports(mavlink));
        assertFalse(configurator.supports(rtsp));
    }

    private static Optional<MessageObservation> observation(VehicleProfile profile, int messageId) {
        return profile.messages().stream().filter(m -> m.messageId() == messageId).findFirst();
    }

    private static void awaitObserved(MavlinkTelemetrySource source, int port, int sysid, Duration timeout)
            throws InterruptedException {
        String bindKey = MavlinkTelemetrySource.bindKey("127.0.0.1", port);
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            MavlinkMessageInventory.PeerSnapshot snapshot = source.gateway(bindKey).messageInventory().snapshot(sysid);
            if (snapshot != null && snapshot.messages().stream().anyMatch(m -> m.messageId() == GLOBAL_POSITION_INT_MSG_ID)) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("sysid " + sysid + " was never observed on port " + port + " within " + timeout);
    }

    private static Device device(int port, DeviceId id, Map<String, String> options) {
        return new Device(id, "configurator-test-device", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("mavlink", URI.create("udp://127.0.0.1:" + port), options));
    }

    private static FeedSpec feedSpec(int port, String sysid, double positionRateHz) {
        return new FeedSpec("mavlink", URI.create("udp://127.0.0.1:" + port),
                Map.of("route", "50.00000,30.00000;50.00050,30.00000", "sysid", sysid,
                        "positionRateHz", String.valueOf(positionRateHz), "speedMps", "5"));
    }

    private static int freePort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
