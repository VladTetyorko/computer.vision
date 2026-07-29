package com.drones.vision.adapter.mavlink;

import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.CommandResult;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.StreamDescriptor;

import io.dronefleet.mavlink.MavlinkConnection;
import io.dronefleet.mavlink.MavlinkMessage;
import io.dronefleet.mavlink.common.CommandAck;
import io.dronefleet.mavlink.common.CommandLong;
import io.dronefleet.mavlink.common.MavCmd;
import io.dronefleet.mavlink.common.MavResult;
import io.dronefleet.mavlink.minimal.Heartbeat;
import io.dronefleet.mavlink.minimal.MavAutopilot;
import io.dronefleet.mavlink.minimal.MavModeFlag;
import io.dronefleet.mavlink.minimal.MavState;
import io.dronefleet.mavlink.minimal.MavType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * docs/DRONE-INFRA-PLAN.md I-e Stage 1: {@link MavlinkFlightCommander} exercised over real
 * loopback UDP against a {@link FakeVehicle} test double that speaks just enough MAVLink to stand
 * in for a real ArduPilot/Betaflight aircraft — heartbeats first (so {@link MavlinkSocketHub}
 * claims and labels it, exactly like a real vehicle), then either decodes and answers the
 * {@code COMMAND_LONG} the commander sends, or deliberately doesn't.
 */
class MavlinkFlightCommanderTest {

    @Test
    void constructorRejectsANullTelemetrySource() {
        assertThrows(NullPointerException.class, () -> new MavlinkFlightCommander(null));
    }

    @Test
    void supportsDelegatesToTheTelemetrySourcesOwnSupportsCheckSoTheTwoCanNeverDisagree() {
        MavlinkTelemetrySource telemetrySource = new MavlinkTelemetrySource();
        MavlinkFlightCommander commander = new MavlinkFlightCommander(telemetrySource);
        Device supported = device(14550, DeviceId.random(), Map.of());
        Device wrongProtocol = new Device(DeviceId.random(), "x", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("sim", URI.create("sim://drone"), Map.of()));

        assertEquals(telemetrySource.supports(supported), commander.supports(supported));
        assertTrue(commander.supports(supported));
        assertFalse(commander.supports(wrongProtocol));
    }

    @Test
    void returnToHomeRejectsANullDevice() {
        MavlinkFlightCommander commander = new MavlinkFlightCommander(new MavlinkTelemetrySource());

        assertThrows(NullPointerException.class, () -> commander.returnToHome(null));
    }

