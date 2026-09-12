package com.drones.vision.perception.application.profile;

import com.drones.vision.perception.domain.model.Intent;
import com.drones.vision.perception.domain.model.ModelRef;

import java.util.List;
import java.util.Set;

/**
 * The platform tier of the profile fold (docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.7,
 * wave W2.6) — turns one operator-facing {@link Intent} pick into the {@link IntentPolicy} an
 * org/category/asset/session tier then patches over. Stateless and dependency-free: every
 * constant this resolver returns is a fixed platform choice, not read from any store, matching the
 * mermaid's own framing of {@code IntentPolicyResolver} as the "platform tier."
 *
 * <p>The model choices below are not invented for this class: {@link Intent#PEOPLE}/{@link
 * Intent#VEHICLES} reuse {@code yolo26n.pt} — the same closed-set model the {@code people-vehicles}
 * built-in profile ships (storage/persistence's {@code V29__cv_profiles.sql}) — while {@link
 * Intent#EVERYTHING}/{@link Intent#CUSTOM} reuse {@code yoloe-26s-seg-pf.pt}, the open-vocabulary
 * model {@code wide-search} ships, because only an open-vocabulary model can detect a class set
 * this resolver does not fix in advance (an arbitrary "everything" or a caller-named custom list).
 * Confidence/rate numbers below mirror those same two built-ins' own values for the same reason:
 * this resolver is choosing among facts the codebase already trusted enough to seed the platform
 * with, not asserting new ones.
 */
public final class IntentPolicyResolver {

    private static final ModelRef CLOSED_SET_MODEL = new ModelRef("yolo26n.pt", "latest");
    private static final ModelRef OPEN_VOCAB_MODEL = new ModelRef("yoloe-26s-seg-pf.pt", "latest");

    /** Matches {@code EventRuleConfig#defaults()}'s own confidence threshold for every intent -- this wave introduces no new report-threshold policy per intent, only the wiring for a future one to plug into (see {@link IntentPolicy}'s own javadoc). */
    private static final double DEFAULT_REPORT_THRESHOLD = 0.5;

    private IntentPolicyResolver() {
    }

    /**
     * Resolves {@code intent} to its platform-tier {@link IntentPolicy}.
     *
     * @param intent        the operator's pick; must not be {@code null}
     * @param customClasses the caller's own class list, consulted only when {@code intent} is
     *                       {@link Intent#CUSTOM}; must not be {@code null} or empty in that case
     *                       (a custom intent with no classes has nothing to resolve to), ignored
     *                       otherwise
     * @return the resolved policy
     * @throws IllegalArgumentException if {@code intent} is {@code null}, or if {@code intent} is
     *                                   {@link Intent#CUSTOM} and {@code customClasses} is
     *                                   {@code null} or empty
     */
    public static IntentPolicy resolve(Intent intent, List<String> customClasses) {
        if (intent == null) {
            throw new IllegalArgumentException("IntentPolicyResolver intent must not be null");
        }
        return switch (intent) {
            case PEOPLE -> new IntentPolicy(CLOSED_SET_MODEL, Set.of("person"), 0.40, DEFAULT_REPORT_THRESHOLD, 10);
            case VEHICLES -> new IntentPolicy(CLOSED_SET_MODEL, Set.of("car", "truck", "bus", "motorcycle"), 0.40,
                    DEFAULT_REPORT_THRESHOLD, 10);
            // Empty classSet is this codebase's established "every label" convention (CvProfile#labelFilter's
            // own javadoc), matching wide-search's own '[]'::jsonb label_filter -- EVERYTHING really does mean
            // everything, not a fixed long list this resolver would have to keep in sync with the model.
            case EVERYTHING -> new IntentPolicy(OPEN_VOCAB_MODEL, Set.of(), 0.30, DEFAULT_REPORT_THRESHOLD, 4);
            case CUSTOM -> {
                if (customClasses == null || customClasses.isEmpty()) {
                    throw new IllegalArgumentException(
                            "IntentPolicyResolver customClasses must not be empty for Intent.CUSTOM");
                }
                yield new IntentPolicy(OPEN_VOCAB_MODEL, Set.copyOf(customClasses), 0.40, DEFAULT_REPORT_THRESHOLD,
                        10);
            }
        };
    }
}
