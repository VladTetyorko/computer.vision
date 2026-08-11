package com.drones.vision.application.replay;

/**
 * Tunables for {@link DefaultReplayService}'s telemetry/detection downsampling and fetch bounds —
 * extracted per docs/plans/active/LAYERING-REFACTOR-PLAN.md &sect;1.3's config-extraction rule. Framework-free;
 * {@code vision-app} binds a {@code VisionApplicationProperties} record and maps it onto this
 * record's constructor. Every {@link #defaults()} value is byte-identical to the literal it
 * replaces.
 *
 * @param defaultMaxPoints default cap the API layer falls back to when the caller omits
 *                          {@code maxPoints}; must be positive
 * @param maxPointsCeiling  hard ceiling {@link DefaultReplayService#timeline} clamps
 *                          {@code maxPoints} to, regardless of what's asked; must be &ge;
 *                          {@code defaultMaxPoints}
 * @param fetchLimit        best-effort fetch bound passed to both {@code
 *                          TelemetryRepositoryPort#findByUsage} and {@code
 *                          DetectionRepositoryPort#query} — see {@link DefaultReplayService}'s own
 *                          javadoc for why a genuinely time-bounded fetch isn't possible against
 *                          either port as it exists today; must be positive
 */
public record ReplayServiceSettings(int defaultMaxPoints, int maxPointsCeiling, int fetchLimit) {

    public ReplayServiceSettings {
        if (defaultMaxPoints <= 0) {
            throw new IllegalArgumentException("defaultMaxPoints must be positive, was " + defaultMaxPoints);
        }
        if (maxPointsCeiling < defaultMaxPoints) {
            throw new IllegalArgumentException("maxPointsCeiling must be >= defaultMaxPoints");
        }
        if (fetchLimit <= 0) {
            throw new IllegalArgumentException("fetchLimit must be positive, was " + fetchLimit);
        }
    }

    /** Every value byte-identical to the literal it replaces (docs/plans/active/LAYERING-REFACTOR-PLAN.md &sect;1.3). */
    public static ReplayServiceSettings defaults() {
        return new ReplayServiceSettings(500, 2_000, 20_000);
    }
}
