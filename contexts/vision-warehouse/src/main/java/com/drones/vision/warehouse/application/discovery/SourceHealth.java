package com.drones.vision.warehouse.application.discovery;

import com.drones.vision.warehouse.domain.model.SourceStatus;
import com.drones.vision.warehouse.domain.port.DeviceDiscoveryPort;

/**
 * One registered discovery mechanism's reachability (docs/plans/active/ASSET-FLOWS-PLAN.md
 * &sect;2, A3) — {@link DiscoveryService#health()}'s per-mechanism read model.
 *
 * @param id     the mechanism's {@link DeviceDiscoveryPort#method()} key (e.g. {@code "mediamtx"},
 *               {@code "mdns"}, {@code "onvif"}, {@code "v4l2"})
 * @param status the mechanism's last-observed reachability
 */
public record SourceHealth(String id, SourceStatus status) {

    public SourceHealth {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("SourceHealth id must not be blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("SourceHealth status must not be null");
        }
    }
}
