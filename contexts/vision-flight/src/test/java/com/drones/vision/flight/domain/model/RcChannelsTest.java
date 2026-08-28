package com.drones.vision.flight.domain.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RcChannelsTest {

    @Test
    void acceptsValuesAtBothMicrosBoundsAndBothSentinels() {
        RcChannels channels = new RcChannels(
                List.of(RcChannels.MIN_MICROS, RcChannels.MAX_MICROS, RcChannels.RELEASE, RcChannels.IGNORE));

        assertEquals(1000, channels.channel(1));
        assertEquals(2000, channels.channel(2));
        assertEquals(0, channels.channel(3));
        assertEquals(65535, channels.channel(4));
    }

    @Test
    void acceptsLengthOneAndLengthSixteen() {
        assertEquals(1, new RcChannels(List.of(1500)).microsByChannel().size());
        assertEquals(16, new RcChannels(java.util.Collections.nCopies(16, 1500)).microsByChannel().size());
    }

    @Test
    void rejectsNullList() {
        assertThrows(IllegalArgumentException.class, () -> new RcChannels(null));
    }

    @Test
    void rejectsEmptyList() {
        assertThrows(IllegalArgumentException.class, () -> new RcChannels(List.of()));
    }

    @Test
    void rejectsListLongerThanSixteen() {
        assertThrows(IllegalArgumentException.class,
                () -> new RcChannels(java.util.Collections.nCopies(17, 1500)));
    }

    @Test
    void rejectsNullEntry() {
        List<Integer> values = new ArrayList<>();
        values.add(1500);
        values.add(null);

        assertThrows(IllegalArgumentException.class, () -> new RcChannels(values));
    }

    @Test
    void rejectsValuesOutsideMicrosRangeThatAreNotSentinels() {
        assertThrows(IllegalArgumentException.class, () -> new RcChannels(List.of(999)));
        assertThrows(IllegalArgumentException.class, () -> new RcChannels(List.of(2001)));
        assertThrows(IllegalArgumentException.class, () -> new RcChannels(List.of(-1)));
        assertThrows(IllegalArgumentException.class, () -> new RcChannels(List.of(1)));
    }

    @Test
    void defensivelyCopiesTheList() {
        List<Integer> values = new ArrayList<>(List.of(1500, 1600));
        RcChannels channels = new RcChannels(values);

        values.set(0, 1000);

        assertEquals(1500, channels.channel(1));
        assertThrows(UnsupportedOperationException.class, () -> channels.microsByChannel().add(1700));
    }

    @Test
    void channelIsOneBased() {
        RcChannels channels = new RcChannels(List.of(1100, 1200, 1300));

        assertEquals(1100, channels.channel(1));
        assertEquals(1200, channels.channel(2));
        assertEquals(1300, channels.channel(3));
    }

    @Test
    void channelRejectsOutOfRangeIndices() {
        RcChannels channels = new RcChannels(List.of(1100, 1200, 1300));

        assertThrows(IllegalArgumentException.class, () -> channels.channel(0));
        assertThrows(IllegalArgumentException.class, () -> channels.channel(4));
        assertThrows(IllegalArgumentException.class, () -> channels.channel(-1));
    }

    @Test
    void releasedBuildsAllReleaseValuesForChannelCount() {
        RcChannels channels = RcChannels.released(8);

        assertEquals(8, channels.microsByChannel().size());
        assertEquals(Arrays.asList(0, 0, 0, 0, 0, 0, 0, 0), channels.microsByChannel());
        assertTrue(channels.microsByChannel().stream().allMatch(v -> v == RcChannels.RELEASE));
    }

    @Test
    void releasedRejectsOutOfRangeChannelCount() {
        assertThrows(IllegalArgumentException.class, () -> RcChannels.released(0));
        assertThrows(IllegalArgumentException.class, () -> RcChannels.released(17));
    }
}
