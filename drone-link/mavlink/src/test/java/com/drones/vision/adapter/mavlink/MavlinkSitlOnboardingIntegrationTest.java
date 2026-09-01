package com.drones.vision.adapter.mavlink;

import com.drones.vision.flight.domain.model.MessageIntervalOutcome;
import com.drones.vision.flight.domain.model.MessageObservation;
import com.drones.vision.flight.domain.model.ParameterAliases;
import com.drones.vision.flight.domain.model.ParameterReading;
import com.drones.vision.flight.domain.model.ParameterWriteOutcome;
import com.drones.vision.flight.domain.model.RemediationResultCode;
import com.drones.vision.flight.domain.model.VehicleProfile;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.warehouse.domain.model.Device;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link MavlinkVehicleConfigurator} against genuine ArduPilot firmware — the required proof for
 * wave O4 (docs/plans/active/DRONE-ONBOARDING-PLAN.md), where a skipped run counts as a failed wave.
 *
 * <p>{@link MavlinkVehicleConfiguratorTest} covers what this class cannot: what the configurator
 * claims when an aircraft <i>doesn't</i> answer. This class covers what nothing else can — that real
 * ArduPilot answers the three things the probe asks for, that {@code MAV_CMD_SET_MESSAGE_INTERVAL}
 * really changes what arrives, and that a {@code PARAM_SET} survives an independent read-back. Each
 * of those is a claim about firmware behaviour, and firmware is the only thing that can settle it:
 * our own {@link MavlinkFeedTransmitter} would simply agree with us.
 *
 * <h2>What this test discovered, and why it is shaped this way</h2>
 * A freshly booted ArduPilot pushing at a UDP {@code --out} channel streams almost <b>nothing</b> —
 * measured here as four message types ({@code HEARTBEAT} at 1 Hz plus three event-driven ones at
 * ~0.1 Hz), not the dozen a connected GCS sees. That is not a defect in this module; it is the
 * concrete form of the gap this whole plan exists to close. So the flow below is the onboarding flow
 * itself: <b>probe what is actually there → remediate → probe again and check it took</b>. Asserting
 * "≥8 message types" against the first probe would have been asserting something untrue about
 * default firmware; asserting it after remediation proves the platform earned it.
 *
 * <p>The second finding is why that matters so much: ArduPilot 4.7 <b>has no {@code SRx_*}
 * stream-rate parameters at all</b> — {@code SR0_*} through {@code SR2_*} were read off a live
 * instance and every one is absent. Mechanism A is therefore not one of two ways to fix a starved
 * link on this firmware; it is the only way, and this test is what stands behind that claim.
 *
 * <p>One container for the whole sequence — SITL costs seconds to boot, and splitting these into
 * four methods would pay that four times over for no extra coverage.
 */
class MavlinkSitlOnboardingIntegrationTest {

    private static final int SYSID = 1;

    private static final int HEARTBEAT_MSG_ID = 0;
    private static final int VFR_HUD_MSG_ID = 74;

    /**
     * Messages an operator would want on a link that carries none of them by default — one per
     * stream group, so a single group being silently ignored cannot hide behind the others.
     */
    private static final Map<String, Integer> REQUESTED = new LinkedHashMap<>(Map.of(
            "SYS_STATUS", 1,
            "ATTITUDE", 30,
            "GLOBAL_POSITION_INT", 33,
            "RC_CHANNELS", 65,
            "SERVO_OUTPUT_RAW", 36,
            "VFR_HUD", VFR_HUD_MSG_ID,
            "GPS_RAW_INT", 24,
            "SCALED_IMU2", 116,
            "SYSTEM_TIME", 2));

    /**
     * Chosen for Mechanism B because it is real on this firmware, writable, exactly representable as
     * a float, and inert while the geofence is disabled — a write that could change how the aircraft
     * behaves does not belong in a test that runs unattended.
     */
    private static final String WRITABLE_PARAMETER = "FENCE_ALT_MAX";

