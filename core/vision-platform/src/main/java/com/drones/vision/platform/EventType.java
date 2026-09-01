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
    POSITION_DIVERGENCE,
    /**
     * Raised by {@code contexts/vision-flight}'s {@code LinkLossNotifier}
     * (docs/plans/active/ASSET-FLOWS-PLAN.md §2, wave S4) once per link-failure edge, when the
     * FLEET-RADIO R4 typed link-failure signal ({@code mavlink-core}'s {@code
     * MavlinkSession#onLinkFailure}, surfaced to a context through a device's supervised {@code
     * TelemetrySourcePort} outage callback) reports a device's telemetry source has genuinely
     * failed — never on an ordinary heartbeat miss or an intentional stream stop. {@code streamId}
     * is always {@code null} (asset-scoped, the {@code GEOFENCE_BREACH} precedent); attributes carry
     * {@code {assetId}}.
     */
    LINK_LOST,
    /**
     * Raised by {@code contexts/vision-flight}'s {@code BatteryMonitor}
     * (docs/plans/active/ASSET-FLOWS-PLAN.md §2, wave S4) on the rising edge of an asset's reported
     * battery percentage crossing at or below {@code vision.ops.battery.critical-percent} (default
     * 10) — never repeated while it stays low, and re-armed only once the reading climbs back at or
     * above {@code vision.ops.battery.warning-percent} (default 25), the hysteresis band that keeps
     * a battery hovering near the critical line from spamming one event per sample. {@code streamId}
     * is always {@code null} (asset-scoped, the {@code GEOFENCE_BREACH} precedent); attributes carry
     * {@code {assetId, batteryPercent}}.
     */
    BATTERY_LOW
}
