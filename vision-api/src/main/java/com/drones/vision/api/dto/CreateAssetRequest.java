package com.drones.vision.api.dto;

import com.drones.vision.application.asset.AssetSpec;
import com.drones.vision.application.device.DeviceRegistration;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamDescriptor;

import java.net.URI;
import java.util.List;
import java.util.Map;
import com.drones.vision.api.support.CapabilityParsing;

/**
 * Request body for {@code POST /api/assets}.
 *
 * <p>{@code attributes} is optional; a missing/{@code null} value is treated
 * as an empty map. {@code devices} registers each entry as a new low-level
 * {@code Device} (name/protocol/uri, capabilities defaulting to {@link
 * Capability#VIDEO} only — mirroring {@link RegisterDeviceRequest}'s
 * Phase-1 capability default, see {@link DeviceSpec#capabilities()}) and
 * wraps them under the new asset in one call, per the asset-first
 * registration flow.
 *
 * <p>{@code deviceIds} is the complementary "promote to asset" flow: it assigns already-registered,
 * currently unowned devices to the new asset in the same call, instead of registering new ones —
 * validated exactly like {@code POST /api/assets/{id}/devices} ({@code AssetService#assignDevice}:
 * must exist, must not be soft-deleted, must not already belong to another asset). {@code devices}
 * and {@code deviceIds} may be combined freely; at least one device between the two is required —
 * this is enforced by {@link AssetSpec}'s own validation, not duplicated here.
 *
 * @param displayName human-readable name (e.g. "my drone"); must not be blank
 * @param category    the asset's category slug; must be a known category (validated by the service)
 * @param attributes  free-form key/value attributes; may be {@code null} (treated as empty)
 * @param devices     new device(s) to register for this asset; may be {@code null}/empty if {@code
 *                    deviceIds} supplies at least one device instead
 * @param deviceIds   existing device ids (canonical UUID strings) to assign to this asset; may be
 *                    {@code null}/empty
 */
public record CreateAssetRequest(String displayName, String category, Map<String, String> attributes,
                                  List<DeviceSpec> devices, List<String> deviceIds) {

    /**
     * Convenience constructor for the original, {@code deviceIds}-less shape — defaults it to
     * empty, keeping every pre-existing caller (new devices only) working unchanged.
     *
     * @param displayName human-readable name (e.g. "my drone"); must not be blank
     * @param category    the asset's category slug; must be a known category (validated by the service)
     * @param attributes  free-form key/value attributes; may be {@code null} (treated as empty)
     * @param devices     the device(s) to register for this asset; must contain at least one entry
     */
    public CreateAssetRequest(String displayName, String category, Map<String, String> attributes,
                               List<DeviceSpec> devices) {
        this(displayName, category, attributes, devices, List.of());
    }

    /**
     * Validates and converts this request into an {@link AssetSpec}.
     *
     * @return the input for {@code AssetService#create}
     * @throws IllegalArgumentException if {@code displayName} is blank, {@code category} is not a
     *                                   lower-case-kebab slug, both {@code devices} and {@code
     *                                   deviceIds} are empty, any device entry fails its own
     *                                   validation (see {@link DeviceSpec#toRegistration()}), or a
     *                                   {@code deviceIds} entry is not a valid UUID
     */
    public AssetSpec toSpec() {
        CategoryId categoryId = new CategoryId(category);
        List<DeviceRegistration> registrations = (devices == null ? List.<DeviceSpec>of() : devices)
                .stream()
                .map(DeviceSpec::toRegistration)
                .toList();
        List<DeviceId> existingDeviceIds = (deviceIds == null ? List.<String>of() : deviceIds)
                .stream()
                .map(DeviceId::of)
                .toList();
        return new AssetSpec(displayName, categoryId, attributes == null ? Map.of() : attributes, registrations,
                existingDeviceIds);
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
