package com.drones.vision.api.dto;

import com.drones.vision.flight.domain.model.ControlBinding;

/**
 * One entry of the {@code channelMap} array on the {@code /ws/manual-control} {@code engaged}
 * frame (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md §4) — which gamepad axis/button drives which RC
 * channel, what it does to the vehicle, and where it rests.
 *
 * <p>Every field mirrors {@code com.drones.vision.flight.domain.model.ControlBinding}, serialized as
 * plain wire values (enums as their names) rather than exposing the domain record itself across the
 * boundary.
 *
 * <h2>{@code label} is no longer invented here</h2>
 * This DTO used to receive a label that {@code ManualControlWebSocketHandler} built from
 * {@code (source, rcChannel)} with a hardcoded {@code case 1 -> "Roll"} — a multirotor assumption
 * living in the presentation layer, where nothing could correct it for a ground vehicle whose
 * channel 1 is steering. The binding now carries its own {@link
 * com.drones.vision.flight.domain.model.ControlFunction}, and the label is simply that function's
 * own (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §2 P5).
 *
 * @param source      {@code "AXIS"} or {@code "BUTTON"} — what kind of physical control feeds it
 * @param function    what it does to the vehicle: {@code "ROLL"}, {@code "PITCH"}, {@code "THROTTLE"},
 *                    {@code "YAW"}, {@code "STEERING"}, {@code "AUX_1".."AUX_4"}
 * @param travel      {@code "CENTERED"} (rests at {@code centerMicros}, travels both ways) or
 *                    {@code "UNIDIRECTIONAL"} (rests at {@code minMicros}, travels one way). This is
 *                    the field that tells a client whether to draw a throttle as 0..100 with rest at
 *                    the bottom, or as a bidirectional 50-centred control with reverse below it
 * @param sourceIndex index into the Gamepad API's {@code axes}/{@code buttons} array
 * @param rcChannel   the 1-based RC channel this binding drives
 * @param minMicros   pulse width at the control's minimum
 * @param centerMicros pulse width at rest for a {@code CENTERED} binding; equal to {@code minMicros}
 *                    for a {@code UNIDIRECTIONAL} one, which is exactly what makes it unidirectional
 * @param maxMicros   pulse width at the control's maximum
 * @param label       a short human label, e.g. {@code "Throttle"}, {@code "Steering"}
 */
public record ManualControlChannelBindingResponse(String source, String function, String travel, int sourceIndex,
                                                   int rcChannel, int minMicros, int centerMicros, int maxMicros,
                                                   String label) {

    /**
     * Maps one domain binding to its wire form.
     *
     * @param binding the binding to serialize
     * @return the wire entry
     */
    public static ManualControlChannelBindingResponse from(ControlBinding binding) {
        return new ManualControlChannelBindingResponse(binding.source().name(), binding.function().name(),
                binding.travel().name(), binding.sourceIndex(), binding.rcChannel(), binding.minMicros(),
                binding.centerMicros(), binding.maxMicros(), binding.function().label());
    }
}
