package com.drones.vision.domain.model;

import java.util.Arrays;
import java.util.List;

/**
 * A full set of {@link ControlBinding}s: which gamepad axis/button drives which RC channel
 * (docs/RC-CONTROL-PHASE1-PLAN.md §1/§5).
 *
 * @param bindings the control bindings that make up this map; defensively copied
 */
public record ChannelMap(List<ControlBinding> bindings) {

    public ChannelMap {
        if (bindings == null) {
            throw new IllegalArgumentException("ChannelMap bindings must not be null");
        }
        bindings = List.copyOf(bindings);
    }

    /**
     * The frozen v1 default map (docs/RC-CONTROL-PHASE1-PLAN.md §5): gamepad axes 0..3 (roll/
     * pitch/throttle/yaw) onto RC channels 1..4 — centered at 1500µs, {@code [1000,2000]} full
     * travel, no deadband, not reversed; gamepad buttons 0..3 (aux 1..4, e.g. the flight-mode
     * switch) onto RC channels 5..8 — {@code [1000,2000]}, not reversed. Channels 9..18 are not
     * used — see {@link RcChannels}'s class javadoc for why.
     *
     * @return the default channel map
     */
    public static ChannelMap defaultMap() {
        return new ChannelMap(List.of(
                axis(0, 1),  // Roll (aileron)
                axis(1, 2),  // Pitch (elevator)
                axis(2, 3),  // Throttle
                axis(3, 4),  // Yaw (rudder)
                aux(0, 5),   // Aux 1 (flight-mode switch)
                aux(1, 6),   // Aux 2
                aux(2, 7),   // Aux 3
                aux(3, 8)    // Aux 4
        ));
    }

    private static ControlBinding axis(int sourceIndex, int rcChannel) {
        return new ControlBinding(ControlBinding.Source.AXIS, sourceIndex, rcChannel,
                RcChannels.MIN_MICROS, 1500, RcChannels.MAX_MICROS, 0.0, false);
    }

    private static ControlBinding aux(int sourceIndex, int rcChannel) {
        // A button has no natural center (§5's table lists "1000/—/2000" — center unused). Setting
        // centerMicros = minMicros here is a modeling choice, not part of the mapping math:
        // ControlBinding#toMicros's button formula ignores centerMicros entirely and maps
        // 0 (unpressed) -> minMicros, 1 (pressed) -> maxMicros directly. minMicros<=centerMicros
        // <=maxMicros still needs a value to satisfy the record's invariant, so it doubles as that.
        return new ControlBinding(ControlBinding.Source.BUTTON, sourceIndex, rcChannel,
                RcChannels.MIN_MICROS, RcChannels.MIN_MICROS, RcChannels.MAX_MICROS, 0.0, false);
    }

    /**
     * Maps the latest gamepad reading through every binding into one {@link RcChannels} frame,
     * covering channels 1..N where N is the highest {@link ControlBinding#rcChannel()} any
     * binding targets. A channel no binding targets is sent as {@link RcChannels#IGNORE}.
     *
     * <p>Missing input is handled safely, never rejected: a binding whose {@link
     * ControlBinding#sourceIndex()} falls outside {@code axes}/{@code buttons} (including a
     * {@code null} or short list) reads as {@code 0.0} — center for an axis, unpressed for a
     * button — exactly as if the operator's gamepad simply doesn't report that many controls.
     *
     * @param axes    the gamepad's axis readings, {@code [-1,1]} each; may be {@code null}/short
     * @param buttons the gamepad's button readings, {@code [0,1]} each; may be {@code null}/short
     * @return the assembled override frame
     */
    public RcChannels apply(List<Double> axes, List<Double> buttons) {
        int channelCount = Math.max(1, bindings.stream().mapToInt(ControlBinding::rcChannel).max().orElse(0));
        Integer[] micros = new Integer[channelCount];
        Arrays.fill(micros, RcChannels.IGNORE);
        for (ControlBinding binding : bindings) {
            List<Double> source = binding.source() == ControlBinding.Source.AXIS ? axes : buttons;
            double raw = read(source, binding.sourceIndex());
            micros[binding.rcChannel() - 1] = binding.toMicros(raw);
        }
        return new RcChannels(Arrays.asList(micros));
    }

    private static double read(List<Double> values, int index) {
        if (values == null || index < 0 || index >= values.size()) {
            return 0.0;
        }
        Double value = values.get(index);
        return value == null ? 0.0 : value;
    }
}
