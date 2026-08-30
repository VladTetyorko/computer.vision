package com.drones.vision.warehouse.domain.model;

import com.drones.vision.kernel.UserId;
import java.time.Instant;

/**
 * Who currently holds an {@link Asset}, and where — per-person custody
 * (docs/plans/active/WAREHOUSE-UX-CONTEXT.md OQ2), not just a group.
 *
 * <p>{@code custodianId == null} means the asset is in stock and held by nobody in particular;
 * {@link #NONE} is that value. Once a custodian is named, {@code since} records when they took it
 * and is required — an issued asset with no issue time would be a fact nobody could ever audit.
 * {@code location} is a free-form note ("Hangar 2", "with pilot in the field") since a rigid
 * location model is not this wave's job.
 *
 * @param custodianId the user currently holding this asset, or {@code null} if it is in stock
 * @param location    a free-form note of where it is; blank normalizes to {@code null}
 * @param since       when {@code custodianId} took custody; required iff {@code custodianId} is set
 */
public record Custody(UserId custodianId, String location, Instant since) {

    /** In stock, held by nobody — every asset starts here. */
    public static final Custody NONE = new Custody(null, null, null);

    public Custody {
        location = location == null || location.isBlank() ? null : location;
        if (custodianId != null && since == null) {
            throw new IllegalArgumentException("Custody since must not be null when custodianId is set");
        }
        if (custodianId == null) {
            since = null;
        }
    }
}
