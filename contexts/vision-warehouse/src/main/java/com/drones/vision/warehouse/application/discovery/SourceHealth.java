package com.drones.vision.warehouse.application.discovery;

import com.drones.vision.warehouse.domain.model.SourceStatus;
import com.drones.vision.warehouse.domain.port.DeviceDiscoveryPort;

import java.time.Instant;

/**
 * One registered discovery mechanism's reachability (docs/plans/active/ASSET-FLOWS-PLAN.md
 * &sect;2, A3) — {@link DiscoveryService#health()}'s per-mechanism read model.
 *
 * @param id         the mechanism's {@link DeviceDiscoveryPort#method()} key (e.g. {@code
 *                   "mediamtx"}, {@code "mdns"}, {@code "onvif"}, {@code "v4l2"})
 * @param status     the mechanism's last-observed reachability
 * @param lastScanAt when this mechanism was last scanned by the reporting {@link DiscoveryService}
 *                   instance, or {@code null} when {@code status} is {@link
 *                   SourceStatus#NEVER_SCANNED} — a fresh station restart has scanned nothing yet,
 *                   regardless of how long the mechanism itself has existed
 */
public record SourceHealth(String id, SourceStatus status, Instant lastScanAt) {

    public SourceHealth {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("SourceHealth id must not be blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("SourceHealth status must not be null");
        }
        if (status == SourceStatus.NEVER_SCANNED && lastScanAt != null) {
            throw new IllegalArgumentException("SourceHealth lastScanAt must be null when status is NEVER_SCANNED");
        }
    }

    /**
     * Convenience constructor for a mechanism never scanned by the reporting service instance:
     * {@link #status()} is {@link SourceStatus#NEVER_SCANNED}, {@link #lastScanAt()} is {@code
     * null}.
     *
     * @param id the mechanism's {@link DeviceDiscoveryPort#method()} key
     */
    public SourceHealth(String id) {
        this(id, SourceStatus.NEVER_SCANNED, null);
    }
}
