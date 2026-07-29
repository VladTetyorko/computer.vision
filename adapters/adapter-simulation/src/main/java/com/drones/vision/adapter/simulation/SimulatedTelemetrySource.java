package com.drones.vision.adapter.simulation;

import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.FlightState;
import com.drones.vision.domain.model.Telemetry;
import com.drones.vision.domain.port.out.TelemetrySourcePort;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Flow;

/**
 * Synthetic {@link TelemetrySourcePort} implementation: generates a slow
 * circular GPS track with battery drain in-process, so usage history, start
 * position, and a telemetry trail are demoable with zero hardware (per the
 * asset model plan's demo goal) — or, given a configurable flight plan
 * (docs/CYCLES-PLAN.md §7, CT-a), flies a piecewise-linear route with
 * checkpoints instead.
 *
 * <p>Supports devices that both expose {@link Capability#TELEMETRY} and use
 * {@code "sim"} as their {@link com.drones.vision.domain.model.StreamDescriptor#protocol()}
 * — i.e. the same simulated device a {@link SimulatedVideoSource} would also
 * serve. Recognized {@link com.drones.vision.domain.model.StreamDescriptor#options()}
 * keys (all optional, all lenient — a blank/unparseable/malformed value silently falls
 * back to its default rather than throwing, see {@code doubleOption}/{@link RoutePlan#parse}):
 * <ul>
 *   <li>{@code lat} — circular-track center latitude, default {@value #DEFAULT_CENTER_LATITUDE};
 *       ignored once a valid {@code route} is given</li>
 *   <li>{@code lon} — circular-track center longitude, default {@value #DEFAULT_CENTER_LONGITUDE};
 *       ignored once a valid {@code route} is given</li>
 *   <li>{@code route} — {@code lat,lon[,altM];lat,lon[,altM];…}, at least 2 points (start →
 *       checkpoints → end); absent or malformed (fewer than 2 points, an unparseable number) means
 *       "no route" — the circular track above is the back-compat default</li>
 *   <li>{@code speedMps} — cruise speed along a route, default {@link RoutePlan#DEFAULT_SPEED_MPS};
 *       only meaningful with {@code route}; must be positive, else the default applies</li>
 *   <li>{@code routeMode} — {@code loop} (default)/{@code bounce}/{@code once}, case-insensitive;
 *       only meaningful with {@code route}, see {@link RoutePlan.RouteMode}</li>
 *   <li>{@code batteryDrainPerSecond} — overrides {@value #BATTERY_DRAIN_PERCENT_PER_SECOND}%/s,
 *       whether flying the circular track or a route</li>
 * </ul>
 *
 * <p>Each {@link #open(Device)} call starts a dedicated, single-threaded
 * {@link ScheduledExecutorService} that, once per period (1 Hz by default —
 * see the package-private {@link #SimulatedTelemetrySource(long)} test seam
 * for a shorter interval), computes the next sample and submits a {@link
 * Telemetry} to a per-device {@link SubmissionPublisher}. Without a valid
 * {@code route} option, that sample is the next point on a ~{@value
 * #TRACK_RADIUS_METERS}m-radius circle around the {@code lat}/{@code lon}
 * center with a heading tangent to it (unchanged since before CT-a); with one,
 * the runtime instead advances a cumulative {@code distanceMeters} by {@code
 * speedMps * tickSeconds} every tick and asks the parsed {@link RoutePlan} for
 * the position/heading/altitude there — all route-mode (loop/bounce/once)
 * folding logic lives in {@link RoutePlan} itself, not here. Either way,
 * battery drains {@code batteryDrainPerSecond}%/s (default {@value
 * #BATTERY_DRAIN_PERCENT_PER_SECOND}) from a full charge. {@link
 * #close(DeviceId)} stops that executor and closes the publisher; both are
 * idempotent, matching {@link TelemetrySourcePort}'s contract.
 *
 * <p>Plain class with no framework dependency — instantiated directly by
 * {@code vision-app}'s wiring configuration.
 *
 * <p>Every sample also carries a synthetic {@link FlightState} (docs/FC-INTEGRATIONS-PLAN.md F-c),
 * so the flight-controller-aware UI (failsafe banner, preflight checklist, OSD chips) has something
 * real to show without hardware or a MAVLink link. For the first {@value #STARTUP_DISARMED_TICKS}
 * samples of a subscription the aircraft looks like it's still on the ground: {@code armed=false},
 * one synthetic arming blocker, and {@code gpsFixType} ramping {@code 1 → 3}; after that it flies
 * nominally ({@code firmware="ardupilot"}, {@code mode="Loiter"}, armed, no failsafe, 3D fix, ~12
 * satellites, ~0.8 HDOP, ~90% RSSI, all with mild deterministic jitter) until the same drained
 * {@code batteryPercent} this class already computes crosses a threshold: below {@value
 * #RTL_BATTERY_PERCENT_THRESHOLD}% the mode switches to {@code "RTL"} with {@code failsafe=true};
 * below {@value #LAND_BATTERY_PERCENT_THRESHOLD}% it switches to {@code "Land"} (failsafe stays
 * true) — a scripted "battery-driven drama" for dev demos of the RTH/failsafe banner. The jitter
 * (and the whole per-tick sequence, since it's a pure function of tick index and battery percent)
 * is seeded per device from a hash of {@link DeviceId}, so two runs against the same device id
 * reproduce an identical {@link FlightState} sequence.
 */
