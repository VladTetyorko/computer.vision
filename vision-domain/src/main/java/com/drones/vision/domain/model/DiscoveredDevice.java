package com.drones.vision.domain.model;

import java.net.URI;
import java.util.Map;

/**
 * A candidate device found by a discovery scan.
 *
 * <p>A {@code DiscoveredDevice} is a <b>suggestion</b>, not a registered
 * {@link Device} — the user turns it into one through the existing register
 * flow ({@code RegisterDeviceUseCase}). Some discovery mechanisms cannot
 * produce a ready-to-use stream (e.g. ONVIF needs credentials for {@code
 * GetStreamUri}, which requires user input); those candidates are still
 * returned with whatever info the mechanism has, leaving {@code
 * suggestedStream} (and/or {@code suggestedCategory}) {@code null}.
 *
 * @param method            the discovery mechanism that found this candidate (the producing {@code DeviceDiscoveryPort#method()} key); must not be blank
 * @param name              human-readable name or best-effort label for the candidate; must not be blank
 * @param address           network or local address of the candidate
 * @param suggestedCategory best-guess category, or {@code null} if the mechanism cannot infer one
 * @param suggestedStream   ready-to-use stream descriptor, or {@code null} if the mechanism cannot produce one
 * @param details           mechanism-specific extra info (e.g. raw scopes, service name); defensively copied to an immutable map
 */
public record DiscoveredDevice(String method, String name, URI address, CategoryId suggestedCategory,
                                StreamDescriptor suggestedStream, Map<String, String> details) {

    public DiscoveredDevice {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("DiscoveredDevice method must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("DiscoveredDevice name must not be blank");
        }
        if (address == null) {
            throw new IllegalArgumentException("DiscoveredDevice address must not be null");
        }
        if (details == null) {
            throw new IllegalArgumentException("DiscoveredDevice details must not be null");
        }
        details = Map.copyOf(details);
    }
}
