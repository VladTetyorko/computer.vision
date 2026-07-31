package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChannelMapTest {

    @Test
    void rejectsNullBindings() {
        assertThrows(IllegalArgumentException.class, () -> new ChannelMap(null));
    }

    @Test
    void defensivelyCopiesBindings() {
        List<ControlBinding> bindings = new ArrayList<>(List.of(
                new ControlBinding(ControlBinding.Source.AXIS, 0, 1, 1000, 1500, 2000, 0.0, false)));
        ChannelMap map = new ChannelMap(bindings);

        bindings.add(new ControlBinding(ControlBinding.Source.AXIS, 1, 2, 1000, 1500, 2000, 0.0, false));

        assertEquals(1, map.bindings().size());
        assertThrows(UnsupportedOperationException.class,
                () -> map.bindings().add(
                        new ControlBinding(ControlBinding.Source.AXIS, 1, 2, 1000, 1500, 2000, 0.0, false)));
    }

    // --- defaultMap() shape ---

    @Test
    void defaultMapHasEightBindings() {
        assertEquals(8, ChannelMap.defaultMap().bindings().size());
    }

    @Test
    void defaultMapAxesZeroToThreeDriveChannelsOneToFour() {
        List<ControlBinding> bindings = ChannelMap.defaultMap().bindings();

        for (int i = 0; i < 4; i++) {
            ControlBinding binding = bindings.get(i);
            assertEquals(ControlBinding.Source.AXIS, binding.source());
            assertEquals(i, binding.sourceIndex());
            assertEquals(i + 1, binding.rcChannel());
            assertEquals(1000, binding.minMicros());
            assertEquals(1500, binding.centerMicros());
            assertEquals(2000, binding.maxMicros());
            assertEquals(0.0, binding.deadband());
            assertEquals(false, binding.reversed());
        }
    }

    @Test
    void defaultMapButtonsZeroToThreeDriveChannelsFiveToEight() {
        List<ControlBinding> bindings = ChannelMap.defaultMap().bindings();

        for (int i = 0; i < 4; i++) {
            ControlBinding binding = bindings.get(4 + i);
            assertEquals(ControlBinding.Source.BUTTON, binding.source());
            assertEquals(i, binding.sourceIndex());
            assertEquals(5 + i, binding.rcChannel());
            assertEquals(1000, binding.minMicros());
            assertEquals(2000, binding.maxMicros());
            assertEquals(0.0, binding.deadband());
            assertEquals(false, binding.reversed());
        }
    }

    // --- apply() ---

    @Test
    void applyMapsAKnownAxesAndButtonsVectorToExpectedMicros() {
        ChannelMap map = ChannelMap.defaultMap();

        RcChannels channels = map.apply(List.of(0.0, -0.12, 1.0, 0.0), List.of(0.0, 1.0));

        assertEquals(Arrays.asList(1500, 1440, 2000, 1500, 1000, 2000, 1000, 1000), channels.microsByChannel());
    }

    @Test
    void applyWithEmptyAxesAndButtonsFallsBackToRestForEveryChannel() {
        ChannelMap map = ChannelMap.defaultMap();

        RcChannels channels = map.apply(List.of(), List.of());

        assertEquals(Arrays.asList(1500, 1500, 1500, 1500, 1000, 1000, 1000, 1000), channels.microsByChannel());
    }

    @Test
    void applyWithNullAxesAndButtonsFallsBackToRestForEveryChannel() {
        ChannelMap map = ChannelMap.defaultMap();

        RcChannels channels = map.apply(null, null);

        assertEquals(Arrays.asList(1500, 1500, 1500, 1500, 1000, 1000, 1000, 1000), channels.microsByChannel());
    }

    @Test
    void applyWithShortListsFallsBackToRestForMissingIndicesOnly() {
        ChannelMap map = ChannelMap.defaultMap();

        // Only axis 0 and button 0 are present; axes 1..3 and buttons 1..3 read as rest (0.0).
        RcChannels channels = map.apply(List.of(1.0), List.of(1.0));

        assertEquals(Arrays.asList(2000, 1500, 1500, 1500, 2000, 1000, 1000, 1000), channels.microsByChannel());
    }

    @Test
    void applyLeavesUnboundChannelsAsIgnore() {
        ChannelMap map = new ChannelMap(List.of(
                new ControlBinding(ControlBinding.Source.AXIS, 0, 2, 1000, 1500, 2000, 0.0, false),
                new ControlBinding(ControlBinding.Source.BUTTON, 0, 4, 1000, 1000, 2000, 0.0, false)));

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
