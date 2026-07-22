package com.drones.vision.api.dto;

import com.drones.vision.application.AssetSpec;
import com.drones.vision.application.DeviceRegistration;
import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.StreamDescriptor;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Request body for {@code POST /api/assets}.
 *
 * <p>{@code attributes} is optional; a missing/{@code null} value is treated
 * as an empty map. {@code devices} registers each entry as a new low-level
 * {@code Device} (name/protocol/uri, capabilities defaulting to {@link
 * Capability#VIDEO} only — mirroring {@link RegisterDeviceRequest}'s
 * Phase-1 capability default, see {@link DeviceSpec#capabilities()}) and
 * wraps them under the new asset in one call, per the asset-first
 * registration flow. At least one device is required — this is enforced by
 * {@link AssetSpec}'s own validation, not duplicated here.
 *
 * @param displayName human-readable name (e.g. "my drone"); must not be blank
 * @param category    the asset's category slug; must be a known category (validated by the service)
 * @param attributes  free-form key/value attributes; may be {@code null} (treated as empty)
 * @param devices     the device(s) to register for this asset; must contain at least one entry
 */
public record CreateAssetRequest(String displayName, String category, Map<String, String> attributes,
                                  List<DeviceSpec> devices) {

    /**
     * Validates and converts this request into an {@link AssetSpec}.
     *
     * @return the input for {@code AssetService#create}
     * @throws IllegalArgumentException if {@code displayName} is blank, {@code category} is not a
     *                                   lower-case-kebab slug, {@code devices} is empty, or any device
     *                                   entry fails its own validation (see {@link DeviceSpec#toRegistration()})
     */
    public AssetSpec toSpec() {
        CategoryId categoryId = new CategoryId(category);
        List<DeviceRegistration> registrations = (devices == null ? List.<DeviceSpec>of() : devices)
                .stream()
                .map(DeviceSpec::toRegistration)
                .toList();
        return new AssetSpec(displayName, categoryId, attributes == null ? Map.of() : attributes, registrations);
    }

    /**
     * One device to register alongside the new asset.
     *
     * <p>{@code options} is optional; a missing/{@code null} value is treated
     * as an empty map — same shape and validation as {@link RegisterDeviceRequest}
     * (minus {@code type}, which no longer exists). {@code capabilities} is
     * optional; a missing/empty value defaults to {@code Set.of(Capability.VIDEO)}.
     * When present, each entry must match a {@link Capability} name
     * case-insensitively (see {@link CapabilityParsing}) — this is what lets an
     * asset device be registered with {@link Capability#TELEMETRY} so {@code
     * UsageTracker} records positions for it.
     *
     * @param name         human-readable device name; must not be blank
     * @param protocol     lower-case protocol key selecting the ingest adapter (e.g. {@code "sim"}, {@code "rtsp"})
     * @param uri          the stream's resource locator
     * @param options      adapter-specific parameters; may be {@code null} (treated as empty)
     * @param capabilities capability names (see {@link Capability}); may be {@code null}/empty
     *                     (defaults to {@code [VIDEO]})
     */
    public record DeviceSpec(String name, String protocol, String uri, Map<String, String> options,
                              List<String> capabilities) {

        /**
         * Validates and converts this device entry into a {@link DeviceRegistration}.
         *
         * @return the input device registration
         * @throws IllegalArgumentException if any required field is missing/blank, {@code uri} is not a
         *                                   valid URI, or {@code capabilities} contains an unknown name
         */
        public DeviceRegistration toRegistration() {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("device name must not be blank");
            }
            if (protocol == null || protocol.isBlank()) {
                throw new IllegalArgumentException("device protocol must not be blank");
            }
            if (uri == null || uri.isBlank()) {
                throw new IllegalArgumentException("device uri must not be blank");
            }

            URI parsedUri;
            try {
                parsedUri = URI.create(uri);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid device uri: " + uri, e);
            }

            StreamDescriptor descriptor =
                    new StreamDescriptor(protocol, parsedUri, options == null ? Map.of() : options);
            return new DeviceRegistration(name, CapabilityParsing.parse(capabilities), descriptor);
        }
    }
}