    /** The configured probe list -- a real onboarding run reads exactly these. */
    private static final List<String> PROBE_PARAMETERS = MavlinkSettings.Onboarding.defaults().probeParameters();

    private static final Duration BOOT_BUDGET = Duration.ofSeconds(60);

    @Test
    @Timeout(value = 420, unit = TimeUnit.SECONDS)
    void aRealArduPilotSitlIsProbedRemediatedAndWrittenTo() throws Exception {
        assumeTrue(SitlContainer.dockerAvailable(), SitlContainer.NO_DOCKER);
        assumeTrue(SitlContainer.imagePresent(), SitlContainer.NO_IMAGE);

        int port = SitlContainer.freePort();
        // Generous per-exchange budgets: SITL on a loaded box is slower than a bench aircraft, and a
        // flaky timeout here would read as "ArduPilot does not support this" -- the one conclusion
        // this test exists to make trustworthy.
        MavlinkSettings settings = MavlinkSettings.defaults().withOnboarding(new MavlinkSettings.Onboarding(
                PROBE_PARAMETERS, Duration.ofSeconds(5), 3, Duration.ofSeconds(2), 4));
        MavlinkTelemetrySource source = new MavlinkTelemetrySource(settings);
        MavlinkVehicleConfigurator configurator = new MavlinkVehicleConfigurator(source, settings);
        DeviceId deviceId = DeviceId.random();
        String linkKey = "udp://127.0.0.1:" + port + "#" + SYSID;

        try (SitlContainer ignored = SitlContainer.start("onboarding", port, SYSID)) {
            source.open(device(port, deviceId));
            awaitHeartbeat(source, port, BOOT_BUDGET);

            VehicleProfile probed = probeAndAssertIdentity(configurator, linkKey);
            remediateAndAssertTheStreamsArrived(configurator, linkKey, probed);
            writeAndAssertItSurvivesAnIndependentReadBack(configurator, linkKey);
        } finally {
            source.close(deviceId);
        }
    }

    /** PROBE (plan §3.1): the inventory, the capability report and the parameter batch, in one call. */
    private VehicleProfile probeAndAssertIdentity(MavlinkVehicleConfigurator configurator, String linkKey) {
        VehicleProfile profile = configurator.probe(linkKey, Duration.ofSeconds(8));

        assertEquals(SYSID, profile.sysid());
        assertEquals("ardupilot", profile.firmware(), "a real ArduPilot HEARTBEAT identifies its autopilot");
        assertTrue(observation(profile, HEARTBEAT_MSG_ID).isPresent(), "HEARTBEAT must be observed: " + names(profile));
        assertEquals("HEARTBEAT", observation(profile, HEARTBEAT_MSG_ID).orElseThrow().name(),
                "the CamelCase->SCREAMING_SNAKE name transform must survive real traffic");
        assertTrue(profile.messages().stream().allMatch(m -> m.hz() > 0 && m.hz() < 200),
                "every observed rate must be plausible (0 < hz < 200): " + rates(profile));
        assertNotNull(profile.linkBytesPerSecond());
        assertTrue(profile.linkBytesPerSecond() > 0);

        assertNotNull(profile.firmwareVersion(), "AUTOPILOT_VERSION must have been answered and decoded");
        assertTrue(profile.firmwareVersion().matches("\\d+\\.\\d+\\.\\d+"),
                "a decoded firmware version reads as major.minor.patch, got " + profile.firmwareVersion());
        assertNotNull(profile.capabilityBitmask());
        assertTrue(profile.capabilityFlags().contains("MAV_PROTOCOL_CAPABILITY_MAVLINK2"),
                "ArduPilot 4.x speaks MAVLink 2: " + profile.capabilityFlags());

        // Every one, not "most": each default name was read off this firmware before being written
        // down, so a miss here means either the protocol path regressed or the firmware moved --
        // both of which this wave exists to catch rather than absorb.
        //
        // The one licensed exception is a renamed parameter (FLEET-RADIO-PLAN F0): the probe asks
        // under every spelling on purpose, so a 4.7 SITL leaves the pre-4.7 spelling unanswered by
        // design. The invariant that still bites is per *parameter*, not per name -- some spelling
        // of every probed parameter must come back.
        List<String> unanswered = PROBE_PARAMETERS.stream()
                .filter(name -> profile.parameters().stream()
                        .noneMatch(r -> ParameterAliases.sameParameter(r.name(), name)))
                .toList();
        assertTrue(unanswered.isEmpty(),
                "every one of the " + PROBE_PARAMETERS.size() + " default probe parameters exists on ArduPilot "
                        + "Copter 4.7 under some spelling and must be answered; unanswered: " + unanswered);
        assertEquals(1, profile.parameters().stream()
                        .filter(r -> ParameterAliases.sameParameter(r.name(), "MAV_SYSID")).count(),
                "4.7 answers the modern spelling, so the alias fallback pass must not have fired: "
                        + names(profile));
        assertEquals(SYSID, (int) reading(profile, "MAV_SYSID").orElseThrow().value(),
                "MAV_SYSID must carry the value this instance was booted with -- not a fabricated zero");
        assertTrue(profile.parameters().stream().noneMatch(p -> p.type().isBlank()),
                "every reading carries the vehicle's own type tag");

        assertTrue(profile.complete(),
                "every half of the probe answered, so nothing may be reported as missing: "
                        + profile.incompleteReason());
        return profile;
    }

