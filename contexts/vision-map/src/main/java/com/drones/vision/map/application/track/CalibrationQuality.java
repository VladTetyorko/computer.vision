package com.drones.vision.map.application.track;

/**
 * How much to trust a successful {@link CalibrationResult} (docs/plans/active/FIXED-CAMERA-GEO-PLAN.md
 * decision D5, frozen wire spellings, §5). Only meaningful when {@link CalibrationResult#solved()}.
 */
public enum CalibrationQuality {
    /** {@code N >= 3} landmarks and the residual is within the configured ceiling. */
    GOOD,
    /** Exactly 2 landmarks — the fit exists but nothing independently checks it; the UI should ask for a third point. */
    UNDETERMINED
}
