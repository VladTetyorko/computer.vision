package com.drones.vision.api.dto;

/**
 * One severity-threshold group inside {@link OpsThresholdsResponse} — see that record's own javadoc
 * for the frozen wire contract this backs (docs/plans/active/ASSET-FLOWS-PLAN.md §2 "Battery
 * thresholds").
 *
 * @param warningPercent  battery percent at/below which a vehicle is "battery low"
 * @param criticalPercent battery percent at/below which a vehicle is "battery critical"; always
 *                        strictly less than {@code warningPercent}
 */
public record BatteryThresholdsResponse(int warningPercent, int criticalPercent) {
}
