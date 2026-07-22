package com.drones.vision.domain.port.out;

import com.drones.vision.domain.model.DiscoveredDevice;

import java.time.Duration;
import java.util.List;

/**
 * Driven port: one discovery mechanism (mDNS, ONVIF WS-Discovery, V4L2
 * enumeration, ...) able to find candidate devices on the network or host.
 *
 * <p>One implementation exists per discovery mechanism. The application
 * layer ({@code DiscoveryService}) fans out to every registered
 * implementation in parallel and aggregates results; adding a new mechanism
 * means adding a new adapter behind this port, with no change to core code
 * (open/closed) — the same extension pattern as {@link VideoSourcePort}.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #method()} returns a stable, lower-case key identifying this
 *       mechanism (e.g. {@code "onvif"}, {@code "mdns"}, {@code "v4l2"}),
 *       used to select which mechanisms run and to report failures.</li>
 *   <li>{@link #scan(Duration)} is blocking and must return within
 *       approximately {@code timeout} — implementations own their own
 *       internal time-boxing (e.g. closing a multicast socket when the
 *       timeout elapses) rather than relying solely on the caller
 *       interrupting the calling thread.</li>
 *   <li>An empty list means "nothing found on this scan", not an error.</li>
 *   <li>Genuine failures (e.g. socket bind failure) may be thrown; the
 *       caller isolates per-adapter failures so one failing or slow
 *       mechanism never fails or stalls the whole scan.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * {@link #scan(Duration)} may be called from any thread (the application
 * layer runs each registered port on its own virtual thread) and must be
 * safe to call concurrently for independent invocations.
 */
public interface DeviceDiscoveryPort {

    /**
     * @return the stable, lower-case key identifying this discovery mechanism
     */
    String method();

    /**
     * Blocking scan for candidate devices. Must return within approximately
     * {@code timeout}.
     *
     * @param timeout how long to scan for
     * @return discovered candidates, possibly empty; never {@code null}
     */
    List<DiscoveredDevice> scan(Duration timeout);
}
