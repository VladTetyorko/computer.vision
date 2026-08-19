package com.drones.vision.flight.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class FeatureRequirementTest {

    @Test
    void rejectsAFeatureKeyNotInTheFrozenV1List() {
        assertThrows(IllegalArgumentException.class,
                () -> new FeatureRequirement("not-a-real-feature", "Label", "ardupilot", null, null, null, null));
    }

    @Test
    void aMessageRequirementMustCarryANameAndAMinimumHz() {
        assertThrows(IllegalArgumentException.class,
                () -> new FeatureRequirement("ground-speed", "Ground speed", "ardupilot", 74, null, 2.0, null));
        assertThrows(IllegalArgumentException.class,
                () -> new FeatureRequirement("ground-speed", "Ground speed", "ardupilot", 74, "VFR_HUD", null, null));
    }

    @Test
    void aRowWithNeitherMessageNorParameterIsValid() {
        // Declares "this firmware supports the feature" without a concrete telemetry check --
        // see this type's own javadoc.
        new FeatureRequirement("command-tx", "Command TX", "ardupilot", null, null, null, null);
    }

    @Test
    void blankFirmwareIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new FeatureRequirement("battery", "Battery", " ", null, null, null, null));
    }
}
