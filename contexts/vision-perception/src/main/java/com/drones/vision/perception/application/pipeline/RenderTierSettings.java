package com.drones.vision.perception.application.pipeline;

/**
 * Tunables for {@link WorldModel}'s server-side render-tier assignment (docs/plans/active/
 * CV-ORCHESTRATION-PLAN.md &sect;4.6) — the Java port of {@code
 * station/vision-web/src/app/shared/player/detection-overlay-logic.ts}'s {@code detectionTiers}.
 * See {@link WorldModel}'s own "Render tier" javadoc section for the two deliberate deviations
 * from that client algorithm (no hover/class-hover promotion; {@link #subScaleFraction()} in
 * place of a CSS-pixel threshold) that this record's shape follows from.
 *
 * @param notableTopK                 how many non-{@code T0}/non-{@code T3} objects {@link
 *                                     WorldModel} promotes to {@code T1} purely by (box area
 *                                     &times; confidence), on top of whichever are already
 *                                     promoted by movement — mirrors the client's {@code
 *                                     NOTABLE_TOP_K}; must be positive
 * @param movingDisplacementThreshold minimum normalized box-centre displacement since the
 *                                    previous update for an object to count as moving (promoted
 *                                    to {@code T1} regardless of the {@link #notableTopK()}
 *                                    budget) — mirrors the client's {@code
 *                                    MOVING_DISPLACEMENT_THRESHOLD}; must not be negative
 * @param subScaleFraction            an object whose elected box is narrower than this fraction
 *                                    of the frame on <b>both</b> axes renders as a {@code T3} dot
 *                                    instead of a box — the normalized analogue of the client's
 *                                    {@code SUB_SCALE_PX}; must be in {@code (0, 1)}
 */
public record RenderTierSettings(int notableTopK, double movingDisplacementThreshold, double subScaleFraction) {

    /** @see #notableTopK() — byte-identical to the client's {@code NOTABLE_TOP_K}. */
    private static final int DEFAULT_NOTABLE_TOP_K = 5;

    /**
     * @see #movingDisplacementThreshold() — byte-identical to the client's {@code
     *      MOVING_DISPLACEMENT_THRESHOLD}, reused as-is even though {@link WorldModel} measures a
     *      one-frame delta rather than the client's trail-window integration (see that class's
     *      javadoc for why the two are close enough to share a threshold).
     */
    private static final double DEFAULT_MOVING_DISPLACEMENT_THRESHOLD = 0.02;

    /**
     * @see #subScaleFraction() — {@code 0.01} (1% of frame width/height) approximates the
     *      client's {@code SUB_SCALE_PX = 12} against a typical letterboxed video element in the
     *      900-1600px range this deployment renders at. Unlike {@link #notableTopK()}/{@link
     *      #movingDisplacementThreshold()} this cannot be byte-identical to the client's constant
     *      because the server has no viewport size to divide pixels by — see {@link WorldModel}'s
     *      javadoc.
     */
    private static final double DEFAULT_SUB_SCALE_FRACTION = 0.01;

    public RenderTierSettings {
        if (notableTopK <= 0) {
            throw new IllegalArgumentException("notableTopK must be positive, was " + notableTopK);
        }
        if (!Double.isFinite(movingDisplacementThreshold) || movingDisplacementThreshold < 0) {
            throw new IllegalArgumentException(
                    "movingDisplacementThreshold must not be negative: " + movingDisplacementThreshold);
        }
        if (!Double.isFinite(subScaleFraction) || subScaleFraction <= 0.0 || subScaleFraction >= 1.0) {
            throw new IllegalArgumentException("subScaleFraction must be in (0,1), was " + subScaleFraction);
        }
    }

    /** Every value either byte-identical to the client's own constant, or the documented approximation. */
    public static RenderTierSettings defaults() {
        return new RenderTierSettings(DEFAULT_NOTABLE_TOP_K, DEFAULT_MOVING_DISPLACEMENT_THRESHOLD,
                DEFAULT_SUB_SCALE_FRACTION);
    }
}
