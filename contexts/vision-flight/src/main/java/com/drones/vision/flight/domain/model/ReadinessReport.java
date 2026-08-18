package com.drones.vision.flight.domain.model;

import com.drones.vision.kernel.AssetId;

import java.time.Instant;
import java.util.List;

/**
 * The NEGOTIATE-stage output (docs/plans/active/DRONE-ONBOARDING-PLAN.md §3.1) — one
 * {@link VehicleProfile} evaluated against every frozen feature key, produced by {@link
 * com.drones.vision.flight.application.ReadinessService}. Placed in {@code domain.model} rather than
 * {@code application} per the plan's own module-placement table (§7) — it is this context's
 * vocabulary for "what this vehicle is ready to do", not a wire shape.
 *
 * <p>{@code profileObservedAt} is {@code null} when the asset has never been probed at all — the
 * report can still exist (verdict {@link ReadinessVerdict#UNKNOWN}, every feature {@code UNKNOWN}),
 * because "never probed" is itself an honest, renderable answer (C7), not a 404. A scoped, 404-hiding
 * read is a different failure mode, handled by {@link
 * com.drones.vision.flight.application.ReadinessService} itself, not by this type.
 *
 * @param assetId           the asset this report describes
 * @param verdict           the overall verdict; {@link ReadinessVerdict#UNKNOWN} whenever the
 *                          profile is missing or incomplete — see {@code ReadinessService} for the
 *                          exact rule ("absence of evidence is not evidence of readiness")
 * @param evaluatedAt       when this report was computed
 * @param profileObservedAt when the underlying profile was observed, or {@code null} if never probed
 * @param features          one row per frozen feature key; defensively copied
 * @param blockers          feature keys whose status forced {@link ReadinessVerdict#NO_GO};
 *                          defensively copied, empty unless {@code verdict == NO_GO}
 */
public record ReadinessReport(
        AssetId assetId,
        ReadinessVerdict verdict,
        Instant evaluatedAt,
        Instant profileObservedAt,
        List<FeatureReadiness> features,
        List<String> blockers) {

    public ReadinessReport {
        if (assetId == null) {
            throw new IllegalArgumentException("ReadinessReport assetId must not be null");
        }
        if (verdict == null) {
            throw new IllegalArgumentException("ReadinessReport verdict must not be null");
        }
        if (evaluatedAt == null) {
            throw new IllegalArgumentException("ReadinessReport evaluatedAt must not be null");
        }
        if (features == null) {
            throw new IllegalArgumentException("ReadinessReport features must not be null");
        }
        features = List.copyOf(features);
        blockers = List.copyOf(blockers == null ? List.of() : blockers);
    }
}
