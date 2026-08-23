package com.drones.vision.platform;

/**
 * Kind of semantic occurrence carried by an {@link Event}.
 */
public enum EventType {
    DETECTION,
    DEVICE_ONLINE,
    DEVICE_OFFLINE,
    STREAM_STARTED,
    STREAM_STOPPED,
    PIPELINE_ERROR,
    TRAINING,
    GEOFENCE_BREACH,
    /**
     * Raised by {@code contexts/vision-flight}'s {@code DefaultTrackCorrectionService} (a {@code
     * DivergenceRule} rising edge only — docs/plans/done/VISUAL-GEO-V2-PLAN.md §4.5/§3.4) when the
     * HEAVY-A visual-corrected track and the aircraft's own reported GNSS position disagree beyond
     * Nσ for N consecutive {@code CONFIRMED} fixes. {@code streamId} is always {@code null} (asset-
     * scoped, not stream-scoped, the {@code GEOFENCE_BREACH} precedent); attributes carry {@code
     * {assetId, usageId, separationMeters, sigmaMeters}}.
     */
    POSITION_DIVERGENCE
}
