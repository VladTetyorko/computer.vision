package com.drones.vision.adapter.persistence.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite-key class for {@link AssignmentEntity} — the (pilot, asset) pair that uniquely
 * identifies one pilot&rarr;asset assignment (docs/U-SCOPE-PLAN.md, U-e slice 2, feature 2).
 *
 * <p>A plain mutable class with a public no-arg constructor and matching field names, as JPA's
 * {@code @IdClass} contract requires (a record cannot satisfy it — no no-arg constructor). Field
 * names {@code pilotUserId}/{@code assetId} match {@link AssignmentEntity}'s {@code @Id} fields.
 */
public class AssignmentId implements Serializable {

    private UUID pilotUserId;
    private UUID assetId;

    /** JPA only. */
    public AssignmentId() {
    }

    public AssignmentId(UUID pilotUserId, UUID assetId) {
        this.pilotUserId = pilotUserId;
        this.assetId = assetId;
    }

    public UUID getPilotUserId() {
        return pilotUserId;
    }

    public void setPilotUserId(UUID pilotUserId) {
        this.pilotUserId = pilotUserId;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public void setAssetId(UUID assetId) {
        this.assetId = assetId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AssignmentId other)) {
            return false;
        }
        return Objects.equals(pilotUserId, other.pilotUserId) && Objects.equals(assetId, other.assetId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pilotUserId, assetId);
    }
}
