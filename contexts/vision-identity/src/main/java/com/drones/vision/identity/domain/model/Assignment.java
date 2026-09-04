package com.drones.vision.identity.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;

/**
 * One pilot&rarr;asset link, with the seat it grants (docs/plans/active/AUTH-ROLES-PLAN.md §3.4, wave
 * B1) — the roster row {@code AssignmentRepositoryPort#assignmentsForAsset(AssetId)} returns. Not a
 * separate aggregate with its own identity; a plain read shape over the link
 * {@code AssignmentRepositoryPort#assign(UserId, AssetId, AssignmentRole)} creates.
 *
 * @param pilot the assigned user
 * @param asset the asset they are assigned to
 * @param role  the seat this link grants on that asset
 */
public record Assignment(UserId pilot, AssetId asset, AssignmentRole role) {

    public Assignment {
        if (pilot == null) {
            throw new IllegalArgumentException("Assignment pilot must not be null");
        }
        if (asset == null) {
            throw new IllegalArgumentException("Assignment asset must not be null");
        }
        if (role == null) {
            throw new IllegalArgumentException("Assignment role must not be null");
        }
    }
}
