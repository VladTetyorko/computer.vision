package com.drones.vision.application;

import com.drones.vision.domain.model.DiscoveredDevice;

import java.util.List;
import java.util.Set;

/**
 * What a discovery scan found — and which scanners failed trying.
 *
 * <p>{@code failedMethods} is part of the result rather than a swallowed log line: a scanner that
 * errored found nothing for a completely different reason than an empty network, and the user
 * needs to be able to tell those apart.
 *
 * @param devices       the candidates found; defensively copied
 * @param failedMethods methods that errored or timed out; defensively copied
 */
public record ScanResult(List<DiscoveredDevice> devices, Set<String> failedMethods) {

    public ScanResult {
        if (devices == null) {
            throw new IllegalArgumentException("ScanResult devices must not be null");
        }
        if (failedMethods == null) {
            throw new IllegalArgumentException("ScanResult failedMethods must not be null");
        }
        devices = List.copyOf(devices);
        failedMethods = Set.copyOf(failedMethods);
    }
}
