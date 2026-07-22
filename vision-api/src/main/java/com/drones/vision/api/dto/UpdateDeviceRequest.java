package com.drones.vision.api.dto;

import com.drones.vision.application.DeviceEdit;
import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.StreamDescriptor;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Request body for {@code PATCH /api/devices/{id}}.
 *
 * <p>Partial by design: any field left out of the JSON stays as it is. Covers the ordinary
 * maintenance a source needs over its life — renamed, moved to a new IP, password rotated,
 * transport options tuned — none of which should cost the user their history by forcing a
 * delete-and-re-add.
 *
 * <p>The stream descriptor is replaced as a unit: {@code protocol} and {@code uri} must be sent
 * together (with {@code options} optional), or all three omitted to leave the stream untouched.
 * Sending {@code options} alone is rejected rather than silently guessing which protocol and URI
 * they belong to.
 *
 * @param name         replacement name, or absent to keep the current one; must not be blank when present
 * @param protocol     replacement ingest protocol key; required when changing the stream
 * @param uri          replacement resource locator; required when changing the stream
 * @param options      replacement adapter-specific parameters; only meaningful alongside protocol and uri
 * @param capabilities replacement capability names, or absent to keep the current set; must not be empty when present
 */
public record UpdateDeviceRequest(String name, String protocol, String uri, Map<String, String> options,
                                   List<String> capabilities) {

    /** No body at all: an edit that changes nothing. */
    public static final UpdateDeviceRequest EMPTY = new UpdateDeviceRequest(null, null, null, null, null);

    /**
     * Maps this request to the application-level edit.
     *
     * @return the partial edit to apply
     * @throws IllegalArgumentException if a present field is invalid, or the stream is only partly specified
     */
    public DeviceEdit toEdit() {
        return new DeviceEdit(name, parseCapabilities(), parseStream());
    }

    private Set<Capability> parseCapabilities() {
        if (capabilities == null) {
            return null; // absent: leave unchanged
        }
        if (capabilities.isEmpty()) {
            // An explicit empty list is a mistake, not "no capabilities": a device that exposes
            // nothing cannot stream, so refuse rather than quietly defaulting to VIDEO.
            throw new IllegalArgumentException("capabilities must not be empty");
        }
        return CapabilityParsing.parse(capabilities);
    }

    private StreamDescriptor parseStream() {
        if (protocol == null && uri == null && options == null) {
            return null; // absent: leave unchanged
        }
        if (protocol == null || protocol.isBlank() || uri == null || uri.isBlank()) {
            throw new IllegalArgumentException(
                    "changing the stream requires both protocol and uri; send them together with any options");
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
