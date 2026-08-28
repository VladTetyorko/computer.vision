package com.drones.vision.flight.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class FeatureRequirementTest {

    @Test
    void rejectsAFeatureKeyNotInTheFrozenV1List() {
        assertThrows(IllegalArgumentException.class,
                () -> new FeatureRequirement("not-a-real-feature", "Label", "ardupilot", null, null, null, null,
                        null, null));
    }

    @Test
    void aMessageRequirementMustCarryANameAndAMinimumHz() {
        assertThrows(IllegalArgumentException.class,
                () -> new FeatureRequirement("ground-speed", "Ground speed", "ardupilot", 74, null, 2.0, null, null,
                        null));
        assertThrows(IllegalArgumentException.class,
                () -> new FeatureRequirement("ground-speed", "Ground speed", "ardupilot", 74, "VFR_HUD", null, null,
                        null, null));
    }

    @Test
    void aRowWithNeitherMessageNorParameterIsValid() {
        // Declares "this firmware supports the feature" without a concrete telemetry check --
        // see this type's own javadoc.
        new FeatureRequirement("command-tx", "Command TX", "ardupilot", null, null, null, null, null, null);
    }

    @Test
    void blankFirmwareIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new FeatureRequirement("battery", "Battery", " ", null, null, null, null, null, null));
    }

    /**
     * FLEET-RADIO-PLAN R6: a required value or a forbidden-bits mask is only meaningful next to a
     * parameter name -- without one, there is nothing for either to qualify.
     */
    @Test
    void requiredParameterValueMustNotBeSetWithoutAParameterName() {
        assertThrows(IllegalArgumentException.class,
                () -> new FeatureRequirement("rc-relay", "RC relay", "ardupilot", null, null, null, null, 255.0,
                        null));
    }

    @Test
    void forbiddenParameterBitsMustNotBeSetWithoutAParameterName() {
        assertThrows(IllegalArgumentException.class,
                () -> new FeatureRequirement("rc-relay", "RC relay", "ardupilot", null, null, null, null, null, 2L));
    }

    @Test
    void forbiddenParameterBitsMustNotBeNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> new FeatureRequirement("rc-relay", "RC relay", "ardupilot", null, null, null, "RC_OPTIONS",
                        null, -1L));
    }

    @Test
    void aParameterRowMayCarryAValueCheckABitsCheckOrBoth() {
        new FeatureRequirement("rc-relay", "RC relay (GCS sysid)", "ardupilot", null, null, null, "SYSID_MYGCS",
                255.0, null);
        new FeatureRequirement("rc-relay", "RC relay (RC_OPTIONS)", "ardupilot", null, null, null, "RC_OPTIONS",
                null, 2L);
        new FeatureRequirement("rc-relay", "RC relay (both)", "ardupilot", null, null, null, "RC_OPTIONS", 0.0, 2L);
    }
}
