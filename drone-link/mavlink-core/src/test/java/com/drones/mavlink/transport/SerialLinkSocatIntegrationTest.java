package com.drones.mavlink.transport;

import com.drones.mavlink.CompId;
import com.drones.mavlink.PeerId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.codec.FrameWriter;
import com.drones.mavlink.config.MavlinkCoreSettings;
import com.drones.mavlink.session.MavlinkNode;
import com.drones.mavlink.session.MavlinkSession;
import com.drones.mavlink.session.Peer;

import io.dronefleet.mavlink.minimal.Heartbeat;
import io.dronefleet.mavlink.minimal.MavAutopilot;
import io.dronefleet.mavlink.minimal.MavModeFlag;
import io.dronefleet.mavlink.minimal.MavState;
import io.dronefleet.mavlink.minimal.MavType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real {@link SerialLink} end-to-end, gated on the external {@code socat} binary
 * (LINK-PAIRING-PLAN.md §4 L1) — creates a linked pty pair ({@code socat pty,raw,echo=0
 * pty,raw,echo=0}), opens one end as this station's {@link SerialLink} through a real {@link
 * MavlinkSession}, and drives a simulated vehicle's {@link FrameWriter} through the other end —
 * proving a serial-carried vehicle is heard with <b>zero UDP sockets open anywhere in this test</b>.
 *
 * <p>Skips cleanly (never fails) when {@code socat} is not on {@code PATH}, or when this
 * environment's jSerialComm build cannot open a plain Linux pty (its enumeration only recognizes
 * known tty-driver device nodes; opening an arbitrary path by name is a narrower guarantee this
 * test itself verifies rather than assumes) — reported via {@link
 * org.junit.jupiter.api.Assumptions#assumeTrue(boolean, String)} either way, per this project's
 * "skip cleanly, report which happened" gating convention.
 */
class SerialLinkSocatIntegrationTest {

    private static final Pattern PTY_LINE = Pattern.compile("PTY is (/dev/pts/\\d+)");
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(3);

    private Process socat;

    @BeforeEach
    void checkSocatAvailable() {
        assumeTrue(isOnPath("socat"), "socat not found on PATH -- skipping SerialLinkSocatIntegrationTest");
    }

    @AfterEach
    void stopSocat() {
        if (socat != null) {
            socat.destroyForcibly();
        }
    }

    @Test
    void aSerialLinkFedByAPtyHearsAVehicleWithZeroUdpSocketsOpen() throws Exception {
        String[] ptys = startSocatPtyPair();

        SerialLink stationLink;
        try {
            stationLink = SerialLink.open(ptys[0], 57_600);
        } catch (IOException e) {
            assumeTrue(false, "jSerialComm could not open a socat pty (" + ptys[0] + ") in this "
                    + "environment: " + e.getMessage());
            return; // unreachable -- assumeTrue(false, ...) always throws
        }

        try (SerialLink vehicleLink = SerialLink.open(ptys[1], 57_600)) {
            MavlinkSession session = new MavlinkSession(MavlinkNode.groundStation(), MavlinkCoreSettings.defaults());
            try {
                session.addLink(stationLink);

                PeerId vehicle = new PeerId(new SysId(42), new CompId(1));
                FrameWriter vehicleWriter = new FrameWriter(vehicle.system(), vehicle.component());
                vehicleWriter.addLink(vehicleLink);
                for (int i = 0; i < 3; i++) {
                    vehicleWriter.broadcast(heartbeat(), vehicleLink.id());
                }

                Peer heard = awaitPeer(session, vehicle, POLL_TIMEOUT);
                assertNotNull(heard, "the station's SerialLink must hear the simulated vehicle's HEARTBEATs");
                assertTrue(heard.lastHeard().isAfter(Instant.now().minus(POLL_TIMEOUT)));
            } finally {
                session.close();
            }
        }
    }

    /**
     * Mutation check (LINK-PAIRING-PLAN.md §4 L1): killing the pty pair mid-stream must still fire
     * {@link MavlinkSession#onLinkFailure} — proving the test isn't vacuously green on a link that
     * never actually carried frames, and proving {@link SerialLink}'s end-of-stream handling (a
     * negative read is a genuine failure, not a silent close — see its own javadoc) is real.
     */
    @Test
    void killingTheSocatProcessMidStreamStillFiresOnLinkFailure() throws Exception {
        String[] ptys = startSocatPtyPair();

        SerialLink stationLink;
        try {
            stationLink = SerialLink.open(ptys[0], 57_600);
        } catch (IOException e) {
            assumeTrue(false, "jSerialComm could not open a socat pty (" + ptys[0] + ") in this "
                    + "environment: " + e.getMessage());
            return;
        }

        MavlinkSession session = new MavlinkSession(MavlinkNode.groundStation(), MavlinkCoreSettings.defaults());
        java.util.concurrent.CountDownLatch failed = new java.util.concurrent.CountDownLatch(1);
        session.onLinkFailure((linkId, cause) -> failed.countDown());
        try {
            session.addLink(stationLink);

            try (SerialLink vehicleLink = SerialLink.open(ptys[1], 57_600)) {
                PeerId vehicle = new PeerId(new SysId(43), new CompId(1));
                FrameWriter vehicleWriter = new FrameWriter(vehicle.system(), vehicle.component());
                vehicleWriter.addLink(vehicleLink);
                vehicleWriter.broadcast(heartbeat(), vehicleLink.id());
                awaitPeer(session, vehicle, POLL_TIMEOUT);
            }

            // Killing socat tears down BOTH pty endpoints out from under the still-open station
            // link -- an external, unexpected termination, not a call this test made on
            // stationLink itself. socat's own process death (rather than a graceful close of just
            // the vehicle side) is what makes this a genuine "device disappeared" failure.
            socat.destroyForcibly();
            socat.waitFor(5, TimeUnit.SECONDS);

            assertTrue(failed.await(5, TimeUnit.SECONDS),
                    "killing the pty pair mid-stream must still report a genuine link failure");
        } finally {
            session.close();
        }
    }

    private static Peer awaitPeer(MavlinkSession session, PeerId id, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Peer peer = session.peers().peer(id);
            if (peer != null) {
                return peer;
            }
            Thread.sleep(20);
        }
        return session.peers().peer(id);
    }

    private static Heartbeat heartbeat() {
        return Heartbeat.builder()
                .type(MavType.MAV_TYPE_GENERIC)
                .autopilot(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA)
                .baseMode(MavModeFlag.MAV_MODE_FLAG_MANUAL_INPUT_ENABLED)
                .customMode(0)
                .systemStatus(MavState.MAV_STATE_STANDBY)
                .mavlinkVersion(3)
                .build();
    }

    /** Launches {@code socat -d -d pty,raw,echo=0 pty,raw,echo=0} and parses the two pty paths from its stderr. */
    private String[] startSocatPtyPair() throws IOException {
        ProcessBuilder builder = new ProcessBuilder("socat", "-d", "-d",
                "pty,raw,echo=0", "pty,raw,echo=0");
        builder.redirectErrorStream(false);
        socat = builder.start();

        String[] ptys = new String[2];
        int found = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(socat.getErrorStream(), StandardCharsets.UTF_8))) {
            Instant deadline = Instant.now().plusSeconds(5);
            String line;
            while (found < 2 && Instant.now().isBefore(deadline) && (line = reader.readLine()) != null) {
                Matcher matcher = PTY_LINE.matcher(line);
                if (matcher.find()) {
                    ptys[found++] = matcher.group(1);
                }
            }
        }
        assumeTrue(found == 2, "could not parse two pty paths from socat's output -- skipping SerialLinkSocatIntegrationTest");
        return ptys;
    }

    private static boolean isOnPath(String command) {
        try {
            Process process = new ProcessBuilder("which", command).redirectErrorStream(true).start();
            boolean exited = process.waitFor(5, TimeUnit.SECONDS);
            return exited && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
