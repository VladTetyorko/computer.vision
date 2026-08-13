package com.drones.vision.adapter.mavlink;

import com.drones.vision.kernel.Capability;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.perception.domain.model.FeedId;
import com.drones.vision.perception.domain.model.FeedSpec;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.Telemetry;

import io.dronefleet.mavlink.MavlinkConnection;
import io.dronefleet.mavlink.ardupilotmega.Wind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full TX→wire→RX round trip over real loopback UDP sockets — no hardware, no docker: {@link
 * MavlinkFeedTransmitter} flies a two-waypoint loop and {@link MavlinkTelemetrySource} ingests it
 * back, proving a moving position (and draining battery) actually arrives end to end. A third
 * test (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-e) proves a real ardupilotmega-dialect-only {@code WIND}
 * datagram, sent raw alongside the transmitter's own traffic, survives the real UDP path into a
 * {@link Telemetry} sample — confirming {@link MavlinkTelemetryDecoder}'s dialect-selection
 * mechanism note against the real socket/thread path, not just the golden-bytes unit tests. A
 * fourth test proves garbage UDP datagrams interleaved with real traffic never take the RX side
 * down.
 */
class MavlinkRoundTripIntegrationTest {

    private static final String ROUTE = "50.45000,30.52000;50.45200,30.52000"; // ~222m leg, north-south

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aTransmittedRouteArrivesAtTheRxSideAsAMovingBatteryReportingDrone() throws Exception {
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        MavlinkTelemetrySource rxSource = new MavlinkTelemetrySource();
        FeedId feedId = FeedId.random();
        DeviceId rxDeviceId = DeviceId.random();
        int port = freePort();

        try {
            FeedSpec spec = new FeedSpec("mavlink", URI.create("udp://127.0.0.1:" + port),
                    Map.of("route", ROUTE, "speedMps", "50", "positionRateHz", "10", "batteryDrainPerSecond", "1.0"));
            StreamDescriptor txDescriptor = transmitter.start(feedId, spec);

            Device rxDevice = new Device(rxDeviceId, "loopback-drone", Set.of(Capability.TELEMETRY),
                    new StreamDescriptor(txDescriptor.protocol(), txDescriptor.uri(), Map.of()));

            List<Telemetry> collected = Collections.synchronizedList(new ArrayList<>());
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            Object monitor = new Object();

            Flow.Publisher<Telemetry> publisher = rxSource.open(rxDevice);
            publisher.subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(Telemetry item) {
                    collected.add(item);
                    synchronized (monitor) {
                        monitor.notifyAll();
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    errorRef.set(throwable);
                    synchronized (monitor) {
                        monitor.notifyAll();
                    }
                }

                @Override
                public void onComplete() {
                }
            });

            awaitAtLeast(collected, monitor, 10, java.time.Duration.ofSeconds(20));
            assertNull(errorRef.get(), "a real TX->wire->RX round trip must not error");

            List<Telemetry> withPosition = collected.stream().filter(t -> t.latitude() != null).toList();
            assertTrue(withPosition.size() >= 2, "expected at least two position-bearing samples");

            double firstLatitude = withPosition.get(0).latitude();
            double lastLatitude = withPosition.get(withPosition.size() - 1).latitude();
            assertTrue(Math.abs(lastLatitude - firstLatitude) > 1e-6,
                    "expected the drone to actually move: first=" + firstLatitude + " last=" + lastLatitude);

            List<Telemetry> withBattery = collected.stream().filter(t -> t.batteryPercent() != null).toList();
            assertTrue(!withBattery.isEmpty(), "expected at least one battery-reporting sample (SYS_STATUS)");

            // docs/plans/done/FC-INTEGRATIONS-PLAN.md F-a: the nominal (non-failsafe) heartbeat reports armed + Loiter.
            List<Telemetry> withFlightState = collected.stream().filter(t -> t.flightState() != null).toList();
            assertTrue(!withFlightState.isEmpty(), "expected at least one flight-state-bearing sample (HEARTBEAT)");
            assertTrue(withFlightState.stream().anyMatch(t -> Boolean.TRUE.equals(t.flightState().armed())
                            && "Loiter".equals(t.flightState().mode())),
                    "expected an armed, mode=Loiter sample from the nominal heartbeat");
        } finally {
            transmitter.stop(feedId);
            rxSource.close(rxDeviceId);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aDrainedBatteryTriggersAFailsafeRtlSampleOnTheRxSide() throws Exception {
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        MavlinkTelemetrySource rxSource = new MavlinkTelemetrySource();
        FeedId feedId = FeedId.random();
        DeviceId rxDeviceId = DeviceId.random();
        int port = freePort();

        try {
            // A high failsafeBatteryPercent + a steep drain rate guarantees the very first
            // re-sent heartbeat (1s in) already reports the drained battery below the threshold.
            FeedSpec spec = new FeedSpec("mavlink", URI.create("udp://127.0.0.1:" + port),
                    Map.of("route", ROUTE, "speedMps", "20", "positionRateHz", "5",
                            "batteryDrainPerSecond", "50", "failsafeBatteryPercent", "99"));
            StreamDescriptor txDescriptor = transmitter.start(feedId, spec);

            Device rxDevice = new Device(rxDeviceId, "failsafe-drone", Set.of(Capability.TELEMETRY),
                    new StreamDescriptor(txDescriptor.protocol(), txDescriptor.uri(), Map.of()));

            List<Telemetry> collected = Collections.synchronizedList(new ArrayList<>());
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            Object monitor = new Object();

            Flow.Publisher<Telemetry> publisher = rxSource.open(rxDevice);
            publisher.subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(Telemetry item) {
                    collected.add(item);
                    synchronized (monitor) {
                        monitor.notifyAll();
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    errorRef.set(throwable);
                    synchronized (monitor) {
                        monitor.notifyAll();
                    }
                }

                @Override
                public void onComplete() {
                }
            });

            boolean sawFailsafeRtl = false;
            long deadline = System.currentTimeMillis() + 20_000L;
            synchronized (monitor) {
                while (!sawFailsafeRtl && System.currentTimeMillis() < deadline) {
                    sawFailsafeRtl = collected.stream().anyMatch(t -> t.flightState() != null
                            && Boolean.TRUE.equals(t.flightState().failsafe())
                            && "RTL".equals(t.flightState().mode()));
                    if (!sawFailsafeRtl) {
                        monitor.wait(500);
                    }
                }
            }

            assertNull(errorRef.get(), "a real TX->wire->RX round trip must not error");
            assertTrue(sawFailsafeRtl, "expected a failsafe=true, mode=RTL sample once the drained battery fell "
                    + "below failsafeBatteryPercent; got " + collected.size() + " samples");
        } finally {
            transmitter.stop(feedId);
            rxSource.close(rxDeviceId);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void anArdupilotmegaWindMessageSurvivesTheRealUdpPathIntoATelemetrySample() throws Exception {
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        MavlinkTelemetrySource rxSource = new MavlinkTelemetrySource();
        FeedId feedId = FeedId.random();
        DeviceId rxDeviceId = DeviceId.random();
        int port = freePort();

        try {
            // The transmitter's own HEARTBEAT already reports autopilot=ARDUPILOTMEGA (docs/
            // FC-INTEGRATIONS-PLAN.md F-a) at 1Hz -- exactly the traffic that teaches the shared
            // MavlinkConnection inside MavlinkSocketHub's read loop the ardupilotmega dialect for
            // this sysid, per MavlinkTelemetryDecoder's class javadoc.
            FeedSpec spec = new FeedSpec("mavlink", URI.create("udp://127.0.0.1:" + port),
                    Map.of("route", ROUTE, "speedMps", "20", "positionRateHz", "5"));
            StreamDescriptor txDescriptor = transmitter.start(feedId, spec);

            Device rxDevice = new Device(rxDeviceId, "wind-drone", Set.of(Capability.TELEMETRY),
                    new StreamDescriptor(txDescriptor.protocol(), txDescriptor.uri(), Map.of()));

            List<Telemetry> collected = Collections.synchronizedList(new ArrayList<>());
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            Object monitor = new Object();

            Flow.Publisher<Telemetry> publisher = rxSource.open(rxDevice);
            publisher.subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(Telemetry item) {
                    collected.add(item);
                    synchronized (monitor) {
                        monitor.notifyAll();
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    errorRef.set(throwable);
                    synchronized (monitor) {
                        monitor.notifyAll();
                    }
                }

                @Override
                public void onComplete() {
                }
            });

            // Let at least one real sample through first, so the shared connection has certainly
            // already resolved the dialect for this sysid before the raw WIND datagram below.
            awaitAtLeast(collected, monitor, 1, java.time.Duration.ofSeconds(15));

            sendRawWindDatagram(port, MavlinkFeedTransmitter.DEFAULT_MAV_SYSTEM_ID);

            boolean sawWind;
            long deadline = System.currentTimeMillis() + 15_000L;
            synchronized (monitor) {
                sawWind = containsWindSample(collected);
                while (!sawWind && System.currentTimeMillis() < deadline) {
                    monitor.wait(500);
                    sawWind = containsWindSample(collected);
                }
            }

            assertNull(errorRef.get(), "a real ardupilotmega WIND datagram must not error the RX side");
            assertTrue(sawWind, "expected a Telemetry sample carrying extra.windSpeedMps from a real "
                    + "WIND datagram; got " + collected.size() + " samples total");

            Telemetry withWind;
            synchronized (collected) { // Collections.synchronizedList requires manual sync while iterating
                withWind = collected.stream()
                        .filter(t -> t.extra().containsKey("windSpeedMps"))
                        .findFirst()
                        .orElseThrow();
            }
            assertEquals(6.2, withWind.extra().get("windSpeedMps"), 1e-6);
            assertEquals(275.5, withWind.extra().get("windDirectionDegrees"), 1e-6);
        } finally {
            transmitter.stop(feedId);
            rxSource.close(rxDeviceId);
        }
    }

    /**
     * {@code collected} is a {@link Collections#synchronizedList}, whose individual mutating calls
     * (like {@code onNext}'s {@code add}) are each thread-safe, but iterating (what {@code
     * .stream()} does) is not, per its own contract — the caller must hold the list's own monitor
     * for the duration of the iteration to avoid a {@link java.util.ConcurrentModificationException}
     * racing against a concurrent {@code add} from the still-live feed's onNext callback.
     */
    private static boolean containsWindSample(List<Telemetry> collected) {
        synchronized (collected) {
            return collected.stream().anyMatch(t -> t.extra().containsKey("windSpeedMps"));
        }
    }

    /** Sends one raw {@code WIND} (ardupilotmega dialect) datagram directly to {@code port}, bypassing {@link MavlinkFeedTransmitter} entirely. */
    private static void sendRawWindDatagram(int port, int sysid) throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            MavlinkConnection connection = MavlinkConnection.create(
                    InputStream.nullInputStream(), new MavlinkUdpOutputStream(socket, loopback, port));
            Wind wind = Wind.builder().direction(275.5f).speed(6.2f).speedZ(0f).build();
            connection.send2(sysid, MavlinkFeedTransmitter.MAV_COMPONENT_ID, wind);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void garbageDatagramsInterleavedWithRealTrafficNeverBreakTheRxSide() throws Exception {
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        MavlinkTelemetrySource rxSource = new MavlinkTelemetrySource();
        FeedId feedId = FeedId.random();
        DeviceId rxDeviceId = DeviceId.random();
        int port = freePort();

        try {
            FeedSpec spec = new FeedSpec("mavlink", URI.create("udp://127.0.0.1:" + port),
                    Map.of("route", ROUTE, "speedMps", "20", "positionRateHz", "5"));
            StreamDescriptor txDescriptor = transmitter.start(feedId, spec);

            Device rxDevice = new Device(rxDeviceId, "noisy-drone", Set.of(Capability.TELEMETRY),
                    new StreamDescriptor(txDescriptor.protocol(), txDescriptor.uri(), Map.of()));

            List<Telemetry> collected = Collections.synchronizedList(new ArrayList<>());
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            Object monitor = new Object();

            Flow.Publisher<Telemetry> publisher = rxSource.open(rxDevice);
            publisher.subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(Telemetry item) {
                    collected.add(item);
                    synchronized (monitor) {
                        monitor.notifyAll();
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    errorRef.set(throwable);
                    synchronized (monitor) {
                        monitor.notifyAll();
                    }
                }

                @Override
                public void onComplete() {
                }
            });

            // Fire a steady stream of garbage datagrams at the same port the real feed is
            // targeting -- some plausible-looking (start with a MAVLink 2 magic byte), some pure
            // noise -- concurrently with the real transmitter's own traffic.
            Thread noiseThread = startNoiseSender(port);
            try {
                awaitAtLeast(collected, monitor, 5, java.time.Duration.ofSeconds(20));
            } finally {
                noiseThread.interrupt();
                noiseThread.join(5_000);
            }

            assertNull(errorRef.get(), "garbage datagrams must never surface as onError");
            assertTrue(collected.size() >= 5, "real messages must keep decoding despite interleaved garbage");
        } finally {
            transmitter.stop(feedId);
            rxSource.close(rxDeviceId);
        }
    }

    private static Thread startNoiseSender(int port) {
        Thread thread = new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                InetAddress loopback = InetAddress.getByName("127.0.0.1");
                java.util.Random random = new java.util.Random(42);
                while (!Thread.currentThread().isInterrupted()) {
                    byte[] noise = new byte[8 + random.nextInt(40)];
                    random.nextBytes(noise);
                    if (random.nextBoolean()) {
                        noise[0] = (byte) 0xFD; // occasionally look like a MAVLink 2 frame start
                    }
                    socket.send(new DatagramPacket(noise, noise.length, loopback, port));
                    Thread.sleep(15);
                }
            } catch (Exception e) {
                // Best-effort noise generator; interruption during sleep/send ends it, nothing to escalate.
            }
        }, "mavlink-test-noise-sender");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void awaitAtLeast(List<Telemetry> collected, Object monitor, int count, java.time.Duration timeout)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        synchronized (monitor) {
            while (collected.size() < count && System.currentTimeMillis() < deadline) {
                monitor.wait(500);
            }
        }
        assertTrue(collected.size() >= count,
                "expected at least " + count + " samples within " + timeout + ", got " + collected.size());
    }

    private static int freePort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
