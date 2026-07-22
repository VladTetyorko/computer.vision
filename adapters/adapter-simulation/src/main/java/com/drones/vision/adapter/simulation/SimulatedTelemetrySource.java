package com.drones.vision.adapter.simulation;

import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.Telemetry;
import com.drones.vision.domain.port.out.TelemetrySourcePort;

import java.time.Instant;
import java.util.Map;
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
 * asset model plan's demo goal).
 *
 * <p>Supports devices that both expose {@link Capability#TELEMETRY} and use
 * {@code "sim"} as their {@link com.drones.vision.domain.model.StreamDescriptor#protocol()}
 * — i.e. the same simulated device a {@link SimulatedVideoSource} would also
 * serve. Recognized {@link com.drones.vision.domain.model.StreamDescriptor#options()}
 * keys (all optional):
 * <ul>
 *   <li>{@code lat} — track center latitude, default {@value #DEFAULT_CENTER_LATITUDE}</li>
 *   <li>{@code lon} — track center longitude, default {@value #DEFAULT_CENTER_LONGITUDE}</li>
 * </ul>
 *
 * <p>Each {@link #open(Device)} call starts a dedicated, single-threaded
 * {@link ScheduledExecutorService} that, once per period (1 Hz by default —
 * see the package-private {@link #SimulatedTelemetrySource(long)} test seam
 * for a shorter interval), computes the next point on a ~{@value
 * #TRACK_RADIUS_METERS}m-radius circle around the center, a heading tangent
 * to that circle, and a battery level draining {@value
 * #BATTERY_DRAIN_PERCENT_PER_SECOND}%/s from a full charge, and submits a
 * {@link Telemetry} sample to a per-device {@link SubmissionPublisher}. {@link
 * #close(DeviceId)} stops that executor and closes the publisher; both are
 * idempotent, matching {@link TelemetrySourcePort}'s contract.
 *
 * <p>Plain class with no framework dependency — instantiated directly by
 * {@code vision-app}'s wiring configuration.
 */
public final class SimulatedTelemetrySource implements TelemetrySourcePort {

    private static final String PROTOCOL = "sim";

    static final double DEFAULT_CENTER_LATITUDE = 50.45;
    static final double DEFAULT_CENTER_LONGITUDE = 30.52;
    static final double TRACK_RADIUS_METERS = 200.0;
    static final double BATTERY_DRAIN_PERCENT_PER_SECOND = 0.05;

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

        DeviceRuntime runtime = new DeviceRuntime(device.id(), centerLatitude, centerLongitude, periodMillis);
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

    /** Per-open runtime: a scheduled sample generator feeding a {@link SubmissionPublisher}. */
    private static final class DeviceRuntime {
        private final DeviceId deviceId;
        private final double centerLatitude;
        private final double centerLongitude;
        private final long periodMillis;
        private final SubmissionPublisher<Telemetry> publisher = new SubmissionPublisher<>();
        private final ScheduledExecutorService executor;
        private final AtomicLong tick = new AtomicLong();
        private final AtomicBoolean closed = new AtomicBoolean(false);

        DeviceRuntime(DeviceId deviceId, double centerLatitude, double centerLongitude, long periodMillis) {
            this.deviceId = deviceId;
            this.centerLatitude = centerLatitude;
            this.centerLongitude = centerLongitude;
            this.periodMillis = periodMillis;
            this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "sim-telemetry-" + deviceId.value());
                thread.setDaemon(true);
                return thread;
            });
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
                double angle = (n % TICKS_PER_LAP) / (double) TICKS_PER_LAP * 2 * Math.PI;

                double northMeters = TRACK_RADIUS_METERS * Math.cos(angle);
                double eastMeters = TRACK_RADIUS_METERS * Math.sin(angle);
                double latitude = centerLatitude + Math.toDegrees(northMeters / EARTH_RADIUS_METERS);
                double longitude = centerLongitude + Math.toDegrees(
                        eastMeters / (EARTH_RADIUS_METERS * Math.cos(Math.toRadians(centerLatitude))));

                // Heading tangent to the circle (bearing from north, clockwise), the direction
                // of travel as angle increases along the (northMeters, eastMeters) parametrization.
                double headingDegrees = (Math.toDegrees(Math.atan2(Math.cos(angle), -Math.sin(angle))) + 360.0) % 360.0;

                double elapsedSeconds = (n * periodMillis) / 1000.0;
                double batteryPercent = Math.max(0.0, 100.0 - BATTERY_DRAIN_PERCENT_PER_SECOND * elapsedSeconds);

                Telemetry sample = new Telemetry(deviceId, Instant.now(), latitude, longitude, null, headingDegrees,
                        batteryPercent, Map.of());
                publisher.submit(sample);
            } catch (RuntimeException e) {
                publisher.closeExceptionally(e);
                close();
            }
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                executor.shutdown();
                publisher.close();
            }
        }
    }
}
