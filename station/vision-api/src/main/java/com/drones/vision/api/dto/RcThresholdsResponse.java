package com.drones.vision.api.dto;

/**
 * One severity-threshold group inside {@link OpsThresholdsResponse} — see that record's own javadoc
 * for the frozen wire contract this backs (docs/plans/active/FLY-CONTROL-UX-PLAN.md §2 "Frozen
 * contract — neutral gate").
 *
 * @param neutralTolerancePercent how far (in percent) a live stick axis may sit from its rest
 *                                position and still count as "neutral" for the web-side arm gate
 */
public record RcThresholdsResponse(int neutralTolerancePercent) {
}
