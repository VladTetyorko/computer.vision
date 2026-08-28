package com.drones.mavlink.service;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * docs/plans/active/FLEET-RADIO-PLAN.md R3: {@link RcChannels#wireValue(int)} is the one place the
 * extension-channel sentinel asymmetry (F4) is resolved, and the range narrowing to {@code [1,16]}
 * (F17) is enforced at construction. {@link ManualControlServiceTest} covers the same facts through
 * a built {@code RC_CHANNELS_OVERRIDE} frame; these tests isolate the record's own logic.
 */
class RcChannelsTest {

    @Test
    void acceptsLengthOneAndLengthSixteen() {
        assertEquals(1, new RcChannels(List.of(1500)).microsByChannel().size());
        assertEquals(16, new RcChannels(Collections.nCopies(16, 1500)).microsByChannel().size());
    }

    @Test
    void rejectsSeventeenOrMoreChannelsBecauseArduPilotReadsNoHigherThanSixteen() {
        // F17: MAVLink #70 itself extends to 18, but ArduPilot's own RC override handling stops at 16.
        assertThrows(IllegalArgumentException.class, () -> new RcChannels(Collections.nCopies(17, 1500)));
        assertThrows(IllegalArgumentException.class, () -> new RcChannels(Collections.nCopies(18, 1500)));
    }

    @Test
    void allIgnoreAndReleasedRejectChannelCountsAboveSixteen() {
        assertThrows(IllegalArgumentException.class, () -> RcChannels.allIgnore(17));
        assertThrows(IllegalArgumentException.class, () -> RcChannels.released(17));
    }

    @Test
    void wireValuePassesThroughARealMicrosecondValueUnchangedOnEveryChannel() {
        RcChannels channels = new RcChannels(Collections.nCopies(16, 1500));

        for (int channel = 1; channel <= 16; channel++) {
            assertEquals(1500, channels.wireValue(channel), "channel " + channel);
        }
    }

    @Test
    void wireValuePassesThroughIgnoreUnchangedOnEveryChannel() {
        RcChannels channels = new RcChannels(Collections.nCopies(16, RcChannels.IGNORE));

        for (int channel = 1; channel <= 16; channel++) {
            assertEquals(RcChannels.IGNORE, channels.wireValue(channel), "channel " + channel);
        }
    }

    @Test
    void wireValueKeepsReleaseAsZeroOnChannelsOneThroughEight() {
        RcChannels channels = RcChannels.released(8);

        for (int channel = 1; channel <= 8; channel++) {
            assertEquals(0, channels.wireValue(channel), "channel " + channel);
        }
    }

    @Test
    void wireValueTranslatesReleaseToExtensionReleaseOnChannelsNineThroughSixteen() {
        // F4: 9..16 do not share 1..8's RELEASE=0 sentinel -- 0 means "ignore" there, and only
        // 65534 (UINT16_MAX-1) means "release". Getting this wrong reads a release as an ignore,
        // which leaves the channel latched at its last commanded value forever.
        RcChannels channels = RcChannels.released(16);

        for (int channel = 9; channel <= 16; channel++) {
            assertEquals(RcChannels.EXTENSION_RELEASE, channels.wireValue(channel), "channel " + channel);
            assertEquals(65534, channels.wireValue(channel), "channel " + channel);
        }
    }

    @Test
    void wireValuePadsBeyondTheFramesOwnLengthWithIgnoreNotRelease() {
        RcChannels channels = new RcChannels(List.of(1500, 1500));

        assertEquals(RcChannels.IGNORE, channels.wireValue(9));
        assertEquals(RcChannels.IGNORE, channels.wireValue(16));
    }

    @Test
    void wireValueRejectsAChannelBelowOne() {
        RcChannels channels = new RcChannels(List.of(1500));

        assertThrows(IllegalArgumentException.class, () -> channels.wireValue(0));
        assertThrows(IllegalArgumentException.class, () -> channels.wireValue(-1));
    }
}