public final class SimulatedTelemetrySource implements TelemetrySourcePort {

    private static final String PROTOCOL = "sim";

    static final double DEFAULT_CENTER_LATITUDE = 50.45;
    static final double DEFAULT_CENTER_LONGITUDE = 30.52;
    static final double TRACK_RADIUS_METERS = 200.0;
    static final double BATTERY_DRAIN_PERCENT_PER_SECOND = 0.05;

    /** Number of leading samples per subscription where the synthetic aircraft is still disarmed. */
    static final long STARTUP_DISARMED_TICKS = 5L;
    /** Below this drained battery percent (exclusive), the synthetic mode switches to RTL + failsafe. */
    static final double RTL_BATTERY_PERCENT_THRESHOLD = 20.0;
    /** Below this drained battery percent (exclusive), the synthetic mode switches to Land (still failsafe). */
    static final double LAND_BATTERY_PERCENT_THRESHOLD = 8.0;

    static final String FLIGHT_STATE_FIRMWARE = "ardupilot";
    static final String FLIGHT_MODE_LOITER = "Loiter";
    static final String FLIGHT_MODE_RTL = "RTL";
    static final String FLIGHT_MODE_LAND = "Land";
    static final String STARTUP_ARMING_BLOCKER = "PreArm: GPS: waiting for home";
    static final int NOMINAL_SATELLITES = 12;
    static final double NOMINAL_HDOP = 0.8;
    static final int NOMINAL_RSSI_PERCENT = 90;

    /** One full lap of the circular track every this many samples. */
    private static final long TICKS_PER_LAP = 60L;
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;
    private static final long DEFAULT_PERIOD_MILLIS = 1000L; // 1 Hz

    private final long periodMillis;
    private final Map<DeviceId, DeviceRuntime> runtimes = new ConcurrentHashMap<>();

    /** Emits at 1 Hz, per the asset model plan. */
    public SimulatedTelemetrySource() {
        this(DEFAULT_PERIOD_MILLIS);
    }

    /**
     * Test seam: emits at an arbitrary period instead of the real 1 Hz cadence,
     * so tests can observe several samples without waiting seconds of
     * wall-clock time.
     *
     * @param periodMillis milliseconds between samples; must be positive
     */
    SimulatedTelemetrySource(long periodMillis) {
        if (periodMillis <= 0) {
            throw new IllegalArgumentException("periodMillis must be positive: " + periodMillis);
        }
        this.periodMillis = periodMillis;
    }

    @Override
    public boolean supports(Device device) {
        return device != null && device.capabilities().contains(Capability.TELEMETRY)
                && PROTOCOL.equals(device.stream().protocol());
    }

