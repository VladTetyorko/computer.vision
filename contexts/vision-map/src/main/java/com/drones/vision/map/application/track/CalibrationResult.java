package com.drones.vision.map.application.track;

import com.drones.vision.kernel.FixedCameraPose;

/**
 * What {@link CameraCalibrationSolver#solve} returns — the frozen {@code POST
 * /api/assets/{assetId}/camera-pose/calibration} response shape (docs/plans/active/
 * FIXED-CAMERA-GEO-PLAN.md §5, decision D5). <b>Never persisted by the solver itself</b> — the
 * operator reviews {@link #pose()} and {@link #quality()} and confirms with a separate {@code PUT}
 * (via {@link CameraPoseService#put}).
 *
 * <p>Exactly one of two shapes, enforced here rather than left to convention:
 * <ul>
 *   <li>{@link #solved()} {@code == true}: {@link #pose()} and {@link #quality()} are non-null,
 *       {@link #reason()} is {@code null}.</li>
 *   <li>{@link #solved()} {@code == false}: {@link #pose()} and {@link #quality()} are {@code null},
 *       {@link #reason()} names why — one of the frozen strings {@link CameraCalibrationSolver}
 *       produces.</li>
 * </ul>
 *
 * <p>{@link #rmsErrorPixels()} is {@code null} when a refusal fired before any fit was attempted (a
 * degenerate-geometry refusal — too few bearing degrees of spread, or a landmark too close to the
 * camera) and non-null in every other case, including a residual-exceeded refusal (where it names
 * the residual that caused the refusal) and the {@code UNDETERMINED}-quality 2-point success (where
 * it is computed but, per D5, not diagnostic).
 *
 * @param solved         whether a pose was found within the configured tolerance
 * @param pose           the solved pose's five geometric numbers ({@code assetId}/audit metadata are
 *                        not this solver's concern — see {@link FixedCameraPose}), or {@code null}
 * @param rmsErrorPixels the fit's residual converted to pixels, or {@code null} if never computed
 * @param quality        {@link CalibrationQuality#GOOD}/{@link CalibrationQuality#UNDETERMINED}, or
 *                        {@code null} when not solved
 * @param reason         why the solve was refused, or {@code null} when solved
 */
public record CalibrationResult(boolean solved, FixedCameraPose pose, Double rmsErrorPixels,
                                 CalibrationQuality quality, String reason) {

    public CalibrationResult {
        if (solved) {
            if (pose == null) {
                throw new IllegalArgumentException("CalibrationResult pose must not be null when solved");
            }
            if (quality == null) {
                throw new IllegalArgumentException("CalibrationResult quality must not be null when solved");
            }
            if (reason != null) {
                throw new IllegalArgumentException("CalibrationResult reason must be null when solved");
            }
        } else {
            if (pose != null) {
                throw new IllegalArgumentException("CalibrationResult pose must be null when not solved");
            }
            if (quality != null) {
                throw new IllegalArgumentException("CalibrationResult quality must be null when not solved");
            }
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("CalibrationResult reason must not be blank when not solved");
            }
        }
    }

    /**
     * A successful solve.
     *
     * @param pose           the solved pose
     * @param rmsErrorPixels the fit's residual, pixels
     * @param quality        {@link CalibrationQuality#GOOD} or {@link CalibrationQuality#UNDETERMINED}
     * @return a solved result
     */
    static CalibrationResult solved(FixedCameraPose pose, double rmsErrorPixels, CalibrationQuality quality) {
        return new CalibrationResult(true, pose, rmsErrorPixels, quality, null);
    }

    /**
     * A refusal fired before any fit was attempted — a degenerate-geometry check — so no residual
     * exists to report.
     *
     * @param reason why the solve was refused; one of {@link CameraCalibrationSolver}'s frozen strings
     * @return a refused result with a {@code null} {@link #rmsErrorPixels()}
     */
    static CalibrationResult refused(String reason) {
        return new CalibrationResult(false, null, null, null, reason);
    }

    /**
     * A refusal fired after the fit ran (the residual exceeded the configured ceiling), so the
     * residual that caused the refusal is reported alongside the reason.
     *
     * @param reason         why the solve was refused; one of {@link CameraCalibrationSolver}'s
     *                       frozen strings
     * @param rmsErrorPixels the residual that triggered the refusal, pixels
     * @return a refused result carrying the computed residual
     */
    static CalibrationResult refused(String reason, double rmsErrorPixels) {
        return new CalibrationResult(false, null, rmsErrorPixels, null, reason);
    }
}
