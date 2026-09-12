package com.drones.vision.perception.domain.model;

/**
 * The one operator-facing pick "what am I looking for" folds down to (docs/plans/active/
 * CV-ORCHESTRATION-PLAN.md &sect;4.7, wave W2.6) — replaces the pre-wave surface of a bare model
 * card plus a manually-typed label filter with a single choice an {@link
 * com.drones.vision.perception.application.profile.IntentPolicyResolver} turns into a starting
 * policy (model, class set, detect floor, report threshold, rate ceiling).
 *
 * <p>{@link #CUSTOM} is the escape hatch: it carries no fixed class set of its own — {@link
 * com.drones.vision.perception.application.profile.IntentPolicyResolver#resolve} takes the
 * caller's own class list for it instead, exactly the mermaid's {@code Custom(classes)} node.
 * Every other constant resolves to a fixed, platform-tier class set the resolver owns; an expert
 * can still override any individual resolved field afterward (docs/plans/active/
 * CV-ORCHESTRATION-PLAN.md &sect;4.7's "expert tier can still pin a model").
 */
public enum Intent {
    PEOPLE,
    VEHICLES,
    EVERYTHING,
    CUSTOM
}
