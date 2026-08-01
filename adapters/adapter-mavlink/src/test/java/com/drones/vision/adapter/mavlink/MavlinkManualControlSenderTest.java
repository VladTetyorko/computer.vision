package com.drones.vision.adapter.mavlink;

import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.RcChannels;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.port.out.ManualControlLink;

import io.dronefleet.mavlink.MavlinkConnection;
import io.dronefleet.mavlink.MavlinkMessage;
import io.dronefleet.mavlink.common.RcChannelsOverride;
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
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * docs/RC-CONTROL-PHASE1-PLAN.md R3: {@link MavlinkManualControlSender} exercised over real
 * loopback UDP against a {@link FakeVehicle} test double, the same style {@link
 * MavlinkFlightCommanderTest} uses for {@link MavlinkFlightCommander} — heartbeats continuously so
 * {@link MavlinkSocketHub} claims it, then continuously drains every {@code RC_CHANNELS_OVERRIDE}
 * (#70) frame the sender under test transmits into a queue the test can assert against.
 *
 * <p>Tests use the package-private test-seam constructor to inject a fast tick period (well under
 * the real 10-50Hz clamp) so the suite runs quickly and deterministically without asserting an
 * exact wall-clock rate.
 */
class MavlinkManualControlSenderTest {

    private static final long FAST_TICK_MILLIS = 20L;
    private static final int RELEASE_FRAMES = 3;

    @Test
    void constructorRejectsANullTelemetrySource() {
        assertThrows(NullPointerException.class, () -> new MavlinkManualControlSender(null));
    }

    @Test
    void testSeamConstructorRejectsNonPositiveTickPeriodOrReleaseFrameCount() {
        MavlinkTelemetrySource telemetrySource = new MavlinkTelemetrySource();

        assertThrows(IllegalArgumentException.class, () -> new MavlinkManualControlSender(telemetrySource, 0, 3));
        assertThrows(IllegalArgumentException.class, () -> new MavlinkManualControlSender(telemetrySource, 20, 0));
    }

    @Test
    void supportsDelegatesToTheTelemetrySourcesOwnSupportsCheckSoTheTwoCanNeverDisagree() {
        MavlinkTelemetrySource telemetrySource = new MavlinkTelemetrySource();
        MavlinkManualControlSender sender = sender(telemetrySource);
        Device supported = device(14550, DeviceId.random(), Map.of());
        Device wrongProtocol = new Device(DeviceId.random(), "x", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("sim", URI.create("sim://drone"), Map.of()));

        assertEquals(telemetrySource.supports(supported), sender.supports(supported));
        assertTrue(sender.supports(supported));
        assertFalse(sender.supports(wrongProtocol));
    }

    @Test
    void engageRejectsANullDevice() {
        MavlinkManualControlSender sender = sender(new MavlinkTelemetrySource());

        assertThrows(NullPointerException.class, () -> sender.engage(null));
    }

    @Test
    void engageRejectsAnUnsupportedDeviceAndStartsNoThread() {
        MavlinkManualControlSender sender = sender(new MavlinkTelemetrySource());
        Device unsupported = new Device(DeviceId.random(), "x", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("sim", URI.create("sim://drone"), Map.of()));

        assertThrows(IllegalArgumentException.class, () -> sender.engage(unsupported));
        assertNoRcSenderThreads();
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void engageThrowsForAVehicleThatHasNeverTransmittedAndStartsNoThread() throws Exception {
        int port = freePort();
        MavlinkTelemetrySource telemetrySource = new MavlinkTelemetrySource();
        MavlinkManualControlSender sender = sender(telemetrySource);
        DeviceId deviceId = DeviceId.random();
        // Pinned to a sysid nothing ever transmits on -- the hub is active, but this device's own
        // claim never resolves, so its last-source-address stays permanently unknown.
        Device device = device(port, deviceId, Map.of("sysid", "81"));

        try {
            telemetrySource.open(device);
            Thread.sleep(300); // let the hub's read thread spin up; still nothing ever arrives on 81

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> sender.engage(device));
            assertTrue(ex.getMessage().contains("cannot command what you cannot hear"),
                    "expected the reachability rule in the message, got: " + ex.getMessage());
            assertNoRcSenderThreads();
        } finally {
            telemetrySource.close(deviceId);
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void engageStartsAFixedRateSenderThatCarriesSentChannelsToTheVehicleOnTheSharedSocket() throws Exception {
        int port = freePort();
        MavlinkTelemetrySource telemetrySource = new MavlinkTelemetrySource();
        MavlinkManualControlSender sender = sender(telemetrySource);
        DeviceId deviceId = DeviceId.random();
        Device device = device(port, deviceId, Map.of());
        String bindKey = MavlinkTelemetrySource.bindKey("127.0.0.1", port);

        try (FakeVehicle vehicle =
                     FakeVehicle.start(port, 121, MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, MavType.MAV_TYPE_QUADROTOR)) {
            telemetrySource.open(device);
            awaitReachable(telemetrySource, bindKey, deviceId, Duration.ofSeconds(10));

            ManualControlLink link = sender.engage(device);
            assertTrue(link.active());

            RcChannels channels = new RcChannels(List.of(1200, 1300, 1400, 1500, 1600, 1700, 1800, 1900));
            sender.send(link, channels);

            ReceivedOverride received = vehicle.awaitOverrideWhereChan1Is(1200, Duration.ofSeconds(5));
            assertEquals(121, received.frame().targetSystem());
            assertEquals(MavlinkFlightCommander.TARGET_COMPONENT_AUTOPILOT, received.frame().targetComponent());
            assertEquals(1200, received.frame().chan1Raw());
            assertEquals(1300, received.frame().chan2Raw());
            assertEquals(1400, received.frame().chan3Raw());
            assertEquals(1500, received.frame().chan4Raw());
            assertEquals(1600, received.frame().chan5Raw());
            assertEquals(1700, received.frame().chan6Raw());
            assertEquals(1800, received.frame().chan7Raw());
            assertEquals(1900, received.frame().chan8Raw());
            assertEquals(RcChannels.IGNORE, received.frame().chan9Raw());
            assertEquals(RcChannels.IGNORE, received.frame().chan18Raw());

            // The sender must use the hub's own shared socket, never a socket of its own: every
            // frame the vehicle receives must originate from the exact port MavlinkTelemetrySource
            // itself is bound to for this device's stream.
            assertEquals(telemetrySource.socket(bindKey).getLocalPort(), received.sourcePort());

            sender.release(link);
            assertFalse(link.active());
        } finally {
            telemetrySource.close(deviceId);
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void sendWithFewerThanEightChannelsFillsTheRestWithIgnore() throws Exception {
        int port = freePort();
        MavlinkTelemetrySource telemetrySource = new MavlinkTelemetrySource();
        MavlinkManualControlSender sender = sender(telemetrySource);
        DeviceId deviceId = DeviceId.random();
        Device device = device(port, deviceId, Map.of());
        String bindKey = MavlinkTelemetrySource.bindKey("127.0.0.1", port);

        try (FakeVehicle vehicle =
                     FakeVehicle.start(port, 122, MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, MavType.MAV_TYPE_QUADROTOR)) {
            telemetrySource.open(device);
            awaitReachable(telemetrySource, bindKey, deviceId, Duration.ofSeconds(10));

            ManualControlLink link = sender.engage(device);
            sender.send(link, new RcChannels(List.of(1111, 1222, 1333)));

            ReceivedOverride received = vehicle.awaitOverrideWhereChan1Is(1111, Duration.ofSeconds(5));
            assertEquals(1222, received.frame().chan2Raw());
            assertEquals(1333, received.frame().chan3Raw());
            assertEquals(RcChannels.IGNORE, received.frame().chan4Raw());
            assertEquals(RcChannels.IGNORE, received.frame().chan8Raw());

            sender.release(link);
        } finally {
            telemetrySource.close(deviceId);
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void sendIsLatestWinsSoOnlyTheNewestBurstValueReliablyReachesTheWire() throws Exception {
        int port = freePort();
        MavlinkTelemetrySource telemetrySource = new MavlinkTelemetrySource();
        MavlinkManualControlSender sender = sender(telemetrySource);
        DeviceId deviceId = DeviceId.random();
        Device device = device(port, deviceId, Map.of());
        String bindKey = MavlinkTelemetrySource.bindKey("127.0.0.1", port);

        try (FakeVehicle vehicle =
                     FakeVehicle.start(port, 123, MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, MavType.MAV_TYPE_QUADROTOR)) {
            telemetrySource.open(device);
            awaitReachable(telemetrySource, bindKey, deviceId, Duration.ofSeconds(10));

            ManualControlLink link = sender.engage(device);

            // A burst of sends, all far faster than the tick period -- the mailbox is a single
            // slot, so only the last write before a given tick's read can ever reach the wire.
            for (int i = 1; i <= 20; i++) {
                int value = 1000 + i * 10; // 1010, 1020, ..., 1200
                sender.send(link, new RcChannels(List.of(value, value, value, value, value, value, value, value)));
            }

            ReceivedOverride received = vehicle.awaitOverrideWhereChan1Is(1200, Duration.ofSeconds(5));
            assertEquals(1200, received.frame().chan1Raw(), "expected only the latest burst value on the wire");

            sender.release(link);
        } finally {
            telemetrySource.close(deviceId);
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void releaseEmitsAReleaseBurstThenStopsAndIsIdempotent() throws Exception {
        int port = freePort();
        MavlinkTelemetrySource telemetrySource = new MavlinkTelemetrySource();
        MavlinkManualControlSender sender = sender(telemetrySource);
        DeviceId deviceId = DeviceId.random();
        Device device = device(port, deviceId, Map.of());
        String bindKey = MavlinkTelemetrySource.bindKey("127.0.0.1", port);

        try (FakeVehicle vehicle =
                     FakeVehicle.start(port, 124, MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, MavType.MAV_TYPE_QUADROTOR)) {
            telemetrySource.open(device);
            awaitReachable(telemetrySource, bindKey, deviceId, Duration.ofSeconds(10));

            ManualControlLink link = sender.engage(device);
            sender.send(link, new RcChannels(List.of(1500, 1500, 1500, 1500, 1500, 1500, 1500, 1500)));
            vehicle.awaitOverrideWhereChan1Is(1500, Duration.ofSeconds(5)); // confirm real values flowed first

            sender.release(link);
            assertFalse(link.active());

            ReceivedOverride releaseFrame = vehicle.awaitOverrideWhereChan1Is(RcChannels.RELEASE, Duration.ofSeconds(5));
            assertEquals(0, releaseFrame.frame().chan1Raw());
            assertEquals(0, releaseFrame.frame().chan2Raw());
            assertEquals(0, releaseFrame.frame().chan3Raw());
            assertEquals(0, releaseFrame.frame().chan4Raw());
            assertEquals(0, releaseFrame.frame().chan5Raw());
            assertEquals(0, releaseFrame.frame().chan6Raw());
            assertEquals(0, releaseFrame.frame().chan7Raw());
            assertEquals(0, releaseFrame.frame().chan8Raw());

            // release() only returns once the sender thread has actually stopped (bounded join),
            // so no further frame can possibly follow -- not even another release-sentinel one.
            vehicle.clearReceived();
            Thread.sleep(FAST_TICK_MILLIS * 5);
            assertNull(vehicle.pollOverride(Duration.ofMillis(50)), "expected no frames once release() has returned");

            // Idempotent: a second release on an already-released link is a safe no-op.
            sender.release(link);
            assertFalse(link.active());
            assertNull(vehicle.pollOverride(Duration.ofMillis(200)), "a repeat release() must not emit anything");

            // send() after release must also be a no-op -- no exception, no frame.
            sender.send(link, new RcChannels(List.of(1600, 1600, 1600, 1600, 1600, 1600, 1600, 1600)));
            assertNull(vehicle.pollOverride(Duration.ofMillis(200)), "send() after release() must be a no-op");
        } finally {
            telemetrySource.close(deviceId);
        }
    }

    @Test
    void sendRejectsALinkNotCreatedByThisPort() {
        MavlinkManualControlSender sender = sender(new MavlinkTelemetrySource());
        ManualControlLink foreignLink = () -> true;

        assertThrows(IllegalArgumentException.class,
                () -> sender.send(foreignLink, new RcChannels(List.of(1500))));
    }

    @Test
    void releaseRejectsALinkNotCreatedByThisPort() {
        MavlinkManualControlSender sender = sender(new MavlinkTelemetrySource());
        ManualControlLink foreignLink = () -> true;

        assertThrows(IllegalArgumentException.class, () -> sender.release(foreignLink));
    }

    private static MavlinkManualControlSender sender(MavlinkTelemetrySource telemetrySource) {
        return new MavlinkManualControlSender(telemetrySource, FAST_TICK_MILLIS, RELEASE_FRAMES);
    }

    private static void assertNoRcSenderThreads() {
        boolean anyRcThread = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().startsWith("mavlink-rc-"));
        assertFalse(anyRcThread, "expected no mavlink-rc-* sender thread to have been started");
    }

    private static Device device(int port, DeviceId id, Map<String, String> options) {
        return new Device(id, "manual-control-test-device", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("mavlink", URI.create("udp://127.0.0.1:" + port), options));
    }

    private static void awaitReachable(MavlinkTelemetrySource source, String bindKey, DeviceId deviceId,
                                        Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            MavlinkSocketHub.CommandTarget target = source.commandTarget(bindKey, deviceId);
            if (target != null && target.sourceAddress() != null) {
                return;
            }
            Thread.sleep(50);
        }
        fail("expected device " + deviceId + " to become reachable on " + bindKey + " within " + timeout);
    }

    private static int freePort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** One received {@code RC_CHANNELS_OVERRIDE}, paired with the UDP source port it arrived from. */
    private record ReceivedOverride(RcChannelsOverride frame, int sourcePort) {
    }

    /**
     * A minimal fake aircraft: heartbeats continuously (~5Hz, like a real feed transmitter or SITL
     * instance -- see {@link MavlinkFlightCommanderTest}'s own {@code FakeVehicle} for why a
     * one-shot heartbeat is not enough) so {@link MavlinkSocketHub} claims and labels it, and
     * concurrently drains every {@code RC_CHANNELS_OVERRIDE} frame it receives into a queue the
     * test can assert against, one {@link MavlinkConnection} shared by both directions exactly as
     * the sibling fake vehicle does.
     */
    private static final class FakeVehicle implements AutoCloseable {
        private static final long HEARTBEAT_PERIOD_MILLIS = 200L;

        private final DatagramSocket socket;
        private final MavlinkUdpInputStream input;
        private final MavlinkConnection connection;
        private final int sysid;
        private final AtomicBoolean stopped = new AtomicBoolean(false);
        private final Thread heartbeatThread;
        private final Thread readerThread;
        private final BlockingQueue<ReceivedOverride> overrides = new LinkedBlockingQueue<>();

        private FakeVehicle(DatagramSocket socket, MavlinkUdpInputStream input, MavlinkConnection connection,
                             int sysid, MavAutopilot autopilot, MavType mavType) {
            this.socket = socket;
            this.input = input;
            this.connection = connection;
            this.sysid = sysid;
            this.heartbeatThread = new Thread(() -> heartbeatLoop(autopilot, mavType), "fake-vehicle-heartbeat-" + sysid);
            this.heartbeatThread.setDaemon(true);
            this.readerThread = new Thread(this::readerLoop, "fake-vehicle-reader-" + sysid);
            this.readerThread.setDaemon(true);
        }

        static FakeVehicle start(int gatewayPort, int sysid, MavAutopilot autopilot, MavType mavType) throws IOException {
            DatagramSocket socket = new DatagramSocket(0);
            MavlinkUdpInputStream input = new MavlinkUdpInputStream(socket);
            MavlinkConnection connection = MavlinkConnection.create(input,
                    new MavlinkUdpOutputStream(socket, InetAddress.getByName("127.0.0.1"), gatewayPort));
            FakeVehicle vehicle = new FakeVehicle(socket, input, connection, sysid, autopilot, mavType);
            vehicle.heartbeatThread.start();
            vehicle.readerThread.start();
            return vehicle;
        }

        private void heartbeatLoop(MavAutopilot autopilot, MavType mavType) {
            while (!stopped.get()) {
                try {
                    Heartbeat heartbeat = Heartbeat.builder()
                            .type(mavType)
                            .autopilot(autopilot)
                            .baseMode(MavModeFlag.MAV_MODE_FLAG_SAFETY_ARMED, MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED)
                            .customMode(0)
                            .systemStatus(MavState.MAV_STATE_ACTIVE)
                            .mavlinkVersion(3)
                            .build();
                    connection.send2(sysid, 1, heartbeat);
                    Thread.sleep(HEARTBEAT_PERIOD_MILLIS);
                } catch (IOException e) {
                    return; // socket closing (or closed) -- stop quietly, see close()
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        /** Drains every inbound message, queuing only {@code RC_CHANNELS_OVERRIDE} payloads. */
        private void readerLoop() {
            while (!stopped.get()) {
                try {
                    MavlinkMessage<?> message = connection.next();
                    if (message.getPayload() instanceof RcChannelsOverride override) {
                        int sourcePort = input.lastSourceAddress() == null ? -1 : input.lastSourceAddress().getPort();
                        overrides.offer(new ReceivedOverride(override, sourcePort));
                    }
                } catch (IOException e) {
                    return; // socket closing -- stop quietly, see close()
                }
            }
        }

        /** Blocks (bounded by {@code timeout}) until a frame with the given {@code chan1Raw} arrives. */
        ReceivedOverride awaitOverrideWhereChan1Is(int expectedChan1, Duration timeout) throws InterruptedException {
            long deadlineNanos = System.nanoTime() + timeout.toNanos();
            while (true) {
                long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                if (remainingMillis <= 0) {
                    break;
                }
                ReceivedOverride next = overrides.poll(remainingMillis, TimeUnit.MILLISECONDS);
                if (next != null && next.frame().chan1Raw() == expectedChan1) {
                    return next;
                }
            }
            throw new AssertionError("expected an RC_CHANNELS_OVERRIDE with chan1Raw=" + expectedChan1
                    + " within " + timeout);
        }

        /** Non-blocking-ish poll (bounded by {@code timeout}) for the next queued override, or {@code null}. */
        ReceivedOverride pollOverride(Duration timeout) throws InterruptedException {
            return overrides.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        void clearReceived() {
            overrides.clear();
        }

        @Override
        public void close() {
            stopped.set(true);
            socket.close();
            try {
                heartbeatThread.join(Duration.ofSeconds(2).toMillis());
                readerThread.join(Duration.ofSeconds(2).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
