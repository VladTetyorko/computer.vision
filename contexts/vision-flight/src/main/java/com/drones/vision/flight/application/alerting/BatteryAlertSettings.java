package com.drones.vision.flight.application.alerting;

/**
 * The two percentage thresholds {@link BatteryMonitor} evaluates every sample against
 * (docs/plans/active/ASSET-FLOWS-PLAN.md §2, the D6 frozen contract): {@code criticalPercent} is
 * the rising-edge trigger for {@link com.drones.vision.platform.EventType#BATTERY_LOW};
 * {@code warningPercent} is the re-arm line the reading must climb back to before another crossing
 * fires again (hysteresis, so a battery hovering right at the critical line does not spam one event
 * per sample).
 *
 * <p>Sourced at the composition root from {@code vision.ops.battery.critical-percent} /
 * {@code vision.ops.battery.warning-percent} — <b>no magic numbers</b> (CLAUDE.md rule 1) — with
 * {@link #defaults()} holding the same values the properties themselves default to
 * (10/25) so this record is a correct fallback even before those properties exist anywhere. This
 * cycle's BK3 wave owns documenting the properties in root {@code application.yaml} and the
 * {@code GET /api/ops/thresholds} read surface over the *same* two numbers — this record does not
 * depend on either landing to behave correctly, since {@code vision-app}'s {@code @Value} binding
 * carries its own inline default.
 *
 * @param criticalPercent percent, [0,100]; at or below this, {@link BatteryMonitor} raises {@link
 *                         com.drones.vision.platform.EventType#BATTERY_LOW} on the rising edge
 * @param warningPercent   percent, [0,100], strictly greater than {@code criticalPercent}; at or
 *                         above this, {@link BatteryMonitor} re-arms (silently) for the next crossing
 */
public record BatteryAlertSettings(double criticalPercent, double warningPercent) {

    public BatteryAlertSettings {
        if (Double.isNaN(criticalPercent) || criticalPercent < 0 || criticalPercent > 100) {
            throw new IllegalArgumentException("criticalPercent must be within [0,100]: " + criticalPercent);
        }
        if (Double.isNaN(warningPercent) || warningPercent < 0 || warningPercent > 100) {
            throw new IllegalArgumentException("warningPercent must be within [0,100]: " + warningPercent);
        }
        if (warningPercent <= criticalPercent) {
            throw new IllegalArgumentException(
                    "warningPercent (" + warningPercent + ") must exceed criticalPercent (" + criticalPercent + ")");
        }
    }

    /**
     * @return the D6 frozen defaults: critical 10%, warning 25% — byte-identical to the {@code
     *         vision.ops.battery.*} properties' own inline defaults
     */
    public static BatteryAlertSettings defaults() {
        return new BatteryAlertSettings(10.0, 25.0);
    }
}
