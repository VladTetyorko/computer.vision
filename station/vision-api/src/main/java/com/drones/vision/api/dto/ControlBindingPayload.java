package com.drones.vision.api.dto;

import com.drones.vision.api.support.ControlEnumParsing;
import com.drones.vision.flight.domain.model.ControlBinding;
import com.drones.vision.flight.domain.model.ControlFunction;
import com.drones.vision.flight.domain.model.ControlInputKind;

/**
 * One channel binding on the {@code /api/control-profiles} wire (docs/plans/active/
 * CONTROLLER-SETUP-CONTEXT.md §4.4) — a physical control that <em>streams</em> into an RC channel,
 * as opposed to an {@link ActionBindingPayload} that fires a one-shot command.
 *
 * <p>One shape serves both directions, because an operator edits exactly what they were shown. The
 * two exceptions are marked below: {@code travel} and {@code label} are <b>derived</b> on the way
 * out and ignored on the way in — {@link ControlBinding} computes travel from its own microseconds
 * and takes its label from its {@link ControlFunction}, so accepting either as input would let a
 * client assert something the domain would immediately contradict.
 *
 * @param source       {@code "AXIS"} or {@code "BUTTON"} — which Gamepad array the value is read from
 * @param kind         {@code "AXIS"}, {@code "BUTTON"}, {@code "SWITCH_2"} or {@code "SWITCH_3"} —
 *                     what the operator says the control physically is, which is a different
 *                     question from {@code source} (decision C1: a 3-position switch is read from
 *                     the axes array)
 * @param function     {@code "ROLL"}, {@code "PITCH"}, {@code "THROTTLE"}, {@code "YAW"},
 *                     {@code "STEERING"} or {@code "AUX_1".."AUX_4"}
 * @param sourceIndex  index into the Gamepad API's {@code axes}/{@code buttons} array
 * @param rcChannel    the 1-based RC channel this binding drives
 * @param minMicros    pulse width at the control's minimum
 * @param centerMicros pulse width at rest; equal to {@code minMicros} for a unidirectional control
 * @param maxMicros    pulse width at the control's maximum
 * @param deadband     normalized magnitude snapped to rest, {@code [0,1]}
 * @param reversed     whether the control's travel is inverted
 * @param travel       <b>response only</b> — {@code "CENTERED"} or {@code "UNIDIRECTIONAL"}, derived
 * @param label        <b>response only</b> — the function's own human label, derived
 */
public record ControlBindingPayload(String source, String kind, String function, int sourceIndex, int rcChannel,
                                     int minMicros, int centerMicros, int maxMicros, double deadband,
                                     boolean reversed, String travel, String label) {

    /**
     * Maps one domain binding to its wire form.
     *
     * @param binding the binding to serialize
     * @return the wire entry, with {@code travel}/{@code label} filled in
     */
    public static ControlBindingPayload from(ControlBinding binding) {
        return new ControlBindingPayload(binding.source().name(), binding.kind().name(), binding.function().name(),
                binding.sourceIndex(), binding.rcChannel(), binding.minMicros(), binding.centerMicros(),
                binding.maxMicros(), binding.deadband(), binding.reversed(), binding.travel().name(),
                binding.function().label());
    }

    /**
     * Converts this request entry to a domain binding.
     *
     * @return the equivalent {@link ControlBinding}
     * @throws IllegalArgumentException if an enum name is missing/unrecognized, or the numbers fail
     *                                   {@link ControlBinding}'s own validation (channel out of
     *                                   range, microseconds out of order, a kind its source cannot
     *                                   report)
     */
    public ControlBinding toBinding() {
        return new ControlBinding(ControlEnumParsing.source(source), ControlEnumParsing.parse(ControlInputKind.class,
                kind, "kind"), ControlEnumParsing.parse(ControlFunction.class, function, "function"), sourceIndex,
                rcChannel, minMicros, centerMicros, maxMicros, deadband, reversed);
    }
}
