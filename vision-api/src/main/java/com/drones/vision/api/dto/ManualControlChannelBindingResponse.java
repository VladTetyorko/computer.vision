package com.drones.vision.api.dto;

import com.drones.vision.api.ws.ManualControlWebSocketHandler;

/**
 * One entry of the {@code channelMap} array on the {@code /ws/manual-control} {@code engaged}
 * frame (docs/RC-CONTROL-PHASE1-PLAN.md §4) — which gamepad axis/button drives which RC channel,
 * with a short human label for the cockpit UI to display next to it.
 *
 * <p>{@code source}/{@code sourceIndex}/{@code rcChannel} mirror {@code
 * com.drones.vision.domain.model.ControlBinding} field-for-field, serialized as plain wire values
 * ({@code source} as its enum name, e.g. {@code "AXIS"}/{@code "BUTTON"}) rather than exposing the
 * domain record itself across the boundary. {@code label} has no domain counterpart —
 * {@code ControlBinding} carries no label field — so {@code ManualControlWebSocketHandler} builds
 * it itself from {@code (source, rcChannel)}, matching docs/RC-CONTROL-PHASE1-PLAN.md §5's default
 * map (e.g. {@code "Roll"} for {@code AXIS} channel 1).
 *
 * @param source      {@code "AXIS"} or {@code "BUTTON"}
 * @param sourceIndex index into the Gamepad API's {@code axes}/{@code buttons} array
 * @param rcChannel   the 1-based RC channel this binding drives
 * @param label       a short human label, e.g. {@code "Roll"}, {@code "Aux 1"}
 */
public record ManualControlChannelBindingResponse(String source, int sourceIndex, int rcChannel, String label) {
}