    @Override
    public Flow.Publisher<Telemetry> open(Device device) {
        if (!supports(device)) {
            throw new IllegalArgumentException("SimulatedTelemetrySource does not support device: " + device);
        }
        double centerLatitude = doubleOption(device, "lat", DEFAULT_CENTER_LATITUDE);
        double centerLongitude = doubleOption(device, "lon", DEFAULT_CENTER_LONGITUDE);
        double speedMps = positiveDoubleOption(device, "speedMps", RoutePlan.DEFAULT_SPEED_MPS);
        double batteryDrainPercentPerSecond =
                doubleOption(device, "batteryDrainPerSecond", BATTERY_DRAIN_PERCENT_PER_SECOND);
        RoutePlan routePlan = RoutePlan.parse(
                device.stream().options().get("route"), device.stream().options().get("routeMode"));

        DeviceRuntime runtime = new DeviceRuntime(device.id(), centerLatitude, centerLongitude, periodMillis,
                routePlan, speedMps, batteryDrainPercentPerSecond);
        DeviceRuntime previous = runtimes.put(device.id(), runtime);
        if (previous != null) {
            previous.close(); // defensive: a device id must not have two live runtimes
        }
        runtime.start();
        return runtime.publisher;
    }

    @Override
    public void close(DeviceId id) {
        DeviceRuntime runtime = runtimes.remove(id);
        if (runtime != null) {
            runtime.close();
        }
    }

    private static double doubleOption(Device device, String key, double defaultValue) {
        String raw = device.stream().options().get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Like {@link #doubleOption}, but a parsed value that isn't positive also falls back to the default. */
    private static double positiveDoubleOption(Device device, String key, double defaultValue) {
        double parsed = doubleOption(device, key, defaultValue);
        return parsed > 0 ? parsed : defaultValue;
    }

    /** Per-open runtime: a scheduled sample generator feeding a {@link SubmissionPublisher}. */
    private static final class DeviceRuntime {
        private final DeviceId deviceId;
        private final double centerLatitude;
        private final double centerLongitude;
        private final long periodMillis;
        /** {@code null} means "no valid route" — fall back to the circular track, unchanged since before CT-a. */
        private final RoutePlan routePlan;
        private final double speedMps;
        private final double batteryDrainPercentPerSecond;
        private final SubmissionPublisher<Telemetry> publisher = new SubmissionPublisher<>();
        private final ScheduledExecutorService executor;
        private final AtomicLong tick = new AtomicLong();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        /**
         * Drives the mild {@link FlightState} jitter (satellites/hdop/rssi); seeded from a hash of
         * {@link #deviceId} so the whole per-tick {@link FlightState} sequence is reproducible for a
         * given device id, touched only by this runtime's single scheduler thread.
         */
        private final Random flightStateJitter;
        /** Cumulative distance flown along {@link #routePlan}; touched only by this runtime's single scheduler thread. */
        private double distanceMeters;

        DeviceRuntime(DeviceId deviceId, double centerLatitude, double centerLongitude, long periodMillis,
                      RoutePlan routePlan, double speedMps, double batteryDrainPercentPerSecond) {
            this.deviceId = deviceId;
            this.centerLatitude = centerLatitude;
            this.centerLongitude = centerLongitude;
            this.periodMillis = periodMillis;
            this.routePlan = routePlan;
            this.speedMps = speedMps;
            this.batteryDrainPercentPerSecond = batteryDrainPercentPerSecond;
            this.flightStateJitter = new Random(seedFor(deviceId));
            this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "sim-telemetry-" + deviceId.value());
                thread.setDaemon(true);
                return thread;
            });
        }

        /** Deterministic per-device seed: a device id always yields the same {@link FlightState} jitter sequence. */
        private static long seedFor(DeviceId deviceId) {
            return deviceId.value().getMostSignificantBits() ^ deviceId.value().getLeastSignificantBits();
        }

        void start() {
            executor.scheduleAtFixedRate(this::sampleAndPublish, 0, periodMillis, TimeUnit.MILLISECONDS);
        }

        private void sampleAndPublish() {
            if (closed.get()) {
                return;
            }
            try {
                long n = tick.getAndIncrement();
                double elapsedSeconds = (n * periodMillis) / 1000.0;
                double batteryPercent = Math.max(0.0, 100.0 - batteryDrainPercentPerSecond * elapsedSeconds);
                FlightState flightState = flightStateFor(n, batteryPercent);
                Telemetry sample = routePlan != null
                        ? routeSample(batteryPercent, flightState) : circularSample(n, batteryPercent, flightState);
                publisher.submit(sample);
            } catch (RuntimeException e) {
                publisher.closeExceptionally(e);
                close();
            }
        }