    /** MECHANISM A (plan §4a): the session-scoped {@code MAV_CMD_SET_MESSAGE_INTERVAL} request. */
    private void remediateAndAssertTheStreamsArrived(MavlinkVehicleConfigurator configurator, String linkKey,
                                                     VehicleProfile before) throws InterruptedException {
        REQUESTED.forEach((name, messageId) -> {
            MessageIntervalOutcome outcome =
                    configurator.requestMessageInterval(linkKey, messageId, Duration.ofMillis(200));
            assertEquals(RemediationResultCode.ACCEPTED, outcome.outcome(),
                    "ArduPilot 4.x supports MAV_CMD_SET_MESSAGE_INTERVAL for " + name + ": " + outcome.detail());
        });

        VehicleProfile after = awaitProfileWith(configurator, linkKey, 8, Duration.ofSeconds(60));

        assertTrue(after.messages().size() >= 8,
                "after requesting " + REQUESTED.size() + " streams the inventory must show at least 8 message "
                        + "types (it showed " + before.messages().size() + " before): " + names(after));
        assertTrue(after.messages().stream().allMatch(m -> m.hz() > 0 && m.hz() < 200),
                "every rate must still be plausible after remediation: " + rates(after));
        assertTrue(hzOf(after, VFR_HUD_MSG_ID) >= 3.0,
                "VFR_HUD was requested at 5 Hz and must be observed near it, was " + hzOf(before, VFR_HUD_MSG_ID)
                        + " Hz, now " + hzOf(after, VFR_HUD_MSG_ID) + " Hz");
        assertTrue(after.messages().size() > before.messages().size(),
                "remediation must have added streams, not merely kept the ones already arriving");
    }

    /** MECHANISM B (plan §4b/§6.2): snapshot, write, read back, and restore — all four, or it is not safe. */
    private void writeAndAssertItSurvivesAnIndependentReadBack(MavlinkVehicleConfigurator configurator,
                                                               String linkKey) {
        ParameterReading snapshot = readOne(configurator, linkKey, WRITABLE_PARAMETER);
        double target = snapshot.value() == 4 ? 8 : 4;

        ParameterWriteOutcome write = configurator.writeParam(linkKey, WRITABLE_PARAMETER, target);
        assertEquals(RemediationResultCode.ACCEPTED, write.outcome(), write.detail());
        assertEquals(snapshot.value(), write.previousValue(),
                "the snapshot a caller would restore to must be what was actually on the aircraft");
        assertEquals(target, write.newValue(), "ACCEPTED means the read-back equalled what was asked for");

        assertEquals(target, readOne(configurator, linkKey, WRITABLE_PARAMETER).value(),
                "an independent re-read must agree -- the read-back was the aircraft's value, never an echo of "
                        + "our own request");

        ParameterWriteOutcome restored = configurator.writeParam(linkKey, WRITABLE_PARAMETER, snapshot.value());
        assertEquals(RemediationResultCode.ACCEPTED, restored.outcome(), restored.detail());
        assertEquals(snapshot.value(), readOne(configurator, linkKey, WRITABLE_PARAMETER).value(),
                "the snapshot must be usable -- a restore that does not restore makes a Tier-A write unsafe");
    }

