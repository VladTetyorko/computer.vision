package com.drones.vision.warehouse.application.discovery;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.warehouse.domain.model.DiscoveryCandidateId;

/**
 * Thrown by {@link DiscoveryInboxService#attach} when the target candidate is already {@link
 * com.drones.vision.warehouse.domain.model.CandidateStatus#REGISTERED} to an asset other than the
 * one requested (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md &sect;3.2 C1) — distinct from the
 * {@code 409} a duplicate-stream or missing-stream {@link IllegalStateException} gives: this is not
 * a conflict to resolve by picking a different asset, it is a stale request against a candidate the
 * caller (or another operator) already resolved. Mapped to {@code 422} by {@code vision-api}'s
 * exception handler in a later wave; declared here rather than reusing {@code
 * com.drones.vision.perception.application.device.ProbeFailedException} because {@code
 * vision-warehouse} is the pure leaf and may not depend on {@code vision-perception}.
 */
public final class DiscoveryCandidateAlreadyRegisteredException extends RuntimeException {

    public DiscoveryCandidateAlreadyRegisteredException(DiscoveryCandidateId id, AssetId registeredTo) {
        super("Discovery candidate " + id.value() + " is already registered to asset " + registeredTo.value());
    }
}
