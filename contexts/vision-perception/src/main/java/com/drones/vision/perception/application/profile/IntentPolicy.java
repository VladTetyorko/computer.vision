package com.drones.vision.perception.application.profile;

import com.drones.vision.perception.domain.model.Intent;
import com.drones.vision.perception.domain.model.ModelRef;

import java.util.Objects;
import java.util.Set;

/**
 * What {@link IntentPolicyResolver#resolve} produces for one {@link Intent} (docs/plans/active/
 * CV-ORCHESTRATION-PLAN.md &sect;4.7, wave W2.6) — the platform-tier starting point the profile
 * fold patches over, before any org/category/asset/session tier gets a say.
 *
 * <p><b>Known gap, disclosed rather than papered over:</b> only {@link #model()} and {@link
 * #classSet()} currently reach a caller ({@code CvProfileRequest#toSpec()} seeds {@code
 * model}/{@code labelFilter} from them when the request leaves those fields blank/empty) and
 * {@link #reportThreshold()} (folded into the synthesized {@code EventRuleConfig}). {@link
 * #detectFloor()} and {@link #rateCeiling()} are computed here and available to any future caller,
 * but nothing wires them into {@code CvProfileRequest} yet: unlike {@code model}/{@code
 * labelFilter} (blank string / empty list are unambiguous "caller left this to the platform"
 * sentinels), {@code CvProfileRequest}'s {@code confidenceThreshold}/{@code inferenceFps} are bare
 * primitives with no such sentinel under this wave's frozen wire contract (docs/plans/active/
 * CV-ORCHESTRATION-PLAN.md &sect;5) — {@code 0.0}/{@code 0} both collide with real values a caller
 * might deliberately send, so guessing "unset" from either would be dishonest. Widening the wire
 * shape to nullable wrappers to fix this properly is left to whichever wave actually builds the
 * web surface these two fields are for (&sect;4.8's Tuning modal, not this Java-only wave).
 *
 * @param model           the CV model this intent resolves to
 * @param classSet        labels this intent watches for; empty means every label (matches {@link
 *                        com.drones.vision.perception.domain.model.CvProfile#labelFilter()}'s own
 *                        "empty means all" convention); defensively copied
 * @param detectFloor     minimum confidence to keep a detection at all, range [0,1] — the same
 *                        knob as {@link com.drones.vision.perception.domain.model.CvProfile#confidenceThreshold()}
 * @param reportThreshold minimum confidence to count toward opening/keeping a {@code
 *                        DetectionEvent} open, range [0,1] — the same knob as {@link
 *                        com.drones.vision.perception.domain.model.EventRuleConfig#confidenceThreshold()};
 *                        deliberately a distinct, usually higher, number from {@code detectFloor}:
 *                        an intent can keep a low-confidence box on screen without also opening an
 *                        event for it
 * @param rateCeiling     maximum inference sample rate this intent should ever run at; must be
 *                        positive — a wide, expensive search intent needs a lower ceiling than a
 *                        narrow one, matching &sect;4.9's admission concerns
 */
public record IntentPolicy(ModelRef model, Set<String> classSet, double detectFloor, double reportThreshold,
                            int rateCeiling) {

    public IntentPolicy {
        Objects.requireNonNull(model, "IntentPolicy model must not be null");
        Objects.requireNonNull(classSet, "IntentPolicy classSet must not be null");
        if (Double.isNaN(detectFloor) || detectFloor < 0.0 || detectFloor > 1.0) {
            throw new IllegalArgumentException("IntentPolicy detectFloor must be within [0,1]: " + detectFloor);
        }
        if (Double.isNaN(reportThreshold) || reportThreshold < 0.0 || reportThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "IntentPolicy reportThreshold must be within [0,1]: " + reportThreshold);
        }
        if (rateCeiling <= 0) {
            throw new IllegalArgumentException("IntentPolicy rateCeiling must be positive: " + rateCeiling);
        }
        classSet = Set.copyOf(classSet);
    }
}
