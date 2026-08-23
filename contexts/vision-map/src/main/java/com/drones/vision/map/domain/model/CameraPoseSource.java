package com.drones.vision.map.domain.model;

/**
 * How a {@link CameraPose} came to hold the numbers it does (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md
 * decision D4) — mirrors {@link MarkSource}'s "how did this come to exist" role for {@link Mark}.
 */
public enum CameraPoseSource {
    /** Entered by hand — the operator typed the five numbers directly. */
    MANUAL,
    /** Produced by {@code CameraCalibrationSolver} from clicked landmark correspondences, then confirmed by a {@code PUT}. */
    CALIBRATED
}
