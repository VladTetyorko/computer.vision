package com.drones.vision.api.dto;

import com.drones.vision.warehouse.application.device.DeviceRegistration;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.StreamDescriptor;

import java.net.URI;
import java.util.List;
import java.util.Map;
import com.drones.vision.api.controller.CategoryController;
import com.drones.vision.api.support.CapabilityParsing;

/**
 * Request body for {@code POST /api/devices}.
 *
 * <p>{@code options} is optional; a missing/{@code null} value is treated as
 * an empty map. {@code capabilities} is optional; a missing/empty value
 * defaults to {@code Set.of(Capability.VIDEO)}, preserving the Phase-1
 * VIDEO-only default. When present, each entry must match a {@link
 * Capability} name case-insensitively (see {@link CapabilityParsing}).
 * There is no {@code type} field: the {@code DeviceType} enum was removed in
 * favor of the data-driven category model (see {@code CategoryController})
 * — categories apply to {@code Asset}s (via {@code CreateAssetRequest}), not
 * to raw device registration.
 *
 * @param name         human-readable device name; must not be blank
 * @param protocol     lower-case protocol key selecting the ingest adapter (e.g. {@code "sim"}, {@code "rtsp"})
 * @param uri          the stream's resource locator
 * @param options      adapter-specific parameters; may be {@code null} (treated as empty)
 * @param capabilities capability names (see {@link Capability}); may be {@code null}/empty
 *                     (defaults to {@code [VIDEO]})
 */
public record RegisterDeviceRequest(String name, String protocol, String uri, Map<String, String> options,
                                     List<String> capabilities) {

    /**
     * Validates and converts this request into a {@link DeviceRegistration}.
     *
     * @return the input for {@code DeviceService#register}
     * @throws IllegalArgumentException if any required field is missing/blank, {@code uri} is not a
     *                                   valid URI, or {@code capabilities} contains an unknown name
     */
    public DeviceRegistration toRegistration() {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
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

        StreamDescriptor descriptor =
                new StreamDescriptor(protocol, parsedUri, options == null ? Map.of() : options);
        return new DeviceRegistration(name, CapabilityParsing.parse(capabilities), descriptor);
    }
}