    @Test
    void returnToHomeRejectsAnUnsupportedDevice() {
        MavlinkFlightCommander commander = new MavlinkFlightCommander(new MavlinkTelemetrySource());
        Device unsupported = new Device(DeviceId.random(), "x", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("sim", URI.create("sim://drone"), Map.of()));

        assertThrows(IllegalArgumentException.class, () -> commander.returnToHome(unsupported));
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void returnToHomeThrowsForAVehicleThatHasNeverTransmitted() throws Exception {
        int port = freePort();
        MavlinkTelemetrySource telemetrySource = new MavlinkTelemetrySource();
        MavlinkFlightCommander commander = new MavlinkFlightCommander(telemetrySource);
        DeviceId deviceId = DeviceId.random();
        // Pinned to a sysid nothing ever transmits on -- the hub is active, but this exact
        // device's claim never resolves, so its last-source-address stays permanently unknown.
        Device device = device(port, deviceId, Map.of("sysid", "81"));

        try {
            telemetrySource.open(device);
            Thread.sleep(300); // let the hub's read thread spin up; still nothing ever arrives on 81

            IllegalArgumentException ex =
                    assertThrows(IllegalArgumentException.class, () -> commander.returnToHome(device));
            assertTrue(ex.getMessage().contains("cannot command what you cannot hear"),
                    "expected the Stage 1 reachability rule in the message, got: " + ex.getMessage());
        } finally {
            telemetrySource.close(deviceId);
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void returnToHomeRejectsABetaflightVehicleEvenThoughItsModeTableHasAnRtlEntry() throws Exception {
        int port = freePort();
        MavlinkTelemetrySource telemetrySource = new MavlinkTelemetrySource();
        MavlinkFlightCommander commander = new MavlinkFlightCommander(telemetrySource);
        DeviceId deviceId = DeviceId.random();
        Device device = device(port, deviceId, Map.of());
        String bindKey = MavlinkTelemetrySource.bindKey("127.0.0.1", port);

        try (FakeVehicle vehicle =
                     FakeVehicle.start(port, 82, MavAutopilot.MAV_AUTOPILOT_GENERIC, MavType.MAV_TYPE_QUADROTOR)) {
            telemetrySource.open(device);
            awaitClaimedWithFirmware(telemetrySource, bindKey, deviceId, "generic", Duration.ofSeconds(10));

            IllegalArgumentException ex =
                    assertThrows(IllegalArgumentException.class, () -> commander.returnToHome(device));
            assertTrue(ex.getMessage().contains("Betaflight"), "expected the message to name Betaflight, got: " + ex.getMessage());
        } finally {
            telemetrySource.close(deviceId);
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void sendsAWellFormedCommandLongAndReturnsAcceptedOnAck() throws Exception {
        int port = freePort();
        MavlinkTelemetrySource telemetrySource = new MavlinkTelemetrySource();
        MavlinkFlightCommander commander = new MavlinkFlightCommander(telemetrySource);
        DeviceId deviceId = DeviceId.random();
        Device device = device(port, deviceId, Map.of());
        String bindKey = MavlinkTelemetrySource.bindKey("127.0.0.1", port);

        try (FakeVehicle vehicle =
                     FakeVehicle.start(port, 91, MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, MavType.MAV_TYPE_QUADROTOR)) {
            telemetrySource.open(device);
            awaitClaimedWithFirmware(telemetrySource, bindKey, deviceId, "ardupilot", Duration.ofSeconds(10));

            AtomicReference<CommandLong> received = new AtomicReference<>();
            AtomicReference<Exception> vehicleError = new AtomicReference<>();
            Thread vehicleThread = new Thread(() -> {
                try {
                    CommandLong commandLong = vehicle.awaitCommandLong(Duration.ofSeconds(10));
                    received.set(commandLong);
                    vehicle.replyAck(MavResult.MAV_RESULT_ACCEPTED);
                } catch (Exception e) {
                    vehicleError.set(e);
                }
            }, "fake-vehicle-91");
            vehicleThread.start();

            CommandResult result = commander.returnToHome(device);

            vehicleThread.join(Duration.ofSeconds(10).toMillis());
            assertNull(vehicleError.get(), "vehicle-side listener must not error: " + vehicleError.get());
            CommandLong commandLong = received.get();
            assertNotNull(commandLong, "expected the vehicle to receive a COMMAND_LONG");
            assertEquals(91, commandLong.targetSystem());
            assertEquals(MavlinkFlightCommander.TARGET_COMPONENT_AUTOPILOT, commandLong.targetComponent());
            assertEquals(MavCmd.MAV_CMD_DO_SET_MODE, commandLong.command().entry());
            assertEquals(1.0f, commandLong.param1(), "param1 must be MAV_MODE_FLAG_CUSTOM_MODE_ENABLED");
            assertEquals(6.0f, commandLong.param2(), "param2 must be the ArduPilot copter RTL custom_mode (6)");
            assertEquals(CommandResult.ACCEPTED, result);
        } finally {
            telemetrySource.close(deviceId);
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void throwsIllegalStateExceptionWhenTheVehicleDeniesTheCommand() throws Exception {
        int port = freePort();
        MavlinkTelemetrySource telemetrySource = new MavlinkTelemetrySource();
        MavlinkFlightCommander commander = new MavlinkFlightCommander(telemetrySource);
        DeviceId deviceId = DeviceId.random();
        Device device = device(port, deviceId, Map.of());
        String bindKey = MavlinkTelemetrySource.bindKey("127.0.0.1", port);

        try (FakeVehicle vehicle =
                     FakeVehicle.start(port, 92, MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, MavType.MAV_TYPE_QUADROTOR)) {
            telemetrySource.open(device);
            awaitClaimedWithFirmware(telemetrySource, bindKey, deviceId, "ardupilot", Duration.ofSeconds(10));

            AtomicReference<Exception> vehicleError = new AtomicReference<>();
            Thread vehicleThread = new Thread(() -> {
                try {
                    vehicle.awaitCommandLong(Duration.ofSeconds(10));
                    vehicle.replyAck(MavResult.MAV_RESULT_DENIED);
                } catch (Exception e) {
                    vehicleError.set(e);
                }
            }, "fake-vehicle-92");
            vehicleThread.start();

            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> commander.returnToHome(device));
            assertTrue(ex.getMessage().contains("MAV_RESULT_DENIED"),
                    "expected the ack's result name in the message, got: " + ex.getMessage());

            vehicleThread.join(Duration.ofSeconds(10).toMillis());
            assertNull(vehicleError.get(), "vehicle-side listener must not error: " + vehicleError.get());
        } finally {
            telemetrySource.close(deviceId);
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void returnsNoAckWhenTheVehicleNeverReplies() throws Exception {
        int port = freePort();
        MavlinkTelemetrySource telemetrySource = new MavlinkTelemetrySource();
        MavlinkFlightCommander commander = new MavlinkFlightCommander(telemetrySource);
        DeviceId deviceId = DeviceId.random();
        Device device = device(port, deviceId, Map.of());
        String bindKey = MavlinkTelemetrySource.bindKey("127.0.0.1", port);

        try (FakeVehicle vehicle =
                     FakeVehicle.start(port, 93, MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, MavType.MAV_TYPE_QUADROTOR)) {
            telemetrySource.open(device);
            awaitClaimedWithFirmware(telemetrySource, bindKey, deviceId, "ardupilot", Duration.ofSeconds(10));

            // Drains the COMMAND_LONG (proving it really was sent) but deliberately never replies.
            Thread vehicleThread = new Thread(() -> {
                try {
                    vehicle.awaitCommandLong(Duration.ofSeconds(10));
                } catch (Exception ignored) {
                    // best-effort drain only -- this test only cares about the commander's own timeout
                }
            }, "fake-vehicle-93");
            vehicleThread.start();

            CommandResult result = commander.returnToHome(device);

            assertEquals(CommandResult.NO_ACK, result);
            vehicleThread.join(Duration.ofSeconds(10).toMillis());
        } finally {
            telemetrySource.close(deviceId);
        }
    }

    private static Device device(int port, DeviceId id, Map<String, String> options) {
        return new Device(id, "flight-commander-test-device", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("mavlink", URI.create("udp://127.0.0.1:" + port), options));
    }

    private static void awaitClaimedWithFirmware(MavlinkTelemetrySource source, String bindKey, DeviceId deviceId,
                                                  String expectedFirmware, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            MavlinkSocketHub.CommandTarget target = source.commandTarget(bindKey, deviceId);
            if (target != null && expectedFirmware.equals(target.firmware())) {
                return;
            }
            Thread.sleep(100);
        }
        fail("expected device " + deviceId + " to be claimed with firmware \"" + expectedFirmware
                + "\" on " + bindKey + " within " + timeout);
    }

    private static int freePort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * A minimal fake aircraft: heartbeats continuously (~5Hz, like a real feed transmitter or
     * SITL instance) from the moment it starts, so {@link MavlinkSocketHub} claims and labels it
     * exactly like a real vehicle regardless of exactly when the gateway's socket happens to bind
     * relative to this test double starting — a single, one-shot heartbeat would race that bind
     * and could be lost on plain UDP with nothing to retry it. Can then either decode the {@code
     * COMMAND_LONG} a {@link MavlinkFlightCommander} sends back, or ignore it, per test scenario.
     */
    private static final class FakeVehicle implements AutoCloseable {
        private static final long HEARTBEAT_PERIOD_MILLIS = 200L;

        private final DatagramSocket socket;
        private final MavlinkConnection connection;
        private final int sysid;
        private final Object writeLock = new Object();
        private final AtomicBoolean stopped = new AtomicBoolean(false);
        private final Thread heartbeatThread;

        private FakeVehicle(DatagramSocket socket, MavlinkConnection connection, int sysid,
                             MavAutopilot autopilot, MavType mavType) {
            this.socket = socket;
            this.connection = connection;
            this.sysid = sysid;
            this.heartbeatThread = new Thread(() -> heartbeatLoop(autopilot, mavType), "fake-vehicle-heartbeat-" + sysid);
            this.heartbeatThread.setDaemon(true);
        }

        static FakeVehicle start(int gatewayPort, int sysid, MavAutopilot autopilot, MavType mavType) throws IOException {
            DatagramSocket socket = new DatagramSocket(0);
            MavlinkConnection connection = MavlinkConnection.create(new MavlinkUdpInputStream(socket),
                    new MavlinkUdpOutputStream(socket, InetAddress.getByName("127.0.0.1"), gatewayPort));
            FakeVehicle vehicle = new FakeVehicle(socket, connection, sysid, autopilot, mavType);
            vehicle.heartbeatThread.start();
            return vehicle;
        }

        private void heartbeatLoop(MavAutopilot autopilot, MavType mavType) {
            while (!stopped.get()) {
                try {
                    sendHeartbeat(autopilot, mavType);
                    Thread.sleep(HEARTBEAT_PERIOD_MILLIS);
                } catch (IOException e) {
                    return; // socket closing (or closed) -- stop quietly, see close()
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private void sendHeartbeat(MavAutopilot autopilot, MavType mavType) throws IOException {
            Heartbeat heartbeat = Heartbeat.builder()
                    .type(mavType)
                    .autopilot(autopilot)
                    .baseMode(MavModeFlag.MAV_MODE_FLAG_SAFETY_ARMED, MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED)
                    .customMode(0)
                    .systemStatus(MavState.MAV_STATE_ACTIVE)
                    .mavlinkVersion(3)
                    .build();
            synchronized (writeLock) {
                connection.send2(sysid, 1, heartbeat);
            }
        }

        /** Blocks (bounded by {@code timeout}) until a {@code COMMAND_LONG} arrives, skipping anything else. */
        CommandLong awaitCommandLong(Duration timeout) throws IOException {
            long deadlineNanos = System.nanoTime() + timeout.toNanos();
            while (true) {
                long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                if (remainingMillis <= 0) {
                    break;
                }
                socket.setSoTimeout((int) Math.min(remainingMillis, Integer.MAX_VALUE));
                try {
                    MavlinkMessage<?> message = connection.next();
                    if (message.getPayload() instanceof CommandLong commandLong) {
                        return commandLong;
                    }
                } catch (SocketTimeoutException e) {
                    break;
                }
            }
            throw new AssertionError("expected a COMMAND_LONG within " + timeout);
        }

        /** Guarded by {@link #writeLock} against the concurrently-running heartbeat thread's own writes. */
        void replyAck(MavResult result) throws IOException {
            CommandAck ack = CommandAck.builder()
                    .command(MavCmd.MAV_CMD_DO_SET_MODE)
                    .result(result)
                    .build();
            synchronized (writeLock) {
                connection.send2(sysid, 1, ack);
            }
        }

        @Override
        public void close() {
            stopped.set(true);
            socket.close();
            try {
                heartbeatThread.join(Duration.ofSeconds(2).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
