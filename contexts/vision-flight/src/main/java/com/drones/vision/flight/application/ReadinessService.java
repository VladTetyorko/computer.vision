package com.drones.vision.flight.application;

import com.drones.vision.flight.domain.model.ReadinessReport;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.platform.VisibilityScope;

/**
 * The NEGOTIATE stage (docs/plans/active/DRONE-ONBOARDING-PLAN.md section 3.1) -- evaluate an
 * asset's most recent {@link com.drones.vision.flight.domain.model.VehicleProfile} against the
 * seeded feature-requirement table. See {@link DefaultReadinessService} for the exact verdict rule.
 *
 * <p>This service currently evaluates only the <b>configuration-derived</b> half of readiness
 * (section 2.5) -- the profile-vs-requirement-table comparison. The <b>telemetry-derived</b> half
 * (today's five {@code derivePreflight} rows: video/telemetry/GPS/battery/armable) is a real,
 * intentional gap in this wave -- see this module's own MODULE.md Status entry for why, and the O3
 * report for the fuller reasoning.
 */
public interface ReadinessService {

    /**
     * @throws java.util.NoSuchElementException if {@code assetId} is unknown or out of scope (404 --
     *                                          this is a read, not a command)
     */
    ReadinessReport evaluate(AssetId assetId, VisibilityScope scope);
}
