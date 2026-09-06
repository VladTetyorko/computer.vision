package com.drones.vision.perception.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DetectionPolicyTest {

    @Test
    void nullAttributeValueParsesToOnViewTheFailClosedDefault() {
        assertEquals(DetectionPolicy.ON_VIEW, DetectionPolicy.fromAttributeValue(null));
    }

    @Test
    void blankAndGarbageAttributeValuesAlsoParseToOnView() {
        assertEquals(DetectionPolicy.ON_VIEW, DetectionPolicy.fromAttributeValue(""));
        assertEquals(DetectionPolicy.ON_VIEW, DetectionPolicy.fromAttributeValue("   "));
        assertEquals(DetectionPolicy.ON_VIEW, DetectionPolicy.fromAttributeValue("not-a-real-policy"));
        assertEquals(DetectionPolicy.ON_VIEW, DetectionPolicy.fromAttributeValue("ALWAYS_ON"),
                "must match the exact frozen vocabulary, not merely contain it");
    }

    @Test
    void exactValueAlwaysCaseInsensitiveAndTrimmedParsesToAlways() {
        assertEquals(DetectionPolicy.ALWAYS, DetectionPolicy.fromAttributeValue("always"));
        assertEquals(DetectionPolicy.ALWAYS, DetectionPolicy.fromAttributeValue("ALWAYS"));
        assertEquals(DetectionPolicy.ALWAYS, DetectionPolicy.fromAttributeValue("  Always  "));
    }

    @Test
    void explicitOnViewValueParsesToOnView() {
        assertEquals(DetectionPolicy.ON_VIEW, DetectionPolicy.fromAttributeValue("on-view"));
    }

    @Test
    void toAttributeValueRoundTripsThroughFromAttributeValueForEveryConstant() {
        for (DetectionPolicy policy : DetectionPolicy.values()) {
            assertEquals(policy, DetectionPolicy.fromAttributeValue(policy.toAttributeValue()),
                    "the exact vocabulary a policy serializes to must parse straight back to itself");
        }
    }
}
