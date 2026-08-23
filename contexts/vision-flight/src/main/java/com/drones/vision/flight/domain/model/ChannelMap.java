package com.drones.vision.flight.domain.model;

import java.util.Arrays;
import java.util.List;

/**
 * A full set of {@link ControlBinding}s: which gamepad axis/button drives which RC channel
 * (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md §1/§5).
 *
 * <p>A plain container plus the mapping pass — it does not decide <em>which</em> bindings a given
 * vehicle should have. That is {@link ControlProfile}'s job, because the answer depends on what the
 * machine is (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §2 P2). The airframe-blind
 * {@code defaultMap()} this class used to expose is gone: it rested a copter's throttle at half
 * power, and nothing in the type could tell that was wrong.
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
