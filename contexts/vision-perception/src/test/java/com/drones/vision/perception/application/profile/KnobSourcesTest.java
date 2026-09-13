package com.drones.vision.perception.application.profile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link KnobSources}: per-knob provenance, and its {@link ProfileSource#PLATFORM} floor. */
class KnobSourcesTest {

    @Test
    void platformFactoryReturnsPlatformForEveryKnob() {
        KnobSources sources = KnobSources.platform();

        assertEquals(ProfileSource.PLATFORM, sources.model());
        assertEquals(ProfileSource.PLATFORM, sources.confidenceThreshold());
        assertEquals(ProfileSource.PLATFORM, sources.inferenceFps());
        assertEquals(ProfileSource.PLATFORM, sources.labelFilter());
        assertEquals(ProfileSource.PLATFORM, sources.labelDenyFilter());
        assertEquals(ProfileSource.PLATFORM, sources.detectionEnabled());
        assertEquals(ProfileSource.PLATFORM, sources.tracking());
        assertEquals(ProfileSource.PLATFORM, sources.eventRule());
    }

    @Test
    void rejectsAnyNullField() {
        assertThrows(NullPointerException.class, () -> new KnobSources(null, ProfileSource.PLATFORM,
                ProfileSource.PLATFORM, ProfileSource.PLATFORM, ProfileSource.PLATFORM, ProfileSource.PLATFORM,
                ProfileSource.PLATFORM, ProfileSource.PLATFORM));
    }

    @Test
    void carriesAMixOfSourcesOnePerKnob() {
        KnobSources sources = new KnobSources(ProfileSource.ASSET, ProfileSource.ORGANIZATION, ProfileSource.INTENT,
                ProfileSource.CATEGORY, ProfileSource.PLATFORM, ProfileSource.PLATFORM, ProfileSource.PLATFORM,
                ProfileSource.PLATFORM);

        assertEquals(ProfileSource.ASSET, sources.model());
        assertEquals(ProfileSource.ORGANIZATION, sources.confidenceThreshold());
        assertEquals(ProfileSource.INTENT, sources.inferenceFps());
        assertEquals(ProfileSource.CATEGORY, sources.labelFilter());
    }
}
