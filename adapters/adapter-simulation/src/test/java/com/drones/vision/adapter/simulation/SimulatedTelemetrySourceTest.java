package com.drones.vision.adapter.simulation;

import com.drones.vision.kernel.Capability;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.FlightState;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.Telemetry;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
            assertEquals(TelemetrySettings.DEFAULT_TRACK_RADIUS_METERS, distanceFromCenter, 5.0);
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

    // --- CT-a: configurable flight plans ---------------------------------------------

    @Test
    void routeOptionFliesAlongTheConfiguredRouteAtTheConfiguredSpeed() throws InterruptedException {
        long periodMillis = 20L;
        SimulatedTelemetrySource source = fastSource(periodMillis);
        // A long, straight, north-heading leg so a modest speed only covers a small fraction of it.
        Device device = telemetryDevice(Map.of("route", "10.0,20.0;10.01,20.0", "speedMps", "50"));

        List<Telemetry> collected = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch atLeastFour = new CountDownLatch(4);
        Flow.Publisher<Telemetry> publisher = source.open(device);
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Telemetry item) {
                collected.add(item);
                atLeastFour.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        });

        try {
            assertTrue(atLeastFour.await(5, TimeUnit.SECONDS), "expected at least 4 telemetry samples within 5s");
            List<Telemetry> snapshot = List.copyOf(collected);
            assertTrue(snapshot.size() >= 4);

            double expectedStepMeters = 50.0 * (periodMillis / 1000.0); // speedMps * tickSeconds
            for (int i = 1; i < snapshot.size(); i++) {
                double stepMeters = approxDistanceMeters(
                        snapshot.get(i - 1).latitude(), snapshot.get(i - 1).longitude(),
                        snapshot.get(i).latitude(), snapshot.get(i).longitude());
                assertEquals(expectedStepMeters, stepMeters, 0.5,
                        "each tick must advance by speedMps * tickSeconds along the route");
            }
            for (Telemetry sample : snapshot) {
                assertEquals(0.0, sample.headingDegrees(), 1.0, "a due-north segment must report a ~0 degree heading");
            }
        } finally {
            source.close(device.id());
        }
    }

    @Test
    void malformedRouteOptionFallsBackToTheCircularTrackWithoutThrowing() throws InterruptedException {
        SimulatedTelemetrySource source = fastSource(20L);
        Device device = telemetryDevice(Map.of("route", "not-a-route-at-all", "lat", "10.0", "lon", "20.0"));

        assertCircularTrackAroundGivenCenter(source, device);
    }

    @Test
    void routeOptionWithFewerThanTwoPointsFallsBackToTheCircularTrack() throws InterruptedException {
        SimulatedTelemetrySource source = fastSource(20L);
        Device device = telemetryDevice(Map.of("route", "10.0,20.0", "lat", "10.0", "lon", "20.0"));

        assertCircularTrackAroundGivenCenter(source, device);
    }

    private void assertCircularTrackAroundGivenCenter(SimulatedTelemetrySource source, Device device)
            throws InterruptedException {
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
            double distanceFromCenter = approxDistanceMeters(sample.latitude(), sample.longitude(), 10.0, 20.0);
            assertEquals(TelemetrySettings.DEFAULT_TRACK_RADIUS_METERS, distanceFromCenter, 5.0);
        } finally {
            source.close(device.id());
        }
    }

    @Test
    void nonPositiveOrUnparseableSpeedMpsFallsBackToTheDefaultCruiseSpeed() throws InterruptedException {
        long periodMillis = 20L;
        SimulatedTelemetrySource source = fastSource(periodMillis);
        Device device = telemetryDevice(Map.of("route", "10.0,20.0;10.01,20.0", "speedMps", "-5"));

        List<Telemetry> collected = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch atLeastTwo = new CountDownLatch(2);
        Flow.Publisher<Telemetry> publisher = source.open(device);
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Telemetry item) {
                collected.add(item);
                atLeastTwo.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        });

        try {
            assertTrue(atLeastTwo.await(5, TimeUnit.SECONDS));
            List<Telemetry> snapshot = List.copyOf(collected);
            assertTrue(snapshot.size() >= 2);

            double expectedStepMeters = RoutePlan.DEFAULT_SPEED_MPS * (periodMillis / 1000.0);
            double stepMeters = approxDistanceMeters(
                    snapshot.get(0).latitude(), snapshot.get(0).longitude(),
                    snapshot.get(1).latitude(), snapshot.get(1).longitude());
            assertEquals(expectedStepMeters, stepMeters, 0.5,
                    "a non-positive speedMps must fall back to RoutePlan.DEFAULT_SPEED_MPS, not stall or throw");
        } finally {
            source.close(device.id());
        }
    }

    @Test
    void batteryDrainPerSecondOptionOverridesTheDefaultDrainRate() throws InterruptedException {
        long periodMillis = 20L;
        SimulatedTelemetrySource source = fastSource(periodMillis);
        Device device = telemetryDevice(Map.of("batteryDrainPerSecond", "5.0"));

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

        try {
            assertTrue(atLeastThree.await(5, TimeUnit.SECONDS));
            List<Telemetry> snapshot = List.copyOf(collected);
            assertTrue(snapshot.size() >= 3);

            double expectedPerTickDrain = 5.0 * (periodMillis / 1000.0);
            for (int i = 1; i < snapshot.size(); i++) {
                double drop = snapshot.get(i - 1).batteryPercent() - snapshot.get(i).batteryPercent();
                assertEquals(expectedPerTickDrain, drop, 0.01,
                        "batteryDrainPerSecond must override the default 0.05%/s drain rate");
            }
        } finally {
            source.close(device.id());
        }
    }

    // --- F-c: synthetic FlightState -----------------------------------------------------

    /**
     * Subscribes to {@code publisher}, awaits at least {@code atLeast} samples (5s budget, same as
     * every other test in this class), and returns them as an immutable snapshot in delivery order.
     */
    private static List<Telemetry> collectSamples(Flow.Publisher<Telemetry> publisher, int atLeast)
            throws InterruptedException {
        List<Telemetry> collected = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(atLeast);
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Telemetry item) {
                collected.add(item);
                latch.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS), "expected at least " + atLeast + " telemetry samples within 5s");
        return List.copyOf(collected);
    }

    @Test
    void nominalFlightStateAfterStartupIsArmedLoiterWithMildJitter() throws InterruptedException {
        SimulatedTelemetrySource source = fastSource(20L);
        Device device = telemetryDevice(Map.of());

        try {
            List<Telemetry> snapshot = collectSamples(source.open(device), 15);
            // Skip a generous margin past the startup window: the scheduler may fire a tick or two
            // before subscribe() lands (see honorsLatLonOptionsAsTrackCenter's comment above), so the
            // first *observed* sample isn't guaranteed to be tick 0.
            List<Telemetry> nominal = snapshot.subList(10, snapshot.size());
            assertTrue(nominal.size() >= 5);

            for (Telemetry sample : nominal) {
                FlightState flightState = sample.flightState();
                assertNotNull(flightState, "every sample must carry a FlightState");
                assertEquals(SyntheticFlightState.FLIGHT_STATE_FIRMWARE, flightState.firmware());
                assertEquals(SyntheticFlightState.FLIGHT_MODE_LOITER, flightState.mode());
                assertTrue(flightState.armed());
                assertFalse(flightState.failsafe());
                assertEquals(3, flightState.gpsFixType());
                assertTrue(flightState.satellites() >= SyntheticFlightState.NOMINAL_SATELLITES - 1
                                && flightState.satellites() <= SyntheticFlightState.NOMINAL_SATELLITES + 1,
                        "satellites must stay within +/-1 of the nominal count");
                assertEquals(SyntheticFlightState.NOMINAL_HDOP, flightState.hdop(), 0.15);
                assertTrue(flightState.rssiPercent() >= SyntheticFlightState.NOMINAL_RSSI_PERCENT - 5
                                && flightState.rssiPercent() <= SyntheticFlightState.NOMINAL_RSSI_PERCENT + 5,
                        "rssiPercent must stay close to the nominal value");
                assertTrue(flightState.armingBlockers().isEmpty());
            }
        } finally {
            source.close(device.id());
        }
    }

    @Test
    void startupRampsDisarmedWithArmingBlockerThenArms() throws InterruptedException {
        SimulatedTelemetrySource source = fastSource(20L);
        Device device = telemetryDevice(Map.of());

        try {
            List<Telemetry> snapshot =
                    collectSamples(source.open(device), (int) SyntheticFlightState.STARTUP_DISARMED_TICKS + 10);

            // Same latent subscribe race as above -- look for the disarmed startup state among the
            // earliest observed samples rather than pinning it to sample #0.
            boolean sawDisarmedStartup = snapshot.stream().limit(3).anyMatch(sample -> {
                FlightState flightState = sample.flightState();
                return Boolean.FALSE.equals(flightState.armed())
                        && flightState.armingBlockers().contains(SyntheticFlightState.STARTUP_ARMING_BLOCKER);
            });
            assertTrue(sawDisarmedStartup, "expected a disarmed sample with the synthetic arming blocker early on");

            int previousGpsFixType = 0;
            for (Telemetry sample : snapshot) {
                int gpsFixType = sample.flightState().gpsFixType();
                assertTrue(gpsFixType >= previousGpsFixType, "gpsFixType must ramp up, never regress, during startup");
                previousGpsFixType = gpsFixType;
            }

            FlightState lastObserved = snapshot.get(snapshot.size() - 1).flightState();
            assertTrue(lastObserved.armed(), "aircraft must be armed once past the startup window");
            assertTrue(lastObserved.armingBlockers().isEmpty(), "the synthetic arming blocker must clear once armed");
            assertEquals(3, lastObserved.gpsFixType());
        } finally {
            source.close(device.id());
        }
    }

    @Test
    void batteryDrivenFlightStateSwitchesToRtlThenLandAsBatteryDrains() throws InterruptedException {
        SimulatedTelemetrySource source = fastSource(20L);
        Device device = telemetryDevice(Map.of("batteryDrainPerSecond", "100"));

        try {
            List<Telemetry> snapshot = collectSamples(source.open(device), 55);

            boolean sawRtl = false;
            boolean sawLand = false;
            for (Telemetry sample : snapshot) {
                FlightState flightState = sample.flightState();
                assertNotNull(flightState);
                double batteryPercent = sample.batteryPercent();
                if (batteryPercent < SyntheticFlightState.LAND_BATTERY_PERCENT_THRESHOLD) {
                    assertEquals(SyntheticFlightState.FLIGHT_MODE_LAND, flightState.mode());
                    assertTrue(flightState.failsafe());
                    assertTrue(flightState.armed());
                    sawLand = true;
                } else if (batteryPercent < SyntheticFlightState.RTL_BATTERY_PERCENT_THRESHOLD) {
                    assertEquals(SyntheticFlightState.FLIGHT_MODE_RTL, flightState.mode());
                    assertTrue(flightState.failsafe());
                    assertTrue(flightState.armed());
                    sawRtl = true;
                } else if (Boolean.TRUE.equals(flightState.armed())) {
                    assertEquals(SyntheticFlightState.FLIGHT_MODE_LOITER, flightState.mode());
                    assertFalse(flightState.failsafe());
                }
            }
            assertTrue(sawRtl, "expected at least one sample in the RTL battery band (< 20%)");
            assertTrue(sawLand, "expected at least one sample in the Land battery band (< 8%)");
        } finally {
            source.close(device.id());
        }
    }

    @Test
    void flightStateSequenceIsDeterministicForTheSameDeviceId() throws InterruptedException {
        long periodMillis = 20L;
        DeviceId deviceId = DeviceId.random();
        Device device = new Device(deviceId, "drone-1", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("sim", URI.create("sim://drone-1"), Map.of()));

        SimulatedTelemetrySource sourceA = fastSource(periodMillis);
        SimulatedTelemetrySource sourceB = fastSource(periodMillis);
        try {
            List<FlightState> runA = flightStatesOf(collectSamples(sourceA.open(device), 20));
            List<FlightState> runB = flightStatesOf(collectSamples(sourceB.open(device), 20));

            // The two runs race subscribe() independently, so they may observe different numbers of
            // leading ticks (same latent race as elsewhere in this class) and can't be compared from
            // index 0. But the sample where `armed` first flips to true is, by construction, always
            // real tick STARTUP_DISARMED_TICKS (SyntheticFlightState.at() only reports armed=false
            // while n < STARTUP_DISARMED_TICKS) -- an anchor independent of how each run happened to start,
            // so aligning both runs there and comparing from there on directly tests determinism.
            int armedAtA = firstArmedIndex(runA);
            int armedAtB = firstArmedIndex(runB);
            assertTrue(armedAtA >= 0 && armedAtB >= 0,
                    "expected to observe the disarmed-to-armed transition in both runs");

            List<FlightState> alignedA = runA.subList(armedAtA, runA.size());
            List<FlightState> alignedB = runB.subList(armedAtB, runB.size());
            int overlap = Math.min(alignedA.size(), alignedB.size());
            assertTrue(overlap >= 5, "expected a meaningful overlap to compare after aligning on the arming transition");

            assertEquals(alignedA.subList(0, overlap), alignedB.subList(0, overlap),
                    "the same device id must always produce the same FlightState sequence once aligned on tick "
                            + "number (seeded jitter)");
        } finally {
            sourceA.close(device.id());
            sourceB.close(device.id());
        }
    }

    private static int firstArmedIndex(List<FlightState> flightStates) {
        for (int i = 0; i < flightStates.size(); i++) {
            if (Boolean.TRUE.equals(flightStates.get(i).armed())) {
                return i;
            }
        }
        return -1;
    }

    private static List<FlightState> flightStatesOf(List<Telemetry> samples) {
        return samples.stream().map(Telemetry::flightState).toList();
    }
}
