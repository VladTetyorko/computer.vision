package com.drones.vision.warehouse.application.discovery;

/**
 * Finds candidate devices on the network or host.
 *
 * <p>One interface, one implementation ({@link DefaultDiscoveryService}), which fans out to every
 * registered {@code DeviceDiscoveryPort} in parallel and aggregates.
 */
public interface DiscoveryService {

    /**
     * Runs a scan, bounded by the request's timeout.
     *
     * <p>Never throws for an individual scanner's failure — a broken scanner is reported in
     * {@link DiscoveryScanResult#failedMethods()}, so one bad adapter cannot fail the whole scan.
     *
     * @param request what to scan and for how long
     * @return the candidates found, and which methods failed
     * @throws IllegalArgumentException if the request names an unknown method
     */
    DiscoveryScanResult scan(DiscoveryScanSpec request);
}
