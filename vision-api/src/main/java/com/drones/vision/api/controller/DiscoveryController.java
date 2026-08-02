package com.drones.vision.api.controller;

import com.drones.vision.api.dto.ScanRequestDto;
import com.drones.vision.api.dto.ScanResultResponse;
import com.drones.vision.application.discovery.DiscoveryService;
import com.drones.vision.application.discovery.DiscoveryScanSpec;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Driving REST adapter for scanning connected devices.
 *
 * <p>Constructor-injected with {@link DiscoveryService} only. Per the
 * hexagonal dependency rule (ARCHITECTURE.md §2, enforced by ArchUnit), this
 * module depends only on {@code vision-domain} and {@code
 * vision-application} — never on an adapter, so the concrete discovery
 * mechanisms ({@code adapter-discovery}) are invisible here.
 */
@RestController
public class DiscoveryController {

    private final DiscoveryService discoveryService;

    public DiscoveryController(DiscoveryService discoveryService) {
        this.discoveryService = Objects.requireNonNull(discoveryService, "discoveryService must not be null");
    }

    /**
     * Scans for connected devices across all (or a subset of) registered
     * discovery mechanisms. The optional request body's fields, if present,
     * override the corresponding defaults from {@link
     * DiscoveryScanSpec#defaults()}; everything else comes from the defaults.
     *
     * @param request optional overrides; {@code null}/absent means use every default
     * @return the aggregated scan result
     */
    @PostMapping("/api/discovery/scan")
    public ScanResultResponse scan(@RequestBody(required = false) ScanRequestDto request) {
        DiscoveryScanSpec scanRequest = (request == null ? ScanRequestDto.EMPTY : request).toScanRequest();
        return ScanResultResponse.from(discoveryService.scan(scanRequest));
    }
}
