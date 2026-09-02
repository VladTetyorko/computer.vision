package com.drones.vision.api.dto;

/**
 * The frozen wire contract behind {@code GET /api/ops/thresholds}
 * (docs/plans/active/ASSET-FLOWS-PLAN.md §2 "Battery thresholds") — the ONE severity source the Fly
 * cockpit's OSD and the fleet attention-logic both read, replacing the two previously hardcoded and
 * disagreeing thresholds (OSD's own 20/45, fleet's own 20/10). {@code rc} joined this record for
 * docs/plans/active/FLY-CONTROL-UX-PLAN.md §2 "Frozen contract — neutral gate" — the web-side
 * neutral-stick arm gate's tolerance, exactly mirroring the {@code battery} pattern.
 *
 * <p>{@code vision-app}'s {@code com.drones.vision.app.config.wiring.OpsWiringConfiguration} builds
 * the single instance of this record this endpoint ever serves, straight off {@code
 * VisionOpsProperties} at startup — these are deploy-time config, not a per-request computation, the
 * same shape {@code com.drones.vision.api.dto.CvTrackersResponse} already uses for a config-backed
 * roster.
 *
 * @param battery battery urgency thresholds; see {@link BatteryThresholdsResponse}
 * @param rc      RC stick neutral-tolerance threshold; see {@link RcThresholdsResponse}
 */
public record OpsThresholdsResponse(BatteryThresholdsResponse battery, RcThresholdsResponse rc) {
}
