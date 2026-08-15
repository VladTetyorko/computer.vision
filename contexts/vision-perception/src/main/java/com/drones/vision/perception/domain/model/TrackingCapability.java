package com.drones.vision.perception.domain.model;

/**
 * What cv-service's V3 capability ladder actually served on a frame (docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md
 * §2), as opposed to {@link TrackingConfig#capabilityLevel()}, which is only the request's ceiling.
 *
 * <p>{@code levelServed} is always {@code &lt;= requested} — nothing on the Java side may raise it,
 * and a reader must render this field as what happened, never echo the request back as if it were
 * the outcome (invariant B5). This record exists at all only so a downgrade is a <em>thing</em> the
 * UI can render, not two loose scalars a caller has to remember to read together.
 *
 * <p>Bundled onto {@link TrackingTelemetry#capability()}, which is {@code null} whenever cv-service
 * reported no level at all (a pre-V3 server) — a {@code TrackingCapability} instance therefore only
 * ever exists once a level was genuinely reported, which is why {@code levelServed} is validated as
 * at least {@code 1} rather than allowing the ladder's own {@code 0} ("auto-probe") sentinel here.
 *
 * @param levelServed the capability level that actually ran on this frame; must be within [1,5]
 * @param reason      why the served level was capped below what was requested; never {@code null},
 *                    {@code ""} means "served exactly as requested"
 */
public record TrackingCapability(int levelServed, String reason) {

    public TrackingCapability {
        if (levelServed < 1 || levelServed > 5) {
            throw new IllegalArgumentException("TrackingCapability levelServed must be within [1,5]: " + levelServed);
        }
        if (reason == null) {
            throw new IllegalArgumentException("TrackingCapability reason must not be null");
        }
    }
}
