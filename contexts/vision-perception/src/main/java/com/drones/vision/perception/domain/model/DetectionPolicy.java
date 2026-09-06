package com.drones.vision.perception.domain.model;

import java.util.Locale;

/**
 * A per-asset choice of when this asset's streams should run CV inference at all
 * (docs/plans/active/ALWAYS-ON-FLOW-PLAN.md wave D1, vocabulary frozen by docs/plans/active/CV-SCALE-PLAN.md
 * &sect;S2): {@link #ON_VIEW} (the default, today's only behavior) means inference runs only while
 * something is consuming the output — a viewer, a poll, or a calibrated fixed camera ({@code
 * DetectionDemandPort}); {@link #ALWAYS} means inference runs regardless, so unattended detection
 * events and persisted detections keep flowing with nobody watching.
 *
 * <p><b>This enum answers one question only: should inference run at all.</b> It is deliberately not
 * consulted by the <em>live</em> gate ({@code StreamPipeline#liveGateOpen()}) — an {@code ALWAYS}
 * stream with no viewer still has nothing to show a screen, so its live read models close exactly as
 * an {@link #ON_VIEW} stream's would; only the durable path (persistence, the {@code DETECTION}
 * platform event, {@code DetectionEventEngine}) stays open. See
 * docs/plans/active/ALWAYS-ON-FLOW-PLAN.md &sect;4 "the gate is three questions, not two" for the
 * full decomposition.
 *
 * <h2>Storage</h2>
 * Read from the owning {@code Asset}'s existing free-form {@code attributes} map, under {@link
 * #ATTRIBUTE_KEY} — not a new column, not a new context, not a Flyway migration: {@code
 * Asset#attributes} already exists for exactly this, is already editable through {@code
 * DefaultAssetService}'s {@code AssetEdit} path ({@code PATCH /api/assets/{id}}, no manager
 * authority required since attribute edits are not a "managed field"), and attribute changes are
 * already audited for free. Parsed at the edge, by {@link #fromAttributeValue(String)} — never
 * stored as this enum itself, since {@code vision-warehouse} (the pure leaf context {@code Asset}
 * lives in) must not know this vocabulary exists.
 *
 * <h2>The honest ceiling (docs/plans/active/ALWAYS-ON-FLOW-PLAN.md &sect;3)</h2>
 * {@code ALWAYS} is strictly opt-in per asset for a reason beyond taste: there is no platform-wide
 * inference budget. One {@code cv-service} process saturates at roughly 3&ndash;4 streams at 10 fps
 * (docs/conclusions/CV-RATE-BUDGET.md), and {@code maxInFlightInferences} bounds only one stream at a
 * time — nothing counts concurrent inference load across streams. Setting more than a handful of
 * assets to {@code ALWAYS} on a single-{@code cv-service} deployment will starve them all of
 * throughput; a fleet-wide scheduler (plan wave D3) is not built. Flip this attribute deliberately,
 * per asset, not as a fleet default.
 */
public enum DetectionPolicy {
    /** Infer only while something is consuming the output. Today's only behavior; the default. */
    ON_VIEW,
    /** Infer regardless of viewer demand — see this enum's own javadoc for the capacity cost. */
    ALWAYS;

    /**
     * The {@code Asset#attributes} key this policy is stored under. Not a Spring {@code
     * @ConfigurationProperties} key — a free-form attribute map entry, so it is a plain string
     * constant rather than a binding path.
     */
    public static final String ATTRIBUTE_KEY = "cv.detection-policy";

    private static final String ALWAYS_VALUE = "always";
    private static final String ON_VIEW_VALUE = "on-view";

    /**
     * Parses one attribute value into a policy, never throwing: an absent, blank, or unrecognized
     * value all resolve to {@link #ON_VIEW} — the fail-closed direction. A garbage value silently
     * defaulting to today's own behavior is the safe failure; the unsafe one would be a typo (or a
     * future vocabulary this parser does not know yet) accidentally granting an asset uncapped,
     * always-on inference against the ceiling this enum's own javadoc documents.
     *
     * @param attributeValue the raw {@code Asset#attributes} value, or {@code null}
     * @return {@link #ALWAYS} only for the exact (trimmed, case-insensitive) value {@code "always"};
     *         {@link #ON_VIEW} for everything else, including {@code null}
     */
    public static DetectionPolicy fromAttributeValue(String attributeValue) {
        if (attributeValue == null) {
            return ON_VIEW;
        }
        String normalized = attributeValue.trim().toLowerCase(Locale.ROOT);
        return ALWAYS_VALUE.equals(normalized) ? ALWAYS : ON_VIEW;
    }

    /** The exact vocabulary this policy round-trips through {@link #ATTRIBUTE_KEY} as. */
    public String toAttributeValue() {
        return this == ALWAYS ? ALWAYS_VALUE : ON_VIEW_VALUE;
    }
}
