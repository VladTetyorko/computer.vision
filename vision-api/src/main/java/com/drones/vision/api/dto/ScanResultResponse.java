package com.drones.vision.api.dto;

import com.drones.vision.warehouse.application.discovery.DiscoveryScanResult;

import java.util.List;
import java.util.Set;

/**
 * Response body for {@code POST /api/discovery/scan}.
 *
 * @param devices       discovered candidates from the mechanisms that completed successfully within the requested timeout
 * @param failedMethods method keys of mechanisms that failed or did not complete in time
 */
public record ScanResultResponse(List<DiscoveredDeviceResponse> devices, Set<String> failedMethods) {

    /**
     * Maps a {@link DiscoveryScanResult} to its wire representation.
     *
     * @param result the scan result to map
     * @return the response body for {@code result}
     */
    public static ScanResultResponse from(DiscoveryScanResult result) {
        return new ScanResultResponse(
                result.devices().stream().map(DiscoveredDeviceResponse::from).toList(),
                result.failedMethods());
    }
}
