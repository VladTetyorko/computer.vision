package com.drones.vision.warehouse.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.kernel.Ownership;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * The user-facing object — "my drone": a named, categorized, owned thing
 * that wraps zero or more {@link Device}s.
 *
 * <p>Asset is the boundary users interact with; {@code Device} remains the
 * low-level connection endpoint underneath. A drone with an FPV camera and a
 * telemetry link is one asset, two devices. {@code attributes} is free-form
 * ({@code category} supplies {@code attributeHints} as UI suggestions, not a
 * rigid schema), which keeps the model generic and migration-free by
 * construction. {@code devices} and {@code attributes} are defensively
 * copied to immutable collections; the {@code with*} methods return new
 * instances since records are immutable.
 *
 * <p><b>Inventory (docs/plans/active/WAREHOUSE-UX-PLAN.md D2/D4):</b> {@code devices} was
 * originally non-empty by invariant for every asset. Now that a category may be non-connected
 * (batteries, spares, radios — {@link DeviceCategory#connected()} {@code false}), an asset in such
 * a category legitimately has zero devices; this record only requires {@code devices} to be
 * non-null. The "at least one device when connected" rule is enforced in the application layer
 * ({@code DefaultAssetService#create}, via {@code CategoryRepositoryPort}), since this record holds
 * a {@link CategoryId}, not the {@link DeviceCategory} itself, and so cannot check {@code
 * connected} on its own. {@code custody}/{@code inventoryState} track where this asset sits in the
 * warehouse-to-field lifecycle; {@code inventoryState} is restricted to the storable values (see
 * {@link InventoryState#storable()}) since {@link InventoryState#ISSUED}/{@link
 * InventoryState#IN_FIELD} are derived on read, never persisted (see {@link
 * InventoryStates#effective}).
 *
 * @param id             typed asset identity
 * @param displayName    human-readable name (e.g. "my drone"); must not be blank
 * @param category       the asset's category
 * @param ownership      who owns this asset
 * @param devices        the device(s) this asset wraps; defensively copied to an immutable set;
 *                       non-null, may be empty for an asset in a non-connected category (see above)
 * @param attributes     free-form key/value attributes; defensively copied to an immutable map
 * @param state          whether the asset is in service; a deactivated asset refuses to stream
 * @param identity       serial/make/model/registration facts, or {@link Identity#NONE}
 * @param custody        who currently holds this asset, or {@link Custody#NONE} (in stock)
 * @param inventoryState this asset's stored inventory state; must satisfy {@link
 *                       InventoryState#storable()}
 * @param createdAt      when this asset was first registered
 * @param updatedAt      when this asset was last changed; must not be before {@code createdAt}
 */
public record Asset(AssetId id, String displayName, CategoryId category, Ownership ownership, Set<DeviceId> devices,
                     Map<String, String> attributes, LifecycleState state, Identity identity, Custody custody,
                     InventoryState inventoryState, Instant createdAt, Instant updatedAt) {

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
        if (attributes == null) {
            throw new IllegalArgumentException("Asset attributes must not be null");
        }
        if (state == null) {
            throw new IllegalArgumentException("Asset state must not be null");
        }
        if (identity == null) {
            throw new IllegalArgumentException("Asset identity must not be null");
        }
        if (custody == null) {
            throw new IllegalArgumentException("Asset custody must not be null");
        }
        if (inventoryState == null) {
            throw new IllegalArgumentException("Asset inventoryState must not be null");
        }
        if (!inventoryState.storable()) {
            throw new IllegalArgumentException(
                    "Asset inventoryState must be IN_STOCK, MAINTENANCE or RETIRED (ISSUED/IN_FIELD are derived): "
                            + inventoryState);
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("Asset createdAt must not be null");
        }
        if (updatedAt == null) {
            throw new IllegalArgumentException("Asset updatedAt must not be null");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "Asset updatedAt must not be before createdAt: " + updatedAt + " < " + createdAt);
        }
        devices = Set.copyOf(devices);
        attributes = Map.copyOf(attributes);
    }

    /**
     * Registers a brand-new asset: {@link LifecycleState#ACTIVE}, stored {@link
     * InventoryState#IN_STOCK} (reads back as {@link InventoryState#ISSUED} immediately if {@code
     * custody} already names a custodian — see {@link InventoryStates#effective}), {@code
     * createdAt}/{@code updatedAt} stamped now.
     *
     * @param id          typed asset identity
     * @param displayName human-readable name; must not be blank
     * @param category    the asset's category
     * @param ownership   who owns it
     * @param devices     the device(s) this asset wraps; may be empty for a non-connected category
     * @param attributes  free-form key/value attributes
     * @param identity    serial/make/model/registration, or {@link Identity#NONE}
     * @param custody     initial custody, or {@link Custody#NONE} to receive it into stock
     * @return the new asset
     */
    public static Asset register(AssetId id, String displayName, CategoryId category, Ownership ownership,
                                  Set<DeviceId> devices, Map<String, String> attributes, Identity identity,
                                  Custody custody) {
        Instant now = Instant.now();
        return new Asset(id, displayName, category, ownership, devices, attributes, LifecycleState.ACTIVE,
                identity, custody, InventoryState.IN_STOCK, now, now);
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
     * @param devices the replacement device set; defensively copied
     * @return a new {@code Asset} with {@code devices} replaced
     */
    public Asset withDevices(Set<DeviceId> devices) {
        return new Asset(id, displayName, category, ownership, devices, attributes, state, identity, custody,
                inventoryState, createdAt, updatedAt);
    }

    /**
     * Returns a copy of this asset with a different attribute map.
     *
     * @param attributes the replacement attribute map; defensively copied
     * @return a new {@code Asset} with {@code attributes} replaced
     */
    public Asset withAttributes(Map<String, String> attributes) {
        return new Asset(id, displayName, category, ownership, devices, attributes, state, identity, custody,
                inventoryState, createdAt, updatedAt);
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
        return new Asset(id, displayName, category, ownership, devices, attributes, state, identity, custody,
                inventoryState, createdAt, updatedAt);
    }

    /**
     * Returns a copy of this asset in a different lifecycle state.
     *
     * @param state the replacement state
     * @return a new {@code Asset} with {@code state} replaced
     */
    public Asset withState(LifecycleState state) {
        return new Asset(id, displayName, category, ownership, devices, attributes, state, identity, custody,
                inventoryState, createdAt, updatedAt);
    }

    /**
     * Returns a copy of this asset with different identity facts.
     *
     * @param identity  the replacement identity
     * @param updatedAt when this change was made
     * @return a new {@code Asset} with {@code identity} and {@code updatedAt} replaced
     */
    public Asset withIdentity(Identity identity, Instant updatedAt) {
        return new Asset(id, displayName, category, ownership, devices, attributes, state, identity, custody,
                inventoryState, createdAt, updatedAt);
    }

    /**
     * Returns a copy of this asset with different custody and stored inventory state — the
     * primitive behind every {@code AssetCustodyService} verb.
     *
     * @param custody        the replacement custody
     * @param inventoryState the replacement stored inventory state; must satisfy {@link
     *                       InventoryState#storable()}
     * @param updatedAt      when this change was made
     * @return a new {@code Asset} with {@code custody}, {@code inventoryState} and {@code
     *         updatedAt} replaced
     */
    public Asset withInventory(Custody custody, InventoryState inventoryState, Instant updatedAt) {
        return new Asset(id, displayName, category, ownership, devices, attributes, state, identity, custody,
                inventoryState, createdAt, updatedAt);
    }

    /**
     * Returns a copy of this asset with only {@code updatedAt} stamped — for a write path that
     * already has its own {@code with*} method for the field it changes but must still record when
     * the change happened.
     *
     * @param updatedAt when this change was made
     * @return a new {@code Asset} with {@code updatedAt} replaced
     */
    public Asset touch(Instant updatedAt) {
        return new Asset(id, displayName, category, ownership, devices, attributes, state, identity, custody,
                inventoryState, createdAt, updatedAt);
    }
}
