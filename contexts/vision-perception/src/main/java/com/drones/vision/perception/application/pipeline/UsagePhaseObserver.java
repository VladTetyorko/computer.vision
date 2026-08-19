package com.drones.vision.perception.application.pipeline;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.warehouse.domain.model.UsagePhase;

import java.time.Instant;

/**
 * Notified by {@link UsageTracker} whenever an {@link AssetUsage}'s {@link UsagePhase} is set for
 * the first time (the usage opening) or changes (docs/plans/active/DRONE-ONBOARDING-PLAN.md
 * §2.4/O11 -- "the flight passport") -- the seam that lets {@code vision-app} compose a phase
 * transition with another context's application layer ({@code
 * com.drones.vision.flight.application.VehicleProfileService#captureSnapshot}) without this module
 * ever depending on it. Same functional-seam convention {@link UsageTracker}'s own {@code
 * telemetryObserver} already follows for exactly the same reason
 * (docs/plans/active/DOMAIN-SEPARATION-W1.md §5, C2: a direct call from here would make perception
 * depend on flight's application layer, closing a context cycle).
 *
 * <h2>Contract</h2>
 * {@code previous} is {@code null} <b>exactly once per usage</b>: the call that announces the
 * usage's very first phase, fired when the usage opens. Nothing ever "transitions into" that
 * initial {@link UsagePhase#PREFLIGHT} -- it is simply what the usage starts as -- so an
 * implementation that needs a PREFLIGHT snapshot (like O11's passport) must treat a {@code null
 * previous} as the open event, not skip it as though nothing happened.
 *
 * <p><b>Called on the telemetry / stream-lifecycle path -- implementations must return promptly.</b>
 * No blocking I/O, no synchronous network call: a slow implementation stalls the very sample or
 * stream event that triggered it. An implementation that needs to do real work (e.g. probe an
 * aircraft over its link) must hand off to its own bounded executor and return immediately -- see
 * {@code com.drones.vision.app.onboarding}'s implementation for the pattern this module expects.
 *
 * <p>An implementation that throws must never break telemetry ingest or usage tracking: {@link
 * UsageTracker} catches every {@link RuntimeException} an invocation raises and logs it rather than
 * letting it propagate -- the same reasoning {@code AuditTrailPort}'s own javadoc gives for why
 * recording must never break the operation being audited.
 */
@FunctionalInterface
public interface UsagePhaseObserver {

    /**
     * @param assetId  the usage's owning asset
     * @param usageId  the usage whose phase changed
     * @param previous the phase before this change, or {@code null} exactly when this call
     *                 announces the usage's opening (its first-ever phase)
     * @param next     the phase now in effect
     * @param at       when {@link UsageTracker} observed the change
     */
    void onPhaseChanged(AssetId assetId, UsageId usageId, UsagePhase previous, UsagePhase next, Instant at);

    /** Observes nothing -- the default every {@link UsageTracker} constructor but the production-wiring one uses. */
    UsagePhaseObserver NOOP = (assetId, usageId, previous, next, at) -> { };
}
