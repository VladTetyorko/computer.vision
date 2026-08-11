package com.drones.vision.application.training;

import com.drones.vision.domain.model.ModelRef;
import com.drones.vision.domain.port.out.ModelRegistryPort;

import java.util.Objects;

/**
 * One row of {@link ModelRegistryService#models()}: a known {@link ModelRef} plus whether it is
 * the one currently promoted/live (docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2).
 *
 * <p>Computed by {@link DefaultModelRegistryService} from {@link ModelRegistryPort#models()} and
 * {@link ModelRegistryPort#active()} — never carried by {@link ModelRef} itself, since being "the
 * active default" is a registry fact, not a property of a reference used throughout
 * detection/pipeline config (see that port's own javadoc).
 *
 * @param ref    the model reference
 * @param active whether this is the registry's current active/production model
 */
public record RegisteredModel(ModelRef ref, boolean active) {

    public RegisteredModel {
        Objects.requireNonNull(ref, "ref must not be null");
    }
}
