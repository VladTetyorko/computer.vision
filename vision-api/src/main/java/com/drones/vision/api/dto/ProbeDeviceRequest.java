package com.drones.vision.api.dto;

import com.drones.vision.domain.model.StreamDescriptor;

import java.net.URI;
import java.util.Map;

/**
 * Request body for {@code POST /api/devices/probe} (docs/UX-REWORK-PLAN.md §U-d item 3,
 * UX-DESIGN.md §5.1's "test-before-save" wizard step) — the exact same connection shape {@code
 * RegisterDeviceRequest} carries, minus a name and capabilities, since a probe never registers
 * anything.
 *
 * @param protocol lower-case protocol key selecting the ingest adapter (e.g. {@code "sim"},
 *                 {@code "rtsp"}); mirrors {@link RegisterDeviceRequest}'s own field exactly,
 *                 including not normalizing case here — {@link StreamDescriptor}'s own compact
 *                 constructor is what actually rejects a non-lower-case value (400)
 * @param uri      the resource locator to probe
 * @param options  adapter-specific parameters; may be {@code null} (treated as empty)
 */
public record ProbeDeviceRequest(String protocol, String uri, Map<String, String> options) {

    /**
     * Validates and converts this request into a {@link StreamDescriptor}.
     *
     * @return the descriptor to hand {@code ProbeService#probe}
     * @throws IllegalArgumentException if {@code protocol}/{@code uri} is missing/blank, {@code uri}
     *                                   is not a valid URI, or {@code protocol} is not lower-case
     *                                   (via {@link StreamDescriptor}'s own validation) — a
     *                                   malformed request (400), distinct from a probe that fails
     *                                   once a valid descriptor is actually attempted (422)
     */
    public StreamDescriptor toDescriptor() {
        if (protocol == null || protocol.isBlank()) {
            throw new IllegalArgumentException("protocol must not be blank");
        }
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("uri must not be blank");
        }
        URI parsedUri;
        try {
            parsedUri = URI.create(uri);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid uri: " + uri, e);
        }
        return new StreamDescriptor(protocol, parsedUri, options == null ? Map.of() : options);
    }
}
