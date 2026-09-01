package com.drones.vision.warehouse.application.asset;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.DeviceId;

/**
 * Whether a candidate stream identity ({@code protocol, uri, sysid}) already matches an active,
 * registered device — the non-throwing query form of the check {@link
 * AssetService#createFromCandidate} enforces by throwing {@link IllegalStateException}
 * (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md &sect;11 Z2a). {@link
 * AssetService#findDuplicateDevice} returns this wrapped in an {@code Optional} — empty means no
 * active device carries the identity at all.
 *
 * @param deviceId    the matching device's id
 * @param owningAsset the asset currently owning that device, or {@code null} when the matching
 *                    device exists but has not yet been assigned to any asset — the same case
 *                    {@link AssetService#createFromCandidate}'s error message names as "already
 *                    registered to device ..." rather than "... to asset ..."
 */
public record DuplicateDeviceMatch(DeviceId deviceId, AssetId owningAsset) {

    public DuplicateDeviceMatch {
        if (deviceId == null) {
            throw new IllegalArgumentException("DuplicateDeviceMatch deviceId must not be null");
        }
    }
}
