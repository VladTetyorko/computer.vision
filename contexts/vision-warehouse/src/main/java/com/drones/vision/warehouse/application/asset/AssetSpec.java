package com.drones.vision.warehouse.application.asset;

import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Identity;

import java.util.List;
import java.util.Map;
import com.drones.vision.warehouse.application.device.DeviceRegistration;

/**
 * Everything needed to create an asset together with its device(s) in one act.
 *
 * <p>{@code devices} registers brand-new low-level devices for the asset (the original,
 * asset-first flow); {@code existingDeviceIds} instead assigns already-registered, currently
 * unowned devices to the new asset in the same act — the "promote to asset" flow, letting a caller
 * combine both in a single {@code create} rather than creating with a placeholder device and
 * assigning the real one(s) as a second call.
 *
 * <p><b>Zero devices is legal here</b> (docs/plans/active/WAREHOUSE-UX-PLAN.md D4): a battery,
 * spare part, or radio has no device to register at all. The "at least one device" rule this
 * record used to enforce unconditionally now depends on whether the target category is {@link
 * com.drones.vision.warehouse.domain.model.DeviceCategory#connected()}, a fact this record cannot
 * see (it only holds a {@link CategoryId}) — so {@code DefaultAssetService#create} enforces it,
 * via {@code CategoryRepositoryPort}.
 *
 * @param displayName       human-readable name (e.g. "my drone"); must not be blank
 * @param category          the asset's category
 * @param attributes        free-form key/value attributes; defensively copied
 * @param devices           new sources to register for it; may be empty
 * @param existingDeviceIds already-registered, unowned devices to assign to the new asset
 *                          (validated exactly like {@code AssetService#assignDevice} — must exist,
 *                          must not be soft-deleted, must not already belong to another asset);
 *                          defensively copied
 * @param identity          initial identity facts, or {@link Identity#NONE}
 * @param custody           initial custody, or {@link Custody#NONE} to receive it into stock
 */
public record AssetSpec(String displayName, CategoryId category, Map<String, String> attributes,
                         List<DeviceRegistration> devices, List<DeviceId> existingDeviceIds, Identity identity,
                         Custody custody) {

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
        if (identity == null) {
            throw new IllegalArgumentException("AssetSpec identity must not be null");
        }
        if (custody == null) {
            throw new IllegalArgumentException("AssetSpec custody must not be null");
        }
        attributes = Map.copyOf(attributes);
        devices = List.copyOf(devices);
        existingDeviceIds = List.copyOf(existingDeviceIds);
    }

    /**
     * Convenience constructor for the original, {@code existingDeviceIds}/{@code identity}/{@code
     * custody}-less shape — defaults them to empty/{@link Identity#NONE}/{@link Custody#NONE},
     * keeping every pre-existing call site (new devices only, no identity or custody known yet)
     * compiling unchanged. Not the start of a chain: a new field earns at most this one overload
     * (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R1/R4), never another one on top of it.
     *
     * @param displayName human-readable name (e.g. "my drone"); must not be blank
     * @param category    the asset's category
     * @param attributes  free-form key/value attributes; defensively copied
     * @param devices     the sources to register for it; may be empty
     */
    public AssetSpec(String displayName, CategoryId category, Map<String, String> attributes,
                      List<DeviceRegistration> devices) {
        this(displayName, category, attributes, devices, List.of(), Identity.NONE, Custody.NONE);
    }
}
