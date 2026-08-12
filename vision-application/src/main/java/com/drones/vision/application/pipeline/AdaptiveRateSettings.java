package com.drones.vision.application.pipeline;

/**
 * Tunables for {@link DetectionRateController} (docs/plans/active/CV-RATE-CONTROL-PLAN.md wave R2) — one
 * nested record rather than three more components on {@link StreamPipelineSettings}, which already
 * carries fifteen.
 *
 * @param enabled   whether the sample rate may rise above the operator's {@code inferenceFps} when
 *                  the tracked target is about to escape its association budget. {@code false}
 *                  pins the rate to exactly what was asked for — the behaviour before this existed
 * @param maxFps    the hard ceiling the demand may raise the rate to; must be positive. Also
 *                  bounded at runtime by the source's own rate and by measured detector capacity,
 *                  so this is the operator's bandwidth budget rather than a performance promise
 * @param ewmaAlpha smoothing applied to the computed demand, in {@code (0,1]}; lower reacts more
 *                  slowly. Damps the rate against a single noisy frame moving it
 */
public record AdaptiveRateSettings(boolean enabled, double maxFps, double ewmaAlpha) {

    /**
     * On by default, because the failure this closes is silent: a fixed rate loses the target
     * during exactly the manoeuvres an operator is least able to notice it happening
     * (docs/conclusions/CV-RATE-BUDGET.md &sect;2). It can only ever <b>raise</b> the rate above what
     * was configured, never lower it, so a deployment that set a number still gets at least that
     * number.
     */
    public static final boolean DEFAULT_ENABLED = true;

    /**
     * 30 fps: the rate at which a typical camera runs out of frames to offer, so raising the
     * ceiling further would spend bandwidth on deadlines no source could serve. A deployment on a
     * metered link should lower it rather than disable the loop — a capped adaptive rate still
     * spends its frames where they are needed.
     */
    public static final double DEFAULT_MAX_FPS = 30.0;

    /** Matches {@code StreamPipelineSettings#measuredFpsEwmaAlpha()} — one smoothing convention. */
    public static final double DEFAULT_EWMA_ALPHA = 0.2;

    public AdaptiveRateSettings {
        if (!Double.isFinite(maxFps) || maxFps <= 0.0) {
            throw new IllegalArgumentException("adaptive rate maxFps must be positive, was " + maxFps);
        }
        if (!Double.isFinite(ewmaAlpha) || ewmaAlpha <= 0.0 || ewmaAlpha > 1.0) {
            throw new IllegalArgumentException("adaptive rate ewmaAlpha must be in (0,1], was " + ewmaAlpha);
        }
    }

    public static AdaptiveRateSettings defaults() {
        return new AdaptiveRateSettings(DEFAULT_ENABLED, DEFAULT_MAX_FPS, DEFAULT_EWMA_ALPHA);
    }

    /** The loop off: the sample rate is exactly the configured one, whatever the target is doing. */
    public static AdaptiveRateSettings disabled() {
        return new AdaptiveRateSettings(false, DEFAULT_MAX_FPS, DEFAULT_EWMA_ALPHA);
    }
}
