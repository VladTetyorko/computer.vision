package com.drones.vision.perception.application.pipeline;

import com.drones.vision.flight.domain.model.FlightPhaseRule;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Bundles the two collaborators {@link UsageTracker} needs to run {@link FlightPhaseRule} against
 * tracked telemetry (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.3, Wave O7) into one
 * constructor parameter instead of two, per {@code java-clean-code} §3's "bundle collaborators
 * rather than sprawl" — {@link UsageTracker}'s constructor chain was already long before this wave.
 *
 * <p>This is also where the plan's own gap gets resolved in code: {@code FlightPhase} lives in
 * {@code vision-flight}, {@code AssetUsage.phase} (typed {@code
 * com.drones.vision.warehouse.domain.model.UsagePhase}) lives in {@code vision-warehouse} — a pure
 * leaf that may not depend on flight — so something has to translate between the two. {@link
 * UsageTracker} is that something (it already legally depends on both), and this record is what it
 * hands the rule.
 *
 * @param clock deterministic time source for the rule's {@code linkAge} math; production uses
 *              {@code Instant::now}, tests inject a fixed/steppable supplier so silence/abandon
 *              assertions never depend on wall-clock timing
 * @param rule  the pure phase state machine (vision-flight); see {@link #defaults()} for the
 *              placeholder used until {@code vision-app} binds {@code vision.flight.phase.*}
 */
public record UsagePhaseSettings(Supplier<Instant> clock, FlightPhaseRule rule) {

    public UsagePhaseSettings {
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(rule, "rule must not be null");
    }

    /**
     * The pre-O7 compatibility default: {@code Instant::now} plus a rule built from
     * docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1's frozen defaults (silence-window 10s,
     * abandon-window 120s) — used by every {@link UsageTracker} constructor overload that predates
     * phase tracking, so their observable behavior (wall-clock timestamps, no caller ever asserting
     * on {@code AssetUsage#phase()}) is unchanged. Production wiring is meant to move to an
     * explicit instance built from {@code vision.flight.phase.silence-window}/{@code
     * abandon-window} once {@code vision-app} binds them — the same "record is config-free, {@code
     * vision-app} binds the real property and passes an instance in" pattern {@link
     * UsageSummaryBatchSettings}'s class javadoc documents for its own settings.
     */
    public static UsagePhaseSettings defaults() {
        return new UsagePhaseSettings(Instant::now,
                new FlightPhaseRule(Duration.ofSeconds(10), Duration.ofSeconds(120)));
    }
}
