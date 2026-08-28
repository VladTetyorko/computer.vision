package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.FeatureRequirementEntity;
import com.drones.vision.flight.domain.model.FeatureRequirement;

/**
 * {@link FeatureRequirement} &harr; {@link FeatureRequirementEntity} mapping (docs/plans/active/DRONE-ONBOARDING-PLAN.md
 * O5), extracted the same way every other mapper in this package is (docs/plans/active/LAYERING-REFACTOR-PLAN.md
 * SS3/SS7 row C).
 *
 * <p>{@link #toEntity} derives the entity's synthetic {@code "<firmware>:<featureKey>"} id — see
 * {@code FeatureRequirementEntity}'s javadoc for why a stable natural key was chosen over a random
 * one. Not called by any production code path today ({@code FeatureRequirementRepositoryPort} has
 * no {@code save}), kept only so the round-trip is testable the same way every other entity's is.
 */
public final class FeatureRequirementMapper {

    private FeatureRequirementMapper() {
    }

    public static FeatureRequirementEntity toEntity(FeatureRequirement requirement) {
        String id = requirement.firmware() + ":" + requirement.featureKey();
        return new FeatureRequirementEntity(id, requirement.featureKey(), requirement.label(),
                requirement.firmware(), requirement.requiredMessageId(), requirement.requiredMessageName(),
                requirement.minimumHz(), requirement.requiredParameterName(), requirement.requiredParameterValue(),
                requirement.forbiddenParameterBits());
    }

    public static FeatureRequirement toDomain(FeatureRequirementEntity entity) {
        return new FeatureRequirement(entity.featureKey(), entity.label(), entity.firmware(),
                entity.requiredMessageId(), entity.requiredMessageName(), entity.minimumHz(),
                entity.requiredParameterName(), entity.requiredParameterValue(), entity.forbiddenParameterBits());
    }
}