        /** The tick just advances distance by {@code speedMps * tickSeconds} and asks the plan for the rest. */
        private Telemetry routeSample(double batteryPercent, FlightState flightState) {
            distanceMeters += speedMps * (periodMillis / 1000.0);
            RoutePlan.Position position = routePlan.positionAt(distanceMeters);
            return new Telemetry(deviceId, Instant.now(), position.latitude(), position.longitude(),
                    position.altitudeMeters(), position.headingDegrees(), batteryPercent, Map.of(), flightState);
        }

        /** Unchanged since before CT-a: a point on a ~{@value #TRACK_RADIUS_METERS}m circle, tangent heading. */
        private Telemetry circularSample(long n, double batteryPercent, FlightState flightState) {
            double angle = (n % TICKS_PER_LAP) / (double) TICKS_PER_LAP * 2 * Math.PI;

            double northMeters = TRACK_RADIUS_METERS * Math.cos(angle);
            double eastMeters = TRACK_RADIUS_METERS * Math.sin(angle);
            double latitude = centerLatitude + Math.toDegrees(northMeters / EARTH_RADIUS_METERS);
            double longitude = centerLongitude + Math.toDegrees(
                    eastMeters / (EARTH_RADIUS_METERS * Math.cos(Math.toRadians(centerLatitude))));

            // Heading tangent to the circle (bearing from north, clockwise), the direction
            // of travel as angle increases along the (northMeters, eastMeters) parametrization.
            double headingDegrees = (Math.toDegrees(Math.atan2(Math.cos(angle), -Math.sin(angle))) + 360.0) % 360.0;

            return new Telemetry(deviceId, Instant.now(), latitude, longitude, null, headingDegrees,
                    batteryPercent, Map.of(), flightState);
        }

        /**
         * Synthetic {@link FlightState} for tick {@code n}: disarmed with a ramping GPS fix and one
         * arming blocker for the first {@value #STARTUP_DISARMED_TICKS} ticks (startup realism), then
         * a nominal armed Loiter state — unless {@code batteryPercent} (the same drained value the
         * sample's own {@code batteryPercent} carries) has crossed the RTL/Land thresholds, gating the
         * "battery-driven drama" behind having armed in the first place (an aircraft can't RTL/Land
         * while still on the ground disarmed).
         */
        private FlightState flightStateFor(long n, double batteryPercent) {
            int satellites = Math.max(0, NOMINAL_SATELLITES + jitterInt(1));
            double hdop = Math.max(0.0, NOMINAL_HDOP + jitterDouble(0.1));
            int rssiPercent = clampPercent(NOMINAL_RSSI_PERCENT + jitterInt(3));

            if (n < STARTUP_DISARMED_TICKS) {
                int gpsFixType = (int) Math.min(3L, 1L + n);
                return new FlightState(FLIGHT_STATE_FIRMWARE, FLIGHT_MODE_LOITER, false, false, gpsFixType,
                        satellites, hdop, rssiPercent, List.of(STARTUP_ARMING_BLOCKER));
            }
            if (batteryPercent < LAND_BATTERY_PERCENT_THRESHOLD) {
                return new FlightState(FLIGHT_STATE_FIRMWARE, FLIGHT_MODE_LAND, true, true, 3,
                        satellites, hdop, rssiPercent, List.of());
            }
            if (batteryPercent < RTL_BATTERY_PERCENT_THRESHOLD) {
                return new FlightState(FLIGHT_STATE_FIRMWARE, FLIGHT_MODE_RTL, true, true, 3,
                        satellites, hdop, rssiPercent, List.of());
            }
            return new FlightState(FLIGHT_STATE_FIRMWARE, FLIGHT_MODE_LOITER, true, false, 3,
                    satellites, hdop, rssiPercent, List.of());
        }

        /** Deterministic (seeded) integer jitter in {@code [-magnitude, magnitude]}; 0 if magnitude &lt;= 0. */
        private int jitterInt(int magnitude) {
            return magnitude <= 0 ? 0 : flightStateJitter.nextInt(2 * magnitude + 1) - magnitude;
        }

        /** Deterministic (seeded) double jitter in {@code [-magnitude, magnitude]}; 0 if magnitude &lt;= 0. */
        private double jitterDouble(double magnitude) {
            return magnitude <= 0 ? 0.0 : (flightStateJitter.nextDouble() * 2 - 1) * magnitude;
        }

        private static int clampPercent(int value) {
            return Math.max(0, Math.min(100, value));
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                executor.shutdown();
                publisher.close();
            }
        }
    }
}
