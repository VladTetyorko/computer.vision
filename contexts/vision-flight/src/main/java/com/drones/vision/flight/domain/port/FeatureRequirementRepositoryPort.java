package com.drones.vision.flight.domain.port;

import com.drones.vision.flight.domain.model.FeatureRequirement;

import java.util.List;

/**
 * Driven port: the seeded feature-requirement table (docs/plans/active/DRONE-ONBOARDING-PLAN.md
 * section 5.1, D6). Reference data -- adding a feature requirement is a migration against whatever
 * implements this port, never a code change here.
 */
public interface FeatureRequirementRepositoryPort {

    /**
     * @return every requirement row seeded for {@code firmware}; empty for a firmware this platform
     *         has never seen -- {@code ReadinessService} reads that as "every feature UNKNOWN", not
     *         an error
     */
    List<FeatureRequirement> findByFirmware(String firmware);

    /**
     * @return every requirement row, across every firmware
     */
    List<FeatureRequirement> findAll();
}
