package com.drones.vision.flight.domain.port;

import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.Telemetry;

import java.util.concurrent.Flow;

/**
 * Driven port: obtain a live telemetry feed for a device (GPS, attitude,
 * battery, ...).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #open(Device)} returns a per-open, hot, live publisher, with
 *       the same delivery semantics as {@link VideoSourcePort#open}: not
 *       every device has a telemetry source, so {@link #supports(Device)}
 *       must be checked first.</li>
 *   <li>{@link #close(DeviceId)} stops the feed and releases resources; it
 *       must be idempotent.</li>
 * </ul>
 *
 * <h2>Backpressure</h2>
 * Telemetry publishes via {@link java.util.concurrent.Flow.Publisher} for
 * the same reason as video: consumers pull at the rate they can handle via
 * {@link Flow.Subscription#request(long)}. Telemetry updates are naturally
 * low-rate and small compared to video, but adapters must still apply a
 * latest-wins drop policy when demand is exhausted rather than buffering
 * unboundedly or blocking the source.
 *
 * <h2>Threading</h2>
 * {@code supports}, {@code open}, and {@code close} must be safe to call
 * concurrently for different devices; publisher callbacks may run on
 * adapter-managed threads.
 */
public interface TelemetrySourcePort {

    /**
     * Whether this adapter can provide telemetry for the given device.
     *
     * @param device the device to check
     * @return {@code true} if this adapter can {@link #open(Device)} it
     */
    boolean supports(Device device);

    /**
     * Opens a live, hot publisher of telemetry for the given device.
     *
     * @param device the device to read telemetry from
     * @return a per-open publisher of telemetry samples
     */
    Flow.Publisher<Telemetry> open(Device device);

    /**
     * Stops the telemetry feed for the given device and releases adapter
     * resources. Idempotent.
     *
     * @param id the device whose feed to close
     */
    void close(DeviceId id);
}
