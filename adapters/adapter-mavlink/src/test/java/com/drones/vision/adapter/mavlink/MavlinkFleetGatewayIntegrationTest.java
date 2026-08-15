package com.drones.vision.adapter.mavlink;

import com.drones.vision.kernel.Capability;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.perception.domain.model.FeedId;
import com.drones.vision.perception.domain.model.FeedSpec;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.Telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * docs/plans/active/DRONE-INFRA-PLAN.md I-a: multi-vehicle single-port ingest through {@link MavlinkGateway},
 * exercised end to end over real loopback UDP sockets — several {@link MavlinkFeedTransmitter}
 * feeds at distinct system ids, all pushed at ONE port, ingested by {@link MavlinkTelemetrySource}
 * devices that pin, don't pin, or never open at all.
 *
 * <p>Since {@link Telemetry} carries no sysid, every route here uses a distinct latitude "bucket"
 * (see {@link #bucket}) so a collected sample can be attributed back to the vehicle it actually
 * came from without any domain change: bucket {@code n}'s route is {@code n0.00000,30.00000 ->
 * n0.00050,30.00000}, so every position that route ever reports floors to bucket {@code n}.
 */
class MavlinkFleetGatewayIntegrationTest {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void pinnedDevicesEachReceiveOnlyTheirOwnVehiclesTelemetry() throws Exception {
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        MavlinkTelemetrySource source = new MavlinkTelemetrySource();
        int port = freePort();
        FeedId feedA = FeedId.random();
        FeedId feedB = FeedId.random();
        DeviceId deviceAId = DeviceId.random();
        DeviceId deviceBId = DeviceId.random();

        try {
            Collector collectorA = Collector.subscribeTo(source.open(device(port, deviceAId, Map.of("sysid", "11"))));
            Collector collectorB = Collector.subscribeTo(source.open(device(port, deviceBId, Map.of("sysid", "12"))));

            transmitter.start(feedA, feedSpec(port, "11", route(1)));
            transmitter.start(feedB, feedSpec(port, "12", route(2)));

            collectorA.awaitAtLeastPositionSamples(5, Duration.ofSeconds(20));
            collectorB.awaitAtLeastPositionSamples(5, Duration.ofSeconds(20));

            assertTrue(collectorA.allPositionsInBucket(1), "device pinned to sysid 11 must only see vehicle 11's positions");
            assertTrue(collectorB.allPositionsInBucket(2), "device pinned to sysid 12 must only see vehicle 12's positions");
        } finally {
            transmitter.stop(feedA);
            transmitter.stop(feedB);
            source.close(deviceAId);
            source.close(deviceBId);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void unpinnedDeviceClaimsTheFirstUnclaimedSysidHeard() throws Exception {
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        MavlinkTelemetrySource source = new MavlinkTelemetrySource();
        int port = freePort();
        FeedId feedId = FeedId.random();
        DeviceId deviceId = DeviceId.random();

        try {
            Collector collector = Collector.subscribeTo(source.open(device(port, deviceId, Map.of())));

            transmitter.start(feedId, feedSpec(port, "21", route(3)));

            collector.awaitAtLeastPositionSamples(5, Duration.ofSeconds(20));
            assertTrue(collector.allPositionsInBucket(3), "the unpinned device must have claimed sysid 21");
        } finally {
            transmitter.stop(feedId);
            source.close(deviceId);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void unclaimedRegistryPopulatesForAVehicleNobodyClaims() throws Exception {
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        MavlinkTelemetrySource source = new MavlinkTelemetrySource();
        int port = freePort();
        FeedId claimedFeed = FeedId.random();
        FeedId strayFeed = FeedId.random();
        DeviceId deviceId = DeviceId.random();
        String bindKey = MavlinkTelemetrySource.bindKey("127.0.0.1", port);

        try {
            // Pinned to 31 only -- it must never claim the stray vehicle at sysid 32.
            Collector collector = Collector.subscribeTo(source.open(device(port, deviceId, Map.of("sysid", "31"))));

            transmitter.start(claimedFeed, feedSpec(port, "31", route(4)));
            transmitter.start(strayFeed, feedSpec(port, "32", route(9)));

            collector.awaitAtLeastPositionSamples(3, Duration.ofSeconds(20));

            MavlinkGateway.UnclaimedVehicle strayVehicle = awaitUnclaimedWithFirmware(source, bindKey, 32, Duration.ofSeconds(20));
            assertEquals("ardupilot", strayVehicle.firmware());
            assertEquals(2, strayVehicle.mavType(), "MAV_TYPE_QUADROTOR"); // MavlinkFeedTransmitter always sends QUADROTOR
            assertTrue(source.unclaimedVehicles(bindKey).stream().noneMatch(v -> v.sysid() == 31),
                    "a claimed vehicle must never also appear in the unclaimed registry");
        } finally {
            transmitter.stop(claimedFeed);
            transmitter.stop(strayFeed);
            source.close(deviceId);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void unpinnedDeviceReElectsToAnotherSysidOnceItsClaimedVehicleGoesSilent() throws Exception {
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        long silenceWindowMillis = 500L;
        MavlinkTelemetrySource source = new MavlinkTelemetrySource(silenceWindowMillis); // test-only short window
        int port = freePort();
        FeedId firstFeed = FeedId.random();
        FeedId secondFeed = FeedId.random();
        DeviceId deviceId = DeviceId.random();

        try {
            Collector collector = Collector.subscribeTo(source.open(device(port, deviceId, Map.of())));

            transmitter.start(firstFeed, feedSpec(port, "41", route(5)));
            collector.awaitAtLeastPositionSamples(3, Duration.ofSeconds(20));
            assertTrue(collector.allPositionsInBucket(5), "must have first claimed sysid 41");

            transmitter.stop(firstFeed); // sysid 41 goes silent
            Thread.sleep(silenceWindowMillis + 300); // clear the silence window before the next unclaimed sysid arrives

            transmitter.start(secondFeed, feedSpec(port, "42", route(6)));
            awaitPositionInBucket(collector, 6, Duration.ofSeconds(20));
        } finally {
            transmitter.stop(firstFeed);
            transmitter.stop(secondFeed);
            source.close(deviceId);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void closingOneDeviceKeepsTheSharedSocketAliveAndClosingAllReleasesIt() throws Exception {
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        MavlinkTelemetrySource source = new MavlinkTelemetrySource();
        int port = freePort();
        FeedId feedId = FeedId.random();
        DeviceId pinnedDeviceId = DeviceId.random();
        DeviceId unpinnedDeviceId = DeviceId.random();

        try {
            Collector pinnedCollector = Collector.subscribeTo(source.open(device(port, pinnedDeviceId, Map.of("sysid", "51"))));
            Collector unpinnedCollector = Collector.subscribeTo(source.open(device(port, unpinnedDeviceId, Map.of())));

            transmitter.start(feedId, feedSpec(port, "51", route(7)));
            pinnedCollector.awaitAtLeastPositionSamples(3, Duration.ofSeconds(20));

            source.close(pinnedDeviceId); // one of two registrations: the shared socket must stay alive

            // Releasing the pinned claim leaves the unpinned device as the only one waiting, so it
            // adopts sysid 51 itself -- proof the socket kept running and routing after the first close.
            unpinnedCollector.awaitAtLeastPositionSamples(3, Duration.ofSeconds(20));
            assertTrue(unpinnedCollector.allPositionsInBucket(7));

            source.close(unpinnedDeviceId); // the last registration: the hub must fully shut down

            assertDoesNotThrow(() -> {
                try (DatagramSocket probe = new DatagramSocket(null)) {
                    probe.setReuseAddress(true);
                    probe.bind(new InetSocketAddress("127.0.0.1", port));
                }
            }, "the shared socket must be released once every device sharing it has closed");
        } finally {
            transmitter.stop(feedId);
            source.close(pinnedDeviceId);
            source.close(unpinnedDeviceId);
        }
    }

    private static Device device(int port, DeviceId id, Map<String, String> options) {
        return new Device(id, "fleet-gateway-test-device", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("mavlink", URI.create("udp://127.0.0.1:" + port), options));
    }

    private static FeedSpec feedSpec(int port, String sysid, String route) {
        return new FeedSpec("mavlink", URI.create("udp://127.0.0.1:" + port),
                Map.of("route", route, "sysid", sysid, "positionRateHz", "20", "speedMps", "5"));
    }

    /** Bucket {@code n}'s route: {@code n0.00000,30.00000 -> n0.00050,30.00000} -- every position it ever reports floors to {@code n}. */
    private static String route(int bucket) {
        return bucket + "0.00000,30.00000;" + bucket + "0.00050,30.00000";
    }

    private static int bucket(double latitude) {
        return (int) Math.floor(latitude / 10.0);
    }

    private static MavlinkGateway.UnclaimedVehicle awaitUnclaimedWithFirmware(
            MavlinkTelemetrySource source, String bindKey, int sysid, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            for (MavlinkGateway.UnclaimedVehicle vehicle : source.unclaimedVehicles(bindKey)) {
                if (vehicle.sysid() == sysid && vehicle.firmware() != null) {
                    return vehicle;
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("expected sysid " + sysid + " in the unclaimed registry with a known firmware "
                + "within " + timeout + "; got " + source.unclaimedVehicles(bindKey));
    }

    private static void awaitPositionInBucket(Collector collector, int expectedBucket, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (!collector.anyPositionInBucket(expectedBucket) && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertTrue(collector.anyPositionInBucket(expectedBucket),
                "expected a sample from bucket " + expectedBucket + " (re-election) within " + timeout);
    }

    private static int freePort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** A {@link Flow.Subscriber} collecting every sample, with poll helpers tolerant of this suite's UDP timing. */
    private static final class Collector implements Flow.Subscriber<Telemetry> {
        private final List<Telemetry> collected = Collections.synchronizedList(new ArrayList<>());
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private final Object monitor = new Object();

        static Collector subscribeTo(Flow.Publisher<Telemetry> publisher) {
            Collector collector = new Collector();
            publisher.subscribe(collector);
            return collector;
        }

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
            error.set(throwable);
            synchronized (monitor) {
                monitor.notifyAll();
            }
        }

        @Override
        public void onComplete() {
        }

        private List<Telemetry> snapshot() {
            synchronized (collected) {
                return List.copyOf(collected);
            }
        }

        private int positionSampleCount() {
            return (int) snapshot().stream().filter(t -> t.latitude() != null).count();
        }

        void awaitAtLeastPositionSamples(int count, Duration timeout) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            synchronized (monitor) {
                while (positionSampleCount() < count && System.currentTimeMillis() < deadline) {
                    monitor.wait(200);
                }
            }
            assertNull(error.get(), "collector must not have errored");
            assertTrue(positionSampleCount() >= count,
                    "expected at least " + count + " position samples within " + timeout + ", got " + positionSampleCount());
        }

        boolean allPositionsInBucket(int expectedBucket) {
            List<Telemetry> samples = snapshot();
            return !samples.isEmpty() && samples.stream().filter(t -> t.latitude() != null)
                    .allMatch(t -> bucket(t.latitude()) == expectedBucket);
        }

        boolean anyPositionInBucket(int expectedBucket) {
            return snapshot().stream().filter(t -> t.latitude() != null)
                    .anyMatch(t -> bucket(t.latitude()) == expectedBucket);
        }
    }
}