    private static ParameterReading readOne(MavlinkVehicleConfigurator configurator, String linkKey, String name) {
        List<ParameterReading> readings = configurator.readParams(linkKey, List.of(name));
        assertEquals(1, readings.size(), "expected exactly one reading for " + name + ", got " + readings);
        return readings.get(0);
    }

    /**
     * The inventory is a decaying rolling window, so a newly raised rate needs a few seconds of
     * traffic before it reads as raised. Waiting for the observation rather than sleeping a fixed
     * guess is the point: the assertion is about what actually arrived.
     */
    private static VehicleProfile awaitProfileWith(MavlinkVehicleConfigurator configurator, String linkKey,
                                                   int messageTypes, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        VehicleProfile best = null;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(3_000);
            VehicleProfile profile = configurator.probe(linkKey, Duration.ofSeconds(2));
            if (best == null || profile.messages().size() > best.messages().size()) {
                best = profile;
            }
            if (profile.messages().size() >= messageTypes && hzOf(profile, VFR_HUD_MSG_ID) >= 3.0) {
                return profile;
            }
        }
        return best;
    }

    private static double hzOf(VehicleProfile profile, int messageId) {
        return observation(profile, messageId).map(MessageObservation::hz).orElse(0.0);
    }

    private static Optional<MessageObservation> observation(VehicleProfile profile, int messageId) {
        return profile.messages().stream().filter(m -> m.messageId() == messageId).findFirst();
    }

    private static Optional<ParameterReading> reading(VehicleProfile profile, String name) {
        return profile.parameters().stream().filter(p -> p.name().equals(name)).findFirst();
    }

    private static String names(VehicleProfile profile) {
        return profile.messages().stream().map(MavlinkSitlOnboardingIntegrationTest::label).sorted().toList().toString();
    }

    private static String rates(VehicleProfile profile) {
        return profile.messages().stream().map(m -> label(m) + "=" + m.hz()).sorted().toList().toString();
    }

    private static String label(MessageObservation observation) {
        return observation.name() == null ? "#" + observation.messageId() : observation.name();
    }

    /**
     * Waits only for the aircraft to say anything at all. Deliberately not "wait until it streams
     * properly" — that it does not is the finding this test is built around.
     */
    private static void awaitHeartbeat(MavlinkTelemetrySource source, int port, Duration timeout)
            throws InterruptedException {
        String bindKey = MavlinkTelemetrySource.bindKey("127.0.0.1", port);
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            MavlinkMessageInventory.PeerSnapshot snapshot = source.gateway(bindKey).messageInventory().snapshot(SYSID);
            if (snapshot != null && snapshot.messages().stream().anyMatch(m -> m.messageId() == HEARTBEAT_MSG_ID)) {
                Thread.sleep(4_000); // let the rate window fill before anything reads a rate off it
                return;
            }
            Thread.sleep(1_000);
        }
        throw new AssertionError("ArduPilot SITL never sent a HEARTBEAT on port " + port + " within " + timeout);
    }

    private static Device device(int port, DeviceId id) {
        return new Device(id, "sitl-onboarding-test", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("mavlink", URI.create("udp://127.0.0.1:" + port),
                        Map.of("sysid", String.valueOf(SYSID))));
    }
}
