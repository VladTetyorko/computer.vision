package com.drones.vision.adapter.mavlink;

import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.warehouse.domain.model.Device;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Wave O8's required, docker-gated proof against real ArduPilot firmware (docs/plans/active/
 * DRONE-ONBOARDING-PLAN.md) — a skipped run counts as a failed wave, the same rule {@link
 * MavlinkSitlOnboardingIntegrationTest} (wave O4) already lives by.
 *
 * <h2>The plan's stated exit criterion is wrong; this is the corrected one</h2>
 * The plan's O8 row describes connecting with {@code SR2_EXTRA2=0} and observing {@code VFR_HUD}
 * begin arriving "without any parameter write". Wave O4 already established, against this same
 * firmware, that <b>no {@code SRx_*} parameter exists on ArduPilot 4.7 at all</b> — see {@code
 * MavlinkSitlOnboardingIntegrationTest}'s own javadoc and this module's MODULE.md. There is no
 * parameter to set to {@code 0}, so that exact scenario cannot be run. What this test proves instead,
 * against the same firmware: a stock SITL is already starved by default (a handful of message types,
 * matching O4's own measurement); connecting a <i>second</i> time with {@link
 * MavlinkSettings.Onboarding#requestMessagesOnConnect()} on makes the configured set begin arriving
 * automatically, on the very first heartbeat of that connection — <b>with no parameter write, and no
 * parameter read, performed at any point in this test.</b> {@link MavlinkVehicleConfigurator} (which
 * is how a parameter would be read or written) is never even constructed here.
 *
 * <h2>Why one container serves two connections</h2>
 * SITL costs real seconds to boot. It also does not care whether anything is listening on its
 * {@code MAVLINK_TARGET_PORT} — it is a plain UDP send, so the container is left running throughout;
 * only the two {@link MavlinkTelemetrySource}s (and therefore the two distinct {@link
 * MavlinkGateway}s and their independent {@link MavlinkConnectRemediator}s, if any) come and go, on
 * the same host port. The second gateway hearing that port's very first post-rebind heartbeat is
 * exactly "a gateway learns a peer" — {@link MavlinkConnectRemediator}'s own trigger — which is what
 * makes reusing one container the more faithful test, not merely the cheaper one.
 *
 * <h2>MAVLINK-COMMANDS-PLAN P2: there is no "starved" connection left to observe</h2>
 * {@link MavlinkStreamNegotiator} fires unconditionally on every claim, flag or no flag — it has no
 * enable switch of its own (see its own javadoc). Its default stream set (ATTITUDE/GLOBAL_POSITION_INT/
 * VFR_HUD/RC_CHANNELS/GPS_RAW_INT/BATTERY_STATUS) overlaps six of Mechanism A's own nine default
 * on-connect messages, {@code VFR_HUD} among them — so the "baseline" connection below is never
 * actually starved of {@code VFR_HUD} the way it was before P2 existed; both {@link #observeP2OnlyBaseline}
 * and this class's assertions were rewritten to prove the thing that is still true: Mechanism A, once
 * enabled, adds streams P2 does not already provide. {@code SERVO_OUTPUT_RAW} (message 36) is the proof
 * message — one of Mechanism A's four P2-exclusive ids (1 SYS_STATUS, 36 SERVO_OUTPUT_RAW, 116
 * SCALED_IMU2, 2 SYSTEM_TIME) that P2's own default set never requests, so seeing it arrive is
 * unambiguous evidence the flag — not P2 — caused it.
 */
class MavlinkSitlOnConnectIntegrationTest {

    private static final int SYSID = 1;
    private static final int HEARTBEAT_MSG_ID = 0;

    /**
     * Mechanism A's default on-connect set (see {@link MavlinkSettings.Onboarding#defaults()}) asks
     * for this at 500ms/2Hz; it is never part of {@link MavlinkSettings.StreamNegotiation#defaults()}
     * (MAVLINK-COMMANDS-PLAN P2), so unlike {@code VFR_HUD} it cannot arrive from P2's own unconditional
     * negotiation. That makes it this test's proof message rather than {@code VFR_HUD}.
     */
    private static final int SERVO_OUTPUT_RAW_MSG_ID = 36;

    /**
     * SERVO_OUTPUT_RAW was requested at 500ms/2Hz nominal; the floor here is deliberately well under
     * that, not tight against it — same reasoning {@code VFR_HUD_MIN_HZ} used before this test was
     * rewritten around P2: the rolling window this reads off decays over real wall-clock time, so a
     * loaded box running this module's whole suite concurrently reads a lower instantaneous rate than
     * a quiet one even when the aircraft is honouring the request exactly. 1.0 (50% of nominal) keeps
     * the assertion meaningful -- it still fails outright if the request did nothing -- while giving
     * the loaded-box case real headroom.
     */
    private static final double SERVO_OUTPUT_RAW_MIN_HZ = 1.0;

    private static final Duration BOOT_BUDGET = Duration.ofSeconds(60);

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void enablingTheFlagAddsMechanismAsOwnStreamsBeyondP2sUnconditionalBaseline() throws Exception {
        assumeTrue(SitlContainer.dockerAvailable(), SitlContainer.NO_DOCKER);
        assumeTrue(SitlContainer.imagePresent(), SitlContainer.NO_IMAGE);

        int port = SitlContainer.freePort();
        String bindKey = MavlinkTelemetrySource.bindKey("127.0.0.1", port);

        try (SitlContainer ignored = SitlContainer.start("on-connect", port, SYSID)) {
            MavlinkMessageInventory.PeerSnapshot baseline = observeP2OnlyBaseline(port, bindKey);

            MavlinkMessageInventory.PeerSnapshot afterRemediation = observeAfterEnablingTheFlag(port, bindKey);

            assertTrue(afterRemediation.messages().size() > baseline.messages().size(),
                    "enabling the flag must add streams beyond P2's own unconditional negotiation -- baseline had "
                            + baseline.messages().size() + ", after had " + afterRemediation.messages().size());
            assertTrue(hzOf(afterRemediation, SERVO_OUTPUT_RAW_MSG_ID) >= SERVO_OUTPUT_RAW_MIN_HZ,
                    "SERVO_OUTPUT_RAW was requested at 500ms/2Hz and must be observed near it once the flag is on "
                            + "(it is Mechanism A-exclusive, never requested by P2), was "
                            + hzOf(afterRemediation, SERVO_OUTPUT_RAW_MSG_ID) + " Hz");
        }
    }

    /**
     * First connection, Mechanism A's flag off (the module default) -- but {@link
     * MavlinkStreamNegotiator} (MAVLINK-COMMANDS-PLAN P2) still fires unconditionally on this
     * connection's claim, so this is a "P2-only" baseline, not a starved one: wave O4's stock-SITL
     * handful of message types plus whichever of P2's own six default streams ArduPilot accepts. What
     * is still true, and still worth proving, is that {@code SERVO_OUTPUT_RAW} -- Mechanism A-exclusive,
     * never one of P2's requested ids -- has not arrived yet.
     */
    private MavlinkMessageInventory.PeerSnapshot observeP2OnlyBaseline(int port, String bindKey) throws Exception {
        MavlinkTelemetrySource source = new MavlinkTelemetrySource(MavlinkSettings.defaults());
        DeviceId deviceId = DeviceId.random();
        try {
            source.open(device(port, deviceId));
            awaitHeartbeat(source, bindKey, BOOT_BUDGET);

            MavlinkMessageInventory.PeerSnapshot baseline = source.gateway(bindKey).messageInventory().snapshot(SYSID);
            assertNotNull(baseline, "the gateway must have recorded something from sysid " + SYSID + " by now");
            assertFalse(baseline.messages().stream().anyMatch(m -> m.messageId() == SERVO_OUTPUT_RAW_MSG_ID),
                    "SERVO_OUTPUT_RAW is Mechanism A-exclusive and must not already be streaming with the flag "
                            + "off, before any remediation: " + names(baseline));
            return baseline;
        } finally {
            // Released before the second connection binds the same port -- see class javadoc.
            source.close(deviceId);
        }
    }

    /**
     * Second connection to the same, still-running SITL, flag on. No parameter is read or written
     * anywhere in this method -- {@link MavlinkConnectRemediator} fires purely off the dispatcher
     * hearing this connection's first {@code HEARTBEAT}.
     */
    private MavlinkMessageInventory.PeerSnapshot observeAfterEnablingTheFlag(int port, String bindKey)
            throws Exception {
        List<MavlinkSettings.Onboarding.MessageRequest> requests =
                MavlinkSettings.Onboarding.defaults().onConnectMessageRequests();
        MavlinkSettings.Onboarding onboardingOn = new MavlinkSettings.Onboarding(
                List.of(), Duration.ofSeconds(3), 2, Duration.ofSeconds(1), 2, true, requests);
        MavlinkSettings settings = MavlinkSettings.defaults().withOnboarding(onboardingOn);
        MavlinkTelemetrySource source = new MavlinkTelemetrySource(settings);
        DeviceId deviceId = DeviceId.random();
        try {
            source.open(device(port, deviceId));
            awaitHeartbeat(source, bindKey, BOOT_BUDGET);

            return awaitMessageCount(source, bindKey, 8, Duration.ofSeconds(90));
        } finally {
            source.close(deviceId);
        }
    }

    /**
     * The inventory is a decaying rolling window, so a newly requested rate needs a few seconds of
     * traffic before it reads as raised -- waiting for the observation rather than sleeping a fixed
     * guess is the point: the assertion is about what actually arrived.
     */
    private static MavlinkMessageInventory.PeerSnapshot awaitMessageCount(
            MavlinkTelemetrySource source, String bindKey, int messageTypes, Duration timeout)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        MavlinkMessageInventory.PeerSnapshot best = null;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(3_000);
            MavlinkMessageInventory.PeerSnapshot snapshot = source.gateway(bindKey).messageInventory().snapshot(SYSID);
            if (snapshot != null && (best == null || snapshot.messages().size() > best.messages().size())) {
                best = snapshot;
            }
            if (snapshot != null && snapshot.messages().size() >= messageTypes
                    && hzOf(snapshot, SERVO_OUTPUT_RAW_MSG_ID) >= SERVO_OUTPUT_RAW_MIN_HZ) {
                return snapshot;
            }
        }
        assertNotNull(best, "sysid " + SYSID + " never appeared in the inventory at all within " + timeout);
        return best;
    }

    /** Waits only for the aircraft to say anything at all -- SITL is already booted for the second connection. */
    private static void awaitHeartbeat(MavlinkTelemetrySource source, String bindKey, Duration timeout)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            MavlinkMessageInventory.PeerSnapshot snapshot = source.gateway(bindKey).messageInventory().snapshot(SYSID);
            if (snapshot != null && snapshot.messages().stream().anyMatch(m -> m.messageId() == HEARTBEAT_MSG_ID)) {
                Thread.sleep(4_000); // let the rate window fill before anything reads a rate off it
                return;
            }
            Thread.sleep(1_000);
        }
        throw new AssertionError("ArduPilot SITL never sent a HEARTBEAT within " + timeout);
    }

    private static double hzOf(MavlinkMessageInventory.PeerSnapshot snapshot, int messageId) {
        return snapshot.messages().stream().filter(m -> m.messageId() == messageId)
                .findFirst().map(MavlinkMessageInventory.MessageRate::hz).orElse(0.0);
    }

    private static String names(MavlinkMessageInventory.PeerSnapshot snapshot) {
        return snapshot.messages().stream().map(m -> "#" + m.messageId()).sorted().toList().toString();
    }

    private static Device device(int port, DeviceId id) {
        return new Device(id, "sitl-on-connect-test", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("mavlink", URI.create("udp://127.0.0.1:" + port),
                        Map.of("sysid", String.valueOf(SYSID))));
    }
}
