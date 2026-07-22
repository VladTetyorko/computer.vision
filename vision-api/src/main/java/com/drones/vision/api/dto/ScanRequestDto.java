package com.drones.vision.api.dto;

import com.drones.vision.application.ScanRequest;

import java.time.Duration;
import java.util.Set;

/**
 * Optional request body for {@code POST /api/discovery/scan}.
 *
 * <p>Both fields are optional; a missing/{@code null} field falls back to
 * the corresponding value from {@link ScanRequest#defaults()} — a 4-second
 * timeout across every registered discovery mechanism. An absent request
 * body maps to {@link #EMPTY}, which behaves the same way.
 *
 * @param timeoutMs how long each discovery mechanism is given to scan, in milliseconds; {@code null} uses the default
 * @param methods   which mechanisms to run, by {@code DeviceDiscoveryPort#method()} key; {@code null} uses the default (every registered mechanism); an explicit empty set also means "every registered mechanism" (see {@link ScanRequest})
 */
public record ScanRequestDto(Long timeoutMs, Set<String> methods) {

    /** No body: use every default from {@link ScanRequest#defaults()}. */
    public static final ScanRequestDto EMPTY = new ScanRequestDto(null, null);

    /**
     * Converts this request into a {@link ScanRequest}, applying defaults
     * for any absent field.
     *
     * @return the input for {@code DiscoveryService#scan}
     * @throws IllegalArgumentException if {@code timeoutMs} is present but not positive
     */
    public ScanRequest toScanRequest() {
        ScanRequest defaults = ScanRequest.defaults();
        Duration timeout = timeoutMs != null ? Duration.ofMillis(timeoutMs) : defaults.timeout();
        Set<String> effectiveMethods = methods != null ? methods : defaults.methods();
        return new ScanRequest(timeout, effectiveMethods);
    }
}
