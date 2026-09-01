package com.drones.vision.warehouse.application.discovery;

import java.util.List;

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

    /**
     * Reachability of every registered discovery mechanism (docs/plans/active/ASSET-FLOWS-PLAN.md
     * &sect;2, A3), one entry per mechanism — read independently of {@link
     * #scan(DiscoveryScanSpec)}, since a caller (the found-devices inbox) needs this even between
     * scans.
     *
     * @return one {@link SourceHealth} per registered mechanism; order not guaranteed
     */
    List<SourceHealth> health();
}
