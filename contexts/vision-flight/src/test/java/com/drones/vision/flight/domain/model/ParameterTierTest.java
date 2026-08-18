package com.drones.vision.flight.domain.model;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParameterTierTest {

    @Test
    void classifiesTierAReportingParameters() {
        assertEquals(Optional.of(ParameterTier.A), ParameterTier.classify("SR2_EXTRA2"));
        assertEquals(Optional.of(ParameterTier.A), ParameterTier.classify("SYSID_THISMAV"));
        assertEquals(Optional.of(ParameterTier.A), ParameterTier.classify("SERIAL1_PROTOCOL"));
    }

    @Test
    void classifiesTierBLinkAndFailsafeParameters() {
        assertEquals(Optional.of(ParameterTier.B), ParameterTier.classify("FS_GCS_ENABLE"));
        assertEquals(Optional.of(ParameterTier.B), ParameterTier.classify("RC_FS_TIMEOUT"));
    }

    /** Required behavior: a Tier-C parameter name is rejected by the allowlist. */
    @Test
    void classifiesTierCFlightCriticalParametersAndTheyAreNeverWritable() {
        assertEquals(Optional.of(ParameterTier.C), ParameterTier.classify("ARMING_CHECK"));
        assertEquals(Optional.of(ParameterTier.C), ParameterTier.classify("FRAME_CLASS"));
        assertFalse(ParameterTier.C.everWritable());
    }

    @Test
    void anUnrecognizedNameClassifiesToNothingAndIsTreatedAsUnwritable() {
        assertEquals(Optional.empty(), ParameterTier.classify("NOT_A_REAL_PARAMETER"));
    }

    @Test
    void tierAAndTierBAreEverWritableTierCIsNot() {
        assertTrue(ParameterTier.A.everWritable());
        assertTrue(ParameterTier.B.everWritable());
        assertFalse(ParameterTier.C.everWritable());
    }

    @Test
    void classifyRejectsBlankNames() {
        assertThrows(IllegalArgumentException.class, () -> ParameterTier.classify(""));
        assertThrows(IllegalArgumentException.class, () -> ParameterTier.classify(null));
    }
}
