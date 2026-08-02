package com.drones.vision.adapter.simulation;

import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.FlightState;
import com.drones.vision.domain.model.Telemetry;
import com.drones.vision.domain.port.out.TelemetrySourcePort;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
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
 *   <li>{@code lat} — circular-track center latitude, default {@link TelemetrySettings#centerLatitude()};
 *       ignored once a valid {@code route} is given</li>
 *   <li>{@code lon} — circular-track center longitude, default {@link TelemetrySettings#centerLongitude()};
 *       ignored once a valid {@code route} is given</li>
 *   <li>{@code route} — {@code lat,lon[,altM];lat,lon[,altM];…}, at least 2 points (start →
 *       checkpoints → end); absent or malformed (fewer than 2 points, an unparseable number) means
 *       "no route" — the circular track above is the back-compat default</li>
 *   <li>{@code speedMps} — cruise speed along a route, default {@link RoutePlan#DEFAULT_SPEED_MPS};
 *       only meaningful with {@code route}; must be positive, else the default applies</li>
 *   <li>{@code routeMode} — {@code loop} (default)/{@code bounce}/{@code once}, case-insensitive;
 *       only meaningful with {@code route}, see {@link RoutePlan.RouteMode}</li>
 *   <li>{@code batteryDrainPerSecond} — overrides {@link TelemetrySettings#batteryDrainPercentPerSecond()}%/s,
 *       whether flying the circular track or a route</li>
 * </ul>
 *
 * <p>Each {@link #open(Device)} call starts a dedicated, single-threaded
 * {@link ScheduledExecutorService} that, once per period (this instance's
 * {@link TelemetrySettings#periodMillis()}, 1 Hz by default — see the
 * package-private {@link #SimulatedTelemetrySource(long)} test seam for a
 * shorter interval), computes the next sample and submits a {@link
 * Telemetry} to a per-device {@link SubmissionPublisher}. Without a valid
 * {@code route} option, that sample is the next point on a circle of this
 * instance's {@link TelemetrySettings#trackRadiusMeters()} around the {@code
 * lat}/{@code lon} center with a heading tangent to it (unchanged since
 * before CT-a); with one, the runtime instead advances a cumulative {@code
 * distanceMeters} by {@code speedMps * tickSeconds} every tick and asks the
 * parsed {@link RoutePlan} for the position/heading/altitude there — all
 * route-mode (loop/bounce/once) folding logic lives in {@link RoutePlan}
 * itself, not here. Either way, battery drains {@code batteryDrainPerSecond}
 * %/s (default {@link TelemetrySettings#batteryDrainPercentPerSecond()}) from
 * a full charge. {@link #close(DeviceId)} stops that executor and closes the
 * publisher; both are idempotent, matching {@link TelemetrySourcePort}'s
 * contract.
 *
 * <p>Plain class with no framework dependency — instantiated directly by
 * {@code vision-app}'s wiring configuration.
 *
 * <p>Every sample also carries a synthetic {@link FlightState} (docs/FC-INTEGRATIONS-PLAN.md F-c),
 * computed by {@link SyntheticFlightState} — see that class for the startup ramp / nominal cruise /
 * battery-driven RTL-Land details and its own deterministic-per-device-id guarantee.
 */
public final class SimulatedTelemetrySource implements TelemetrySourcePort {

    private static final String PROTOCOL = "sim";

    /** One full lap of the circular track every this many samples. */
    private static final long TICKS_PER_LAP = 60L;
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private final TelemetrySettings settings;
    private final Map<DeviceId, DeviceRuntime> runtimes = new ConcurrentHashMap<>();

    /** Uses {@link TelemetrySettings#defaults()} (1 Hz) — unchanged pre-extraction behavior. */
    public SimulatedTelemetrySource() {
        this(TelemetrySettings.defaults());
    }

    /**
     * @param settings default center lat/lon, circular-track radius, sample period, and default
     *                 battery drain rate for every device this source serves
     */
    public SimulatedTelemetrySource(TelemetrySettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Test seam: emits at an arbitrary period instead of the configured cadence,
     * so tests can observe several samples without waiting seconds of
     * wall-clock time.
     *
     * @param periodMillis milliseconds between samples; must be positive
     */
    SimulatedTelemetrySource(long periodMillis) {
        this(TelemetrySettings.defaults().withPeriodMillis(periodMillis));
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
        double centerLatitude = doubleOption(device, "lat", settings.centerLatitude());
        double centerLongitude = doubleOption(device, "lon", settings.centerLongitude());
        double speedMps = positiveDoubleOption(device, "speedMps", RoutePlan.DEFAULT_SPEED_MPS);
        double batteryDrainPercentPerSecond =
                doubleOption(device, "batteryDrainPerSecond", settings.batteryDrainPercentPerSecond());
        RoutePlan routePlan = RoutePlan.parse(
                device.stream().options().get("route"), device.stream().options().get("routeMode"));

        DeviceRuntime runtime = new DeviceRuntime(device.id(), centerLatitude, centerLongitude, settings,
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
        private final TelemetrySettings settings;
        /** {@code null} means "no valid route" — fall back to the circular track, unchanged since before CT-a. */
        private final RoutePlan routePlan;
        private final double speedMps;
        private final double batteryDrainPercentPerSecond;
        private final SubmissionPublisher<Telemetry> publisher = new SubmissionPublisher<>();
        private final ScheduledExecutorService executor;
        private final AtomicLong tick = new AtomicLong();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        /**
         * Pure, seeded-per-device {@link FlightState} generator (see {@link SyntheticFlightState}),
         * touched only by this runtime's single scheduler thread.
         */
        private final SyntheticFlightState flightStateGenerator;
        /** Cumulative distance flown along {@link #routePlan}; touched only by this runtime's single scheduler thread. */
        private double distanceMeters;

        DeviceRuntime(DeviceId deviceId, double centerLatitude, double centerLongitude, TelemetrySettings settings,
                      RoutePlan routePlan, double speedMps, double batteryDrainPercentPerSecond) {
            this.deviceId = deviceId;
            this.centerLatitude = centerLatitude;
            this.centerLongitude = centerLongitude;
            this.settings = settings;
            this.routePlan = routePlan;
            this.speedMps = speedMps;
            this.batteryDrainPercentPerSecond = batteryDrainPercentPerSecond;
            this.flightStateGenerator = SyntheticFlightState.forDevice(deviceId);
            this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "sim-telemetry-" + deviceId.value());
                thread.setDaemon(true);
                return thread;
            });
        }

        void start() {
            executor.scheduleAtFixedRate(this::sampleAndPublish, 0, settings.periodMillis(), TimeUnit.MILLISECONDS);
        }

        private void sampleAndPublish() {
            if (closed.get()) {
                return;
            }
            try {
                long n = tick.getAndIncrement();
                double elapsedSeconds = (n * settings.periodMillis()) / 1000.0;
                double batteryPercent = Math.max(0.0, 100.0 - batteryDrainPercentPerSecond * elapsedSeconds);
                FlightState sampleFlightState = flightStateGenerator.at(n, batteryPercent);
                Telemetry sample = routePlan != null
                        ? routeSample(batteryPercent, sampleFlightState)
                        : circularSample(n, batteryPercent, sampleFlightState);
                publisher.submit(sample);
            } catch (RuntimeException e) {
                publisher.closeExceptionally(e);
                close();
            }
        }

        /** The tick just advances distance by {@code speedMps * tickSeconds} and asks the plan for the rest. */
        private Telemetry routeSample(double batteryPercent, FlightState flightState) {
            distanceMeters += speedMps * (settings.periodMillis() / 1000.0);
            RoutePlan.Position position = routePlan.positionAt(distanceMeters);
            return new Telemetry(deviceId, Instant.now(), position.latitude(), position.longitude(),
                    position.altitudeMeters(), position.headingDegrees(), batteryPercent, Map.of(), flightState);
        }

        /** Unchanged since before CT-a: a point on the configured-radius circle, tangent heading. */
        private Telemetry circularSample(long n, double batteryPercent, FlightState flightState) {
            double angle = (n % TICKS_PER_LAP) / (double) TICKS_PER_LAP * 2 * Math.PI;

            double trackRadiusMeters = settings.trackRadiusMeters();
            double northMeters = trackRadiusMeters * Math.cos(angle);
            double eastMeters = trackRadiusMeters * Math.sin(angle);
            double latitude = centerLatitude + Math.toDegrees(northMeters / EARTH_RADIUS_METERS);
            double longitude = centerLongitude + Math.toDegrees(
                    eastMeters / (EARTH_RADIUS_METERS * Math.cos(Math.toRadians(centerLatitude))));

            // Heading tangent to the circle (bearing from north, clockwise), the direction
            // of travel as angle increases along the (northMeters, eastMeters) parametrization.
            double headingDegrees = (Math.toDegrees(Math.atan2(Math.cos(angle), -Math.sin(angle))) + 360.0) % 360.0;

            return new Telemetry(deviceId, Instant.now(), latitude, longitude, null, headingDegrees,
                    batteryPercent, Map.of(), flightState);
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                executor.shutdown();
                publisher.close();
            }
        }
    }
}
