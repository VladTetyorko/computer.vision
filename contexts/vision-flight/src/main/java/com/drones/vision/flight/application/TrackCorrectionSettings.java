package com.drones.vision.flight.application;

import java.time.Duration;

/**
 * The Java half of D5's two-owner gate (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.6/§4.3/§4.5) —
 * every knob {@code DefaultTrackCorrectionService} needs, sourced from {@code
 * vision.geo.visual.gate.*}/{@code vision.geo.visual.divergence.*} in the running app (CLAUDE.md rule
 * 1 — no magic numbers in this module).
 *
 * @param gate       the §4.3 confirmation gate: radius floor/ceiling and consecutive agreement
 * @param divergence the §4.5 divergence alarm's thresholds
 */
public record TrackCorrectionSettings(GateSettings gate, DivergenceSettings divergence) {

    public TrackCorrectionSettings {
        if (gate == null) {
            throw new IllegalArgumentException("TrackCorrectionSettings gate must not be null");
        }
        if (divergence == null) {
            throw new IllegalArgumentException("TrackCorrectionSettings divergence must not be null");
        }
    }

    /**
     * @param minRadiusMeters        a fix claiming a tighter radius than this is not believed at all
     *                               ({@code vision.geo.visual.gate.min-radius-meters}); non-negative
     * @param maxRadiusMeters        above this, a fix may reach at most {@link
     *                               com.drones.vision.flight.domain.model.CorrectionStatus#PROBABLE},
     *                               never {@code CONFIRMED} ({@code
     *                               vision.geo.visual.gate.max-radius-meters}); must exceed {@code
     *                               minRadiusMeters}
     * @param confirmConsecutive     how many consecutive agreeing fixes are required before {@code
     *                               CONFIRMED} ({@code vision.geo.visual.gate.confirm-consecutive});
     *                               at least 1
     * @param confirmAgreementMeters the maximum pairwise separation allowed within that consecutive
     *                               run ({@code vision.geo.visual.gate.confirm-agreement-meters});
     *                               positive
     * @param confirmWindow          the consecutive run must fit inside this wall-clock span ({@code
     *                               vision.geo.visual.gate.confirm-window}); positive
     */
    public record GateSettings(double minRadiusMeters, double maxRadiusMeters, int confirmConsecutive,
                                double confirmAgreementMeters, Duration confirmWindow) {
        public GateSettings {
            if (Double.isNaN(minRadiusMeters) || Double.isInfinite(minRadiusMeters) || minRadiusMeters < 0) {
                throw new IllegalArgumentException(
                        "TrackCorrectionSettings.GateSettings minRadiusMeters must be finite and non-negative: "
                                + minRadiusMeters);
            }
            if (Double.isNaN(maxRadiusMeters) || Double.isInfinite(maxRadiusMeters)
                    || maxRadiusMeters <= minRadiusMeters) {
                throw new IllegalArgumentException(
                        "TrackCorrectionSettings.GateSettings maxRadiusMeters must be finite and greater than "
                                + "minRadiusMeters (" + minRadiusMeters + "): " + maxRadiusMeters);
            }
            if (confirmConsecutive < 1) {
                throw new IllegalArgumentException(
                        "TrackCorrectionSettings.GateSettings confirmConsecutive must be at least 1: "
                                + confirmConsecutive);
            }
            if (Double.isNaN(confirmAgreementMeters) || Double.isInfinite(confirmAgreementMeters)
                    || confirmAgreementMeters <= 0) {
                throw new IllegalArgumentException(
                        "TrackCorrectionSettings.GateSettings confirmAgreementMeters must be finite and positive: "
                                + confirmAgreementMeters);
            }
            if (confirmWindow == null || confirmWindow.isNegative() || confirmWindow.isZero()) {
                throw new IllegalArgumentException(
                        "TrackCorrectionSettings.GateSettings confirmWindow must be positive: " + confirmWindow);
            }
        }
    }

    /**
     * @param sigma                   how many combined 1-sigma widths a separation must exceed to
     *                                qualify ({@code vision.geo.visual.divergence.sigma}); positive
     * @param consecutiveFixes        how many consecutive qualifying {@code CONFIRMED} fixes latch
     *                                the alarm ({@code
     *                                vision.geo.visual.divergence.consecutive-fixes}); at least 1
     * @param clearAfter              how long a latched alarm may go without a qualifying fix before
     *                                it clears ({@code vision.geo.visual.divergence.clear-after});
     *                                positive
     * @param defaultRawRadiusMeters  the raw fix's assumed 1-sigma when no HDOP-derived radius is
     *                                available ({@code
     *                                vision.geo.visual.divergence.default-raw-radius-meters});
     *                                non-negative
     */
    public record DivergenceSettings(double sigma, int consecutiveFixes, Duration clearAfter,
                                      double defaultRawRadiusMeters) {
        public DivergenceSettings {
            if (Double.isNaN(sigma) || Double.isInfinite(sigma) || sigma <= 0) {
                throw new IllegalArgumentException(
                        "TrackCorrectionSettings.DivergenceSettings sigma must be finite and positive: " + sigma);
            }
            if (consecutiveFixes < 1) {
                throw new IllegalArgumentException(
                        "TrackCorrectionSettings.DivergenceSettings consecutiveFixes must be at least 1: "
                                + consecutiveFixes);
            }
            if (clearAfter == null || clearAfter.isNegative() || clearAfter.isZero()) {
                throw new IllegalArgumentException(
                        "TrackCorrectionSettings.DivergenceSettings clearAfter must be positive: " + clearAfter);
            }
            if (Double.isNaN(defaultRawRadiusMeters) || Double.isInfinite(defaultRawRadiusMeters)
                    || defaultRawRadiusMeters < 0) {
                throw new IllegalArgumentException(
                        "TrackCorrectionSettings.DivergenceSettings defaultRawRadiusMeters must be finite and "
                                + "non-negative: " + defaultRawRadiusMeters);
            }
        }
    }
}
