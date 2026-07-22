package com.drones.vision.adapter.simulation;

import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.Telemetry;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulatedTelemetrySourceTest {

    /**
     * Uses {@link SimulatedTelemetrySource}'s package-private constructor
     * (this test lives in the same package) to emit at an arbitrary period
     * instead of the real 1 Hz cadence, so tests observe several samples
     * without waiting seconds of wall-clock time.
     */
    private static SimulatedTelemetrySource fastSource(long periodMillis) {
        return new SimulatedTelemetrySource(periodMillis);
    }

    private static Device telemetryDevice(Map<String, String> options) {
        return new Device(DeviceId.random(), "drone-1", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("sim", URI.create("sim://drone-1"), options));
    }

    @Test
    void supportsOnlyTelemetryCapableSimDevices() {
        SimulatedTelemetrySource source = new SimulatedTelemetrySource();

        Device telemetrySim = telemetryDevice(Map.of());
        Device videoOnlySim = new Device(DeviceId.random(), "cam", Set.of(Capability.VIDEO),
                new StreamDescriptor("sim", URI.create("sim://cam"), Map.of()));
        Device telemetryRtsp = new Device(DeviceId.random(), "drone-2", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("rtsp", URI.create("rtsp://drone-2"), Map.of()));

        assertTrue(source.supports(telemetrySim));
        assertFalse(source.supports(videoOnlySim), "a device without TELEMETRY capability must not be supported");
        assertFalse(source.supports(telemetryRtsp), "a non-sim protocol must not be supported");
    }

    @Test
    void emitsAtLeastTwoSamplesWithMovingPositionAndDrainingBattery() throws InterruptedException {
        SimulatedTelemetrySource source = fastSource(20L);
        Device device = telemetryDevice(Map.of());

        List<Telemetry> collected = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch atLeastFive = new CountDownLatch(5);

        Flow.Publisher<Telemetry> publisher = source.open(device);
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Telemetry item) {
                collected.add(item);
                atLeastFive.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        });

        try {
            assertTrue(atLeastFive.await(5, TimeUnit.SECONDS), "expected at least 5 telemetry samples within 5s");

            List<Telemetry> snapshot = List.copyOf(collected);
            assertTrue(snapshot.size() >= 5);

            for (Telemetry sample : snapshot) {
                assertEquals(device.id(), sample.deviceId());
                assertTrue(sample.latitude() != null && sample.longitude() != null);
                assertTrue(sample.batteryPercent() != null);
            }

            boolean positionMoved = snapshot.stream().map(t -> t.latitude() + "," + t.longitude()).distinct().count() > 1;
            assertTrue(positionMoved, "position must move across samples on the circular track");

            for (int i = 1; i < snapshot.size(); i++) {
                assertTrue(snapshot.get(i).batteryPercent() <= snapshot.get(i - 1).batteryPercent(),
                        "battery must never increase between samples");
            }
            assertTrue(snapshot.get(snapshot.size() - 1).batteryPercent() < snapshot.get(0).batteryPercent(),
                    "battery must drain strictly over multiple samples");
        } finally {
            source.close(device.id());
        }
    }

    @Test
    void honorsLatLonOptionsAsTrackCenter() throws InterruptedException {
        SimulatedTelemetrySource source = fastSource(20L);
        Device device = telemetryDevice(Map.of("lat", "10.0", "lon", "20.0"));

        List<Telemetry> collected = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstSample = new CountDownLatch(1);

        Flow.Publisher<Telemetry> publisher = source.open(device);
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Telemetry item) {
                collected.add(item);
                firstSample.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        });

        try {
            assertTrue(firstSample.await(5, TimeUnit.SECONDS));
            Telemetry sample = collected.get(0);
            // Whichever tick is received first (the scheduler may fire once or twice before this
            // test's subscribe() call lands, discarding those pre-subscription ticks -- same latent
            // race SimulatedVideoSourceTest tolerates by not asserting on frame #0 specifically), every
            // point on the track must sit ~TRACK_RADIUS_METERS from the configured center.
            double distanceFromCenter = approxDistanceMeters(sample.latitude(), sample.longitude(), 10.0, 20.0);
            assertEquals(SimulatedTelemetrySource.TRACK_RADIUS_METERS, distanceFromCenter, 5.0);
        } finally {
            source.close(device.id());
        }
    }

    /** Equirectangular approximation, adequate at the ~200m scale this source operates at. */
    private static double approxDistanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double earthRadiusMeters = 6_371_000.0;
        double meanLatitudeRadians = Math.toRadians((lat1 + lat2) / 2.0);
        double northMeters = Math.toRadians(lat1 - lat2) * earthRadiusMeters;
        double eastMeters = Math.toRadians(lon1 - lon2) * earthRadiusMeters * Math.cos(meanLatitudeRadians);
        return Math.sqrt(northMeters * northMeters + eastMeters * eastMeters);
    }

    @Test
    void closeStopsProducingSamplesCleanlyAndIsIdempotent() throws InterruptedException {
        SimulatedTelemetrySource source = fastSource(20L);
        Device device = telemetryDevice(Map.of());

        List<Telemetry> collected = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch atLeastThree = new CountDownLatch(3);

        Flow.Publisher<Telemetry> publisher = source.open(device);
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Telemetry item) {
                collected.add(item);
                atLeastThree.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        });

        assertTrue(atLeastThree.await(5, TimeUnit.SECONDS));

        source.close(device.id());
        int sizeRightAfterClose = collected.size();
        Thread.sleep(300);
        int sizeAfterGracePeriod = collected.size();

        assertEquals(sizeRightAfterClose, sizeAfterGracePeriod, "no further samples should be produced after close()");

        source.close(device.id()); // must not throw
    }
}
