package com.drones.vision.adapter.mavlink;

import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.FeedId;
import com.drones.vision.domain.model.FeedSpec;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.Telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full TX→wire→RX round trip over real loopback UDP sockets — no hardware, no docker: {@link
 * MavlinkFeedTransmitter} flies a two-waypoint loop and {@link MavlinkTelemetrySource} ingests it
 * back, proving a moving position (and draining battery) actually arrives end to end. A second
 * test proves garbage UDP datagrams interleaved with real traffic never take the RX side down.
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
        } finally {
            transmitter.stop(feedId);
            rxSource.close(rxDeviceId);
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
