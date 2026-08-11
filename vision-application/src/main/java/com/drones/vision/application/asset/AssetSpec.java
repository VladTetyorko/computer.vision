package com.drones.vision.application.asset;

import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;

import java.util.List;
import java.util.Map;
import com.drones.vision.application.device.DeviceRegistration;

/**
 * Everything needed to create an asset together with its device(s) in one act.
 *
 * <p>{@code devices} registers brand-new low-level devices for the asset (the original,
 * asset-first flow); {@code existingDeviceIds} instead assigns already-registered, currently
 * unowned devices to the new asset in the same act — the "promote to asset" flow, letting a caller
 * combine both in a single {@code create} rather than creating with a placeholder device and
 * assigning the real one(s) as a second call. At least one device, from either list or both
 * combined, is required — mirroring {@link com.drones.vision.warehouse.domain.model.Asset}'s own "devices
 * must be non-empty" invariant.
 *
 * @param displayName       human-readable name (e.g. "my drone"); must not be blank
 * @param category          the asset's category
 * @param attributes        free-form key/value attributes; defensively copied
 * @param devices           new sources to register for it; may be empty if {@code
 *                          existingDeviceIds} supplies at least one device instead
 * @param existingDeviceIds already-registered, unowned devices to assign to the new asset
 *                          (validated exactly like {@code AssetService#assignDevice} — must exist,
 *                          must not be soft-deleted, must not already belong to another asset);
 *                          defensively copied
 */
public record AssetSpec(String displayName, CategoryId category, Map<String, String> attributes,
                         List<DeviceRegistration> devices, List<DeviceId> existingDeviceIds) {

    public AssetSpec {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("AssetSpec displayName must not be blank");
        }
        if (category == null) {
            throw new IllegalArgumentException("AssetSpec category must not be null");
        }
        if (attributes == null) {
            throw new IllegalArgumentException("AssetSpec attributes must not be null");
        }
        if (devices == null) {
            throw new IllegalArgumentException("AssetSpec devices must not be null");
        }
        if (existingDeviceIds == null) {
            throw new IllegalArgumentException("AssetSpec existingDeviceIds must not be null");
        }
        if (devices.isEmpty() && existingDeviceIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "AssetSpec must include at least one device, via devices or existingDeviceIds");
        }
        attributes = Map.copyOf(attributes);
        devices = List.copyOf(devices);
        existingDeviceIds = List.copyOf(existingDeviceIds);
    }

    /**
     * Convenience constructor for the original, {@code existingDeviceIds}-less shape — defaults it
     * to empty, keeping every pre-existing call site (new devices only) compiling unchanged.
     *
     * @param displayName human-readable name (e.g. "my drone"); must not be blank
     * @param category    the asset's category
     * @param attributes  free-form key/value attributes; defensively copied
     * @param devices     the sources to register for it; must contain at least one
     */
    public AssetSpec(String displayName, CategoryId category, Map<String, String> attributes,
                      List<DeviceRegistration> devices) {
        this(displayName, category, attributes, devices, List.of());
    }
}
