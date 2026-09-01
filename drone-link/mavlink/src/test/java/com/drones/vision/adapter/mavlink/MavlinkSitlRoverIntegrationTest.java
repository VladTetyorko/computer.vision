package com.drones.vision.adapter.mavlink;

import com.drones.vision.flight.domain.model.CommandResult;
import com.drones.vision.flight.domain.model.RcChannels;
import com.drones.vision.flight.domain.port.ManualControlLink;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.FlightState;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.warehouse.domain.model.Device;

import io.dronefleet.mavlink.MavlinkConnection;
import io.dronefleet.mavlink.MavlinkMessage;
import io.dronefleet.mavlink.common.CommandLong;
import io.dronefleet.mavlink.common.MavCmd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * docs/plans/active/FLEET-RADIO-PLAN.md R7 — the "test half" the plan's own infra half (already
 * shipped: {@code infra/sitl/} can boot a real ArduRover) explicitly left open. F11 ("no rover or
 * boat is ever tested against real firmware") is not closed until this exists and runs.
 *
 * <p>Drives a genuine ArduRover SITL instance (docker-and-image gated exactly like {@link
 * MavlinkSitlSmokeIntegrationTest}/{@link MavlinkSitlReturnHomeIntegrationTest} — see {@link
 * SitlContainer}) through arm → mode change → RC override, asserting R1's completed rover mode
 * table and R3's extension-channel fix against real firmware, not this module's own {@link
 * MavlinkFeedTransmitter} simulator or the unit-level {@code FlightModesTest}/{@code
 * RcChannelsTest} fixtures those two waves already shipped.
 *
 * <h2>What each phase actually proves, honestly</h2>
 * <ol>
 *   <li><b>Strong.</b> The rover mode table resolved, not the copter one. This reads the real
 *       vehicle's own {@code HEARTBEAT.type} (via {@link MavlinkGateway.CommandTarget#mavType()})
 *       and feeds that live value into {@link FlightModes#selectableModes}, asserting {@code
 *       "Dock"} is in the result — a copter table can never contain Dock, so this is a genuine
 *       proof that ArduRover's own heartbeat drove table selection, not a hardcoded {@code 10}
 *       asserted in isolation from what the aircraft actually said.</li>
 *   <li><b>Strong.</b> A commanded mode change actually takes on the vehicle. This commands
 *       {@code "Circle"} — one of R1's own three newly-added rover modes, not a mode the pre-R1
 *       table already had — and confirms it by reading the vehicle's <em>own subsequent
 *       heartbeat</em> {@code custom_mode} back through the normal telemetry ingest path, not by
 *       trusting the command's {@code ACCEPTED} ack (an ack only proves the vehicle received the
 *       command, never that it changed anything — see this class's own javadoc on
     *   {@code MavlinkFlightCommander.send}).</li>
 *   <li><b>Strong, with one honestly-scoped caveat.</b> An RC override on an <em>extension</em>
 *       channel (9–16 — R3/F3/F4's exact bug) reaches the vehicle. Verified by reading the
 *       vehicle's own {@code RC_CHANNELS} (#65) telemetry back over a <b>second, independent</b>
 *       MAVLink connection opened straight to SITL's own {@code serial2} control port ({@link
 *       SitlContainer#serial2Port()}) — a channel this platform's own production code never opens
 *       or reads — and confirming {@code chan9Raw} changes from whatever it read before the
 *       override to the exact value this test's override placed there. This is the strongest
 *       assertion available from outside the aircraft: it proves the override changed the
 *       vehicle's own belief about its RC input on an extension channel specifically, which is
 *       exactly what was silently false before R3. The one thing it does <b>not</b> prove is that
 *       an aux function bound to CH9 (an {@code RCx_OPTION}) would fire — this module's own
 *       Gotchas document that ArduPilot's aux-function numbering is deliberately not tracked here
     *   ("this adapter keeps no table of RC aux functions, on purpose"), so asserting on one from
 *       this test would mean asserting on a guessed magic number this codebase has explicitly
 *       chosen not to own, not on anything R3 actually changed.</li>
 * </ol>
 *
 * <p>Uses {@code SITL_SPEEDUP=5}, the same as {@link MavlinkSitlReturnHomeIntegrationTest}, purely
 * to shorten the EKF/GPS-settle wait the autofly arm sequence pays regardless of vehicle kind — a
 * rover in MANUAL needs no GPS fix at all (see {@code infra/sitl/README.md}), but the settle delay
 * happens before that mode is even reached.
 */
class MavlinkSitlRoverIntegrationTest {

    private static final int SITL_SPEEDUP = 5;

    /** Arbitrary, distinct from the platform's own {@code SysId} — this vehicle exists on this port for this test alone. */
    private static final int SYSID = 55;

    /** MAVLink {@code common.xml} {@code RC_CHANNELS} message id — verified against the bundled
     * {@code io.dronefleet.mavlink} library's own {@code @MavlinkMessageInfo(id=65, ...)} annotation
     * on {@link io.dronefleet.mavlink.common.RcChannels}, not taken on faith. */
    private static final int RC_CHANNELS_MESSAGE_ID = 65;

    /** This test's own throwaway GCS identity for the independent {@link RcEcho} connection —
     * distinct from {@code MavlinkNode.groundStation()}'s 255/190 so a log line can never confuse
     * the two connections this test has open onto the same vehicle. */
    private static final int ECHO_GCS_SYSID = 253;
    private static final int ECHO_GCS_COMPID = 191;

    /** The extension-channel value this test's own RC override places on CH9 — comfortably inside
     * {@code [MIN_MICROS, MAX_MICROS]} and nowhere near a value ArduPilot would report by chance. */
    private static final int OVERRIDE_MICROS = 1900;

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void aRealArduRoverResolvesItsOwnModeTableAndAcceptsModeChangeAndExtensionChannelOverride() throws Exception {
        assumeTrue(SitlContainer.dockerAvailable(), SitlContainer.NO_DOCKER);
        assumeTrue(SitlContainer.imagePresent(), SitlContainer.NO_IMAGE);

        int port = SitlContainer.freePort();
        MavlinkTelemetrySource source = new MavlinkTelemetrySource();
        MavlinkFlightCommander commander = new MavlinkFlightCommander(source);
        MavlinkManualControlSender rcSender = new MavlinkManualControlSender(source, MavlinkSettings.defaults().rc());
        DeviceId deviceId = DeviceId.random();
        try (SitlContainer sitl = SitlContainer.start("rover", port, SYSID, SITL_SPEEDUP, SitlContainer.VEHICLE_ROVER)) {
            Device device = new Device(deviceId, "sitl-rover-test", Set.of(Capability.TELEMETRY),
                    new StreamDescriptor("mavlink", URI.create("udp://0.0.0.0:" + port), Map.of()));
            LatestSampleCollector collector = LatestSampleCollector.subscribeTo(source.open(device));

            // Phase 1: the rover's own autofly routine arms in MANUAL and holds -- wait for a
            // genuinely armed aircraft (speedup 5: typically well under a minute of wall clock).
            collector.awaitFlightState(Duration.ofSeconds(150),
                    fs -> Boolean.TRUE.equals(fs.armed()),
                    "an armed FlightState from the rover's autofly routine (MANUAL, held)");

            // -------- Assertion 1: the ROVER mode table resolved, Dock included --------
            String bindKey = source.bindKeyFor(device);
            MavlinkGateway.CommandTarget target = source.commandTarget(bindKey, deviceId);
            assertNotNull(target, "expected a resolved command target once real telemetry has been heard");
            assertNotNull(target.mavType(), "expected the real HEARTBEAT to have carried a MAV_TYPE by now");
            assertEquals(10, target.mavType(), "ArduRover's own HEARTBEAT.type must be GROUND_ROVER (10) -- "
                    + "if this fails, SITL sent a different MAV_TYPE than the one this whole test assumes, and "
                    + "every assertion below would be exercising the wrong vehicle family");
            List<String> roverModes = FlightModes.selectableModes(FlightModes.AUTOPILOT_ARDUPILOTMEGA, target.mavType());
            assertTrue(roverModes.contains("Dock"), "expected the ROVER mode table (Dock/Circle/Initialising, "
                    + "FLEET-RADIO R1) to be selected for a real ArduRover's own live MAV_TYPE=" + target.mavType()
                    + "; got " + roverModes + " -- a copter table would never contain Dock, so this failing means "
                    + "the wrong table resolved for a genuine rover heartbeat");

            // -------- Assertion 2: a mode change actually takes, confirmed by the vehicle's own subsequent heartbeat --------
            // "Circle" (not an old mode already in the table before R1) is commanded deliberately,
            // so a passing test ties R1's own newly-added modes to a live round trip, not just the
            // pre-existing "Manual"/"Hold" pair every earlier SITL test already exercised.
            commandModeUntilAccepted(commander, device, "Circle");
            collector.awaitFlightState(Duration.ofSeconds(45),
                    fs -> "Circle".equals(fs.mode()),
                    "telemetry reporting mode=Circle (one of R1's own three added rover modes) after an "
                            + "ACCEPTED command -- an ACK alone would only prove the vehicle received the "
                            + "command, never that it acted on it");

            // -------- Assertion 3: an RC override on an EXTENSION channel (9-16) reaches the vehicle --------
            try (RcEcho echo = RcEcho.connectTo(sitl.serial2Port(), SYSID)) {
                echo.requestRcChannels();

                int baseline = echo.awaitChannel9(Duration.ofSeconds(15), value -> true);
                assertNotEquals(-1, baseline, "never received a single real RC_CHANNELS (#65) message on the "
                        + "independent serial2 connection within 15s -- this is a test-infrastructure failure "
                        + "(Mechanism A's MAV_CMD_SET_MESSAGE_INTERVAL not honoured on this channel), not "
                        + "evidence about R3 either way");
                assertNotEquals(OVERRIDE_MICROS, baseline, "chan9Raw already read " + OVERRIDE_MICROS
                        + " before any override was sent -- the equality check below would prove nothing about "
                        + "causation; rerun with a different OVERRIDE_MICROS value");

                ManualControlLink link = rcSender.engage(device);
                try {
                    // Base channels released/neutral so this override frame looks like a real
                    // operator's stick input, not a synthetic single-channel probe; channel 9 (index
                    // 8, one-based channel 9) is the extension channel under test.
                    RcChannels channels = new RcChannels(Arrays.asList(
                            1500, 1500, RcChannels.RELEASE, RcChannels.RELEASE, RcChannels.RELEASE,
                            RcChannels.RELEASE, RcChannels.RELEASE, RcChannels.RELEASE, OVERRIDE_MICROS));
                    rcSender.send(link, channels);

                    int observed = echo.awaitChannel9(Duration.ofSeconds(20), value -> value == OVERRIDE_MICROS);
                    assertEquals(OVERRIDE_MICROS, observed, "expected the vehicle's OWN subsequent RC_CHANNELS "
                            + "telemetry -- read over an independent connection (serial2), never touched by "
                            + "this platform's own ingest -- to echo back the value this RC override placed on "
                            + "extension channel 9; a mismatch means the extension channel never actually "
                            + "reached the aircraft (F3/F4, R3)");
                } finally {
                    rcSender.release(link);
                }
            }
        } finally {
            source.close(deviceId);
        }
    }

    /**
     * Real firmware refuses a mode change until its own preconditions hold (a fresh heartbeat may
     * still be settling its EKF); retries, exactly like {@link MavlinkSitlReturnHomeIntegrationTest}'s
     * own documented "honest flakiness posture" — a denial or lost-datagram {@code NO_ACK} both just
     * mean "not yet" here, never a transport bug this class exists to paper over.
     */
    private static void commandModeUntilAccepted(MavlinkFlightCommander commander, Device device, String modeName)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        CommandResult result = null;
        String lastRefusal = null;
        while (System.nanoTime() < deadline) {
            try {
                result = commander.setMode(device, modeName);
                if (result == CommandResult.ACCEPTED) {
                    return;
                }
                lastRefusal = "NO_ACK (datagram or ack lost)";
            } catch (IllegalStateException refused) {
                lastRefusal = refused.getMessage();
            }
            Thread.sleep(3_000);
        }
        fail("SITL never ACCEPTED a mode change to " + modeName + " within the retry window; last refusal: "
                + lastRefusal);
    }

    /**
     * A second, independent MAVLink connection straight to SITL's own {@code serial2} control port
     * (see {@link SitlContainer#serial2Port()}) — the same "internal test-harness GCS" role {@code
     * infra/sitl/autofly.py} plays against {@code serial1}, just read/request-only and opened from
     * this test instead. Nothing in {@code adapter-mavlink} production code ever opens or reads
     * this port; it exists purely so this test can observe the vehicle's own belief about its RC
     * input without relying on anything this module's own ingest path decodes.
     */
    private static final class RcEcho implements AutoCloseable {
        private final Socket socket;
        private final MavlinkConnection connection;
        private final int vehicleSysid;

        private RcEcho(Socket socket, MavlinkConnection connection, int vehicleSysid) {
            this.socket = socket;
            this.connection = connection;
            this.vehicleSysid = vehicleSysid;
        }

        static RcEcho connectTo(int port, int vehicleSysid) throws IOException, InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            IOException lastError = null;
            while (System.nanoTime() < deadline) {
                try {
                    Socket socket = new Socket();
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
                    socket.setSoTimeout(2000);
                    MavlinkConnection connection = MavlinkConnection.create(socket.getInputStream(), socket.getOutputStream());
                    return new RcEcho(socket, connection, vehicleSysid);
                } catch (IOException e) {
                    lastError = e;
                    Thread.sleep(500);
                }
            }
            throw new IOException("could not open an independent MAVLink connection to SITL's own serial2 "
                    + "control port " + port + " within 15s", lastError);
        }

        /**
         * Mechanism A ({@code adapter-mavlink}'s own {@code MavlinkConnectRemediator} does exactly
         * this on the platform's own channel) sent over THIS connection only: ArduPilot streams
         * almost nothing to a freshly connected channel until explicitly asked (see this module's
         * MODULE.md Gotchas), and this codebase's own grounding already established
         * {@code MAV_CMD_SET_MESSAGE_INTERVAL} is the only mechanism that works on this firmware.
         */
        void requestRcChannels() throws IOException {
            CommandLong request = CommandLong.builder()
                    .targetSystem(vehicleSysid)
                    .targetComponent(MavlinkFlightCommander.TARGET_COMPONENT_AUTOPILOT)
                    .command(MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL)
                    .confirmation(0)
                    .param1(RC_CHANNELS_MESSAGE_ID)
                    .param2(50_000f) // microseconds -- 20Hz, MessageIntervalService's own documented convention
                    .build();
            connection.send2(ECHO_GCS_SYSID, ECHO_GCS_COMPID, request);
        }

        /**
         * Reads real frames off this connection until a genuine {@code RC_CHANNELS} (#65) message's
         * {@code chan9Raw} satisfies {@code until}, or {@code timeout} elapses.
         *
         * @return the last {@code chan9Raw} value actually observed, or {@code -1} if not a single
         *         {@code RC_CHANNELS} message arrived at all -- so a caller can tell "never got a
         *         message" apart from "got messages, wrong value" instead of one opaque timeout
         */
        int awaitChannel9(Duration timeout, IntPredicate until) throws IOException {
            long deadline = System.nanoTime() + timeout.toNanos();
            int lastSeen = -1;
            while (System.nanoTime() < deadline) {
                MavlinkMessage<?> message;
                try {
                    message = connection.next();
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (message.getPayload() instanceof io.dronefleet.mavlink.common.RcChannels rc) {
                    lastSeen = rc.chan9Raw();
                    if (until.test(lastSeen)) {
                        return lastSeen;
                    }
                }
            }
            return lastSeen;
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best-effort cleanup only
            }
        }
    }

    /** Tracks the freshest sample; callers await a {@code FlightState} predicate. Deliberately
     * duplicated per SITL test file rather than shared -- see {@link MavlinkSitlReturnHomeIntegrationTest}'s
     * identical copy; this module has no shared home for it today and extracting one is outside this wave's scope. */
    private static final class LatestSampleCollector implements Flow.Subscriber<Telemetry> {
        private final AtomicReference<Telemetry> latest = new AtomicReference<>();
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private final Object monitor = new Object();

        static LatestSampleCollector subscribeTo(Flow.Publisher<Telemetry> publisher) {
            LatestSampleCollector collector = new LatestSampleCollector();
            publisher.subscribe(collector);
            return collector;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(Telemetry item) {
            latest.set(item);
            synchronized (monitor) {
                monitor.notifyAll();
            }
        }

        @Override
        public void onError(Throwable throwable) {
            error.set(throwable);
            synchronized (monitor) {
                monitor.notifyAll();
            }
        }

        @Override
        public void onComplete() {
        }

        void awaitFlightState(Duration timeout, Predicate<FlightState> predicate, String expectation)
                throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            synchronized (monitor) {
                while (System.currentTimeMillis() < deadline && error.get() == null) {
                    Telemetry sample = latest.get();
                    if (sample != null && sample.flightState() != null && predicate.test(sample.flightState())) {
                        return;
                    }
                    monitor.wait(500);
                }
            }
            if (error.get() != null) {
                fail("telemetry publisher errored while awaiting " + expectation + ": " + error.get());
            }
            Telemetry last = latest.get();
            fail("timed out (" + timeout + ") awaiting " + expectation + "; last flight state: "
                    + (last == null ? "no sample at all" : String.valueOf(last.flightState())));
        }
    }
}
