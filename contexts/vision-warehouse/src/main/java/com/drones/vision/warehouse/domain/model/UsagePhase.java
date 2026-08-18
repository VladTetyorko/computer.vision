package com.drones.vision.warehouse.domain.model;

/**
 * The phase of an {@link AssetUsage} session's aircraft-state lifecycle
 * (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.3) — a warehouse-owned fact, not a
 * warehouse-owned rule.
 *
 * <p><b>Why this is a separate type from flight's {@code FlightPhase}, not a reuse of it:</b>
 * warehouse is the pure leaf of the context dependency DAG (docs/plans/active/DOMAIN-SEPARATION-W1.md
 * §16) and may never depend on {@code vision-flight} or any other context — but
 * docs/plans/active/DRONE-ONBOARDING-PLAN.md §7/§8 (Wave O7) asks for a {@code phase} field on
 * {@link AssetUsage}, which lives here. Those two facts only both hold if warehouse owns its own
 * value type rather than importing flight's. {@code UsagePhase} therefore mirrors {@code
 * FlightPhase}'s six values by name only, as an independent enum with zero dependency on flight;
 * the actual state machine ({@code FlightPhaseRule}, pure and stateless) still lives in {@code
 * vision-flight} exactly as the plan says. {@code vision-perception} — which legally depends on
 * both warehouse and flight — is the one place that runs the rule and translates its verdict
 * ({@code FlightPhase}) into this enum before persisting an {@link AssetUsage}; see {@code
 * com.drones.vision.perception.application.pipeline.UsageTracker}. This resolves a gap the plan
 * itself left open (it names {@code FlightPhase} as flight's and {@code AssetUsage.phase} as
 * warehouse's without saying how a leaf module holds a field typed by a module it cannot depend
 * on) without adding any new edge to the dependency DAG.
 *
 * @see AssetUsage#phase()
 */
public enum UsagePhase {
    PREFLIGHT, IN_FLIGHT, LINK_LOST, POSTFLIGHT, ABANDONED, CLOSED
}
