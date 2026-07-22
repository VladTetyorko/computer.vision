package com.drones.vision.domain.model;

import java.util.Map;
import java.util.Set;

/**
 * The user-facing object — "my drone": a named, categorized, owned thing
 * that wraps one or more {@link Device}s.
 *
 * <p>Asset is the boundary users interact with; {@code Device} remains the
 * low-level connection endpoint underneath. A drone with an FPV camera and a
 * telemetry link is one asset, two devices — {@code devices} therefore must
 * hold at least one id. {@code attributes} is free-form ({@code category}
 * supplies {@code attributeHints} as UI suggestions, not a rigid schema),
 * which keeps the model generic and migration-free by construction.
 * {@code devices} and {@code attributes} are defensively copied to immutable
 * collections; the {@code with*} methods return new instances since records
 * are immutable.
 *
 * @param id          typed asset identity
 * @param displayName human-readable name (e.g. "my drone"); must not be blank
 * @param category    the asset's category
 * @param ownership   who owns this asset
 * @param devices     the device(s) this asset wraps; defensively copied to an immutable set; must contain at least one id
 * @param attributes  free-form key/value attributes; defensively copied to an immutable map
 * @param state       whether the asset is in service; a deactivated asset refuses to stream
 */
public record Asset(AssetId id, String displayName, CategoryId category, Ownership ownership, Set<DeviceId> devices,
                     Map<String, String> attributes, LifecycleState state) {

    public Asset {
        if (id == null) {
            throw new IllegalArgumentException("Asset id must not be null");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Asset displayName must not be blank");
        }
        if (category == null) {
            throw new IllegalArgumentException("Asset category must not be null");
        }
        if (ownership == null) {
            throw new IllegalArgumentException("Asset ownership must not be null");
        }
        if (devices == null) {
            throw new IllegalArgumentException("Asset devices must not be null");
        }
        if (devices.isEmpty()) {
            throw new IllegalArgumentException("Asset devices must contain at least one device");
        }
        if (attributes == null) {
            throw new IllegalArgumentException("Asset attributes must not be null");
        }
        if (state == null) {
            throw new IllegalArgumentException("Asset state must not be null");
        }
        devices = Set.copyOf(devices);
        attributes = Map.copyOf(attributes);
    }

    /**
     * Creates an asset in service.
     *
     * <p>Newly created assets are always {@link LifecycleState#ACTIVE}; deactivation is an
     * explicit later act, never an accident of construction.
     */
    public Asset(AssetId id, String displayName, CategoryId category, Ownership ownership, Set<DeviceId> devices,
                  Map<String, String> attributes) {
        this(id, displayName, category, ownership, devices, attributes, LifecycleState.ACTIVE);
    }

    /**
     * Whether this asset is in service.
     *
     * @return {@code true} when {@link #state()} is {@link LifecycleState#ACTIVE}
     */
    public boolean isActive() {
        return state == LifecycleState.ACTIVE;
    }

    /**
     * Whether this asset has been soft-deleted.
     *
     * @return {@code true} when {@link #state()} is {@link LifecycleState#DELETED}
     */
    public boolean isDeleted() {
        return state == LifecycleState.DELETED;
    }

    /**
     * Returns a copy of this asset with a different set of devices.
     *
     * @param devices the replacement device set; defensively copied; must contain at least one id
     * @return a new {@code Asset} with {@code devices} replaced
     */
    public Asset withDevices(Set<DeviceId> devices) {
        return new Asset(id, displayName, category, ownership, devices, attributes, state);
    }

    /**
     * Returns a copy of this asset with a different attribute map.
     *
     * @param attributes the replacement attribute map; defensively copied
     * @return a new {@code Asset} with {@code attributes} replaced
     */
    public Asset withAttributes(Map<String, String> attributes) {
        return new Asset(id, displayName, category, ownership, devices, attributes, state);
    }

    /**
     * Returns a copy of this asset with edited descriptive fields.
     *
     * <p>Identity, ownership, device membership and lifecycle state are deliberately not
     * editable here: those change through their own operations, so an ordinary rename can
     * never reassign an asset to another owner by accident.
     *
     * @param displayName the replacement name; must not be blank
     * @param category    the replacement category
     * @param attributes  the replacement attribute map; defensively copied
     * @return a new {@code Asset} with those fields replaced
     */
    public Asset withDetails(String displayName, CategoryId category, Map<String, String> attributes) {
        return new Asset(id, displayName, category, ownership, devices, attributes, state);
    }

    /**
     * Returns a copy of this asset in a different lifecycle state.
     *
     * @param state the replacement state
     * @return a new {@code Asset} with {@code state} replaced
     */
    public Asset withState(LifecycleState state) {
        return new Asset(id, displayName, category, ownership, devices, attributes, state);
    }
}
