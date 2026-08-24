package com.drones.vision.flight.domain.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChannelMapTest {

    /**
     * The shape {@code ChannelMap.defaultMap()} used to hand these tests: axes 0..3 on channels 1..4,
     * buttons 0..3 on channels 5..8. Built locally now that choosing a map is {@link ControlProfile}'s
     * job — these tests are about {@link ChannelMap#apply}'s mapping pass, not about which bindings
     * any particular vehicle should get.
     */
    private static ChannelMap fourAxesFourButtons() {
        return new ChannelMap(List.of(
                ControlBinding.centeredAxis(ControlFunction.ROLL, 0, 1),
                ControlBinding.centeredAxis(ControlFunction.PITCH, 1, 2),
                ControlBinding.centeredAxis(ControlFunction.THROTTLE, 2, 3),
                ControlBinding.centeredAxis(ControlFunction.YAW, 3, 4),
                ControlBinding.button(ControlFunction.AUX_1, 0, 5),
                ControlBinding.button(ControlFunction.AUX_2, 1, 6),
                ControlBinding.button(ControlFunction.AUX_3, 2, 7),
                ControlBinding.button(ControlFunction.AUX_4, 3, 8)));
    }

    @Test
    void rejectsNullBindings() {
        assertThrows(IllegalArgumentException.class, () -> new ChannelMap(null));
    }

    @Test
    void defensivelyCopiesBindings() {
        List<ControlBinding> bindings = new ArrayList<>(List.of(
                new ControlBinding(ControlBinding.Source.AXIS, ControlInputKind.AXIS, ControlFunction.ROLL, 0, 1, 1000, 1500, 2000, 0.0, false)));
        ChannelMap map = new ChannelMap(bindings);

        bindings.add(new ControlBinding(ControlBinding.Source.AXIS, ControlInputKind.AXIS, ControlFunction.ROLL, 1, 2, 1000, 1500, 2000, 0.0, false));

        assertEquals(1, map.bindings().size());
        assertThrows(UnsupportedOperationException.class,
                () -> map.bindings().add(
                        new ControlBinding(ControlBinding.Source.AXIS, ControlInputKind.AXIS, ControlFunction.ROLL, 1, 2, 1000, 1500, 2000, 0.0, false)));
    }

    // --- apply() ---

    @Test
    void applyMapsAKnownAxesAndButtonsVectorToExpectedMicros() {
        ChannelMap map = fourAxesFourButtons();

        RcChannels channels = map.apply(List.of(0.0, -0.12, 1.0, 0.0), List.of(0.0, 1.0));

        assertEquals(Arrays.asList(1500, 1440, 2000, 1500, 1000, 2000, 1000, 1000), channels.microsByChannel());
    }

    @Test
    void applyWithEmptyAxesAndButtonsFallsBackToRestForEveryChannel() {
        ChannelMap map = fourAxesFourButtons();

        RcChannels channels = map.apply(List.of(), List.of());

        assertEquals(Arrays.asList(1500, 1500, 1500, 1500, 1000, 1000, 1000, 1000), channels.microsByChannel());
    }

    @Test
    void applyWithNullAxesAndButtonsFallsBackToRestForEveryChannel() {
        ChannelMap map = fourAxesFourButtons();

        RcChannels channels = map.apply(null, null);

        assertEquals(Arrays.asList(1500, 1500, 1500, 1500, 1000, 1000, 1000, 1000), channels.microsByChannel());
    }

    @Test
    void applyWithShortListsFallsBackToRestForMissingIndicesOnly() {
        ChannelMap map = fourAxesFourButtons();

        // Only axis 0 and button 0 are present; axes 1..3 and buttons 1..3 read as rest (0.0).
        RcChannels channels = map.apply(List.of(1.0), List.of(1.0));

        assertEquals(Arrays.asList(2000, 1500, 1500, 1500, 2000, 1000, 1000, 1000), channels.microsByChannel());
    }

    @Test
    void applyLeavesUnboundChannelsAsIgnore() {
        ChannelMap map = new ChannelMap(List.of(
                new ControlBinding(ControlBinding.Source.AXIS, ControlInputKind.AXIS, ControlFunction.ROLL, 0, 2, 1000, 1500, 2000, 0.0, false),
                new ControlBinding(ControlBinding.Source.BUTTON, ControlInputKind.BUTTON, ControlFunction.AUX_1, 0, 4, 1000, 1000, 2000, 0.0, false)));

        RcChannels channels = map.apply(List.of(0.7), List.of(1.0));

        assertEquals(4, channels.microsByChannel().size());
        assertEquals(RcChannels.IGNORE, channels.channel(1));
        assertEquals(1850, channels.channel(2));
        assertEquals(RcChannels.IGNORE, channels.channel(3));
        assertEquals(2000, channels.channel(4));
    }

    @Test
    void applyWithNoBindingsProducesASingleIgnoreChannel() {
        ChannelMap map = new ChannelMap(List.of());

        RcChannels channels = map.apply(List.of(), List.of());

        assertEquals(1, channels.microsByChannel().size());
        assertEquals(RcChannels.IGNORE, channels.channel(1));
    }
}
