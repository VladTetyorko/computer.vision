package com.drones.vision.flight.application;

import com.drones.vision.flight.domain.model.MessageIntervalOutcome;
import com.drones.vision.flight.domain.model.ParameterWriteOutcome;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.Authority;

import java.time.Duration;

/**
 * The CONFIGURE stage's vehicle-side half (docs/plans/active/DRONE-ONBOARDING-PLAN.md section 3.1)
 * -- Mechanism A message-interval requests and Tier-A/B {@code PARAM_SET}s, both structurally
 * refused while the aircraft is armed or its arming is unknown (section 6.2, D10). See {@link
 * DefaultRemediationService} for the full gate ordering.
 */
public interface RemediationService {

    /**
     * Mechanism A (docs/plans/active/DRONE-ONBOARDING-PLAN.md section 4a) -- not a write, but still
     * an authority action: it puts traffic on someone else's aircraft link.
     *
     * @throws java.util.NoSuchElementException                if {@code assetId} is unknown (404)
     * @throws com.drones.vision.platform.AccessDeniedException if the actor may not manage this
     *                                                            asset (403, audited)
     * @throws IllegalStateException                             if the aircraft is armed or its
     *                                                            arming is unknown (409, audited as
     *                                                            a refusal), or the asset has no
     *                                                            configurable device (409, not
     *                                                            audited)
     */
    MessageIntervalOutcome requestMessageInterval(AssetId assetId, int messageId, Duration interval,
                                                   UserId actor, Authority scope);

    /**
     * A single Tier-A/B {@code PARAM_SET}. Tier C, and any name matching no known tier, is refused
     * before anything else is even resolved (section 4a, D9) -- "not exposed at any authority
     * level".
     *
     * @param explicitConsent required {@code true} for a Tier-B parameter (section 6.1's "per-item
     *                        explicit consent"); ignored for Tier A
     * @throws IllegalArgumentException                         if {@code parameterName} is Tier C or
     *                                                            unclassified (never audited, never
     *                                                            resolves the asset), or is Tier B
     *                                                            without {@code explicitConsent}
     * @throws java.util.NoSuchElementException                if {@code assetId} is unknown (404)
     * @throws com.drones.vision.platform.AccessDeniedException if the actor lacks the tier's
     *                                                            required authority (403, audited)
     * @throws IllegalStateException                             if the aircraft is armed or its
     *                                                            arming is unknown (409, audited as
     *                                                            a refusal), or the asset has no
     *                                                            configurable device (409, not
     *                                                            audited)
     */
    ParameterWriteOutcome writeParameter(AssetId assetId, String parameterName, double value,
                                          boolean explicitConsent, UserId actor, Authority scope);
}
