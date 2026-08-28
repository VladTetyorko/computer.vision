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

    /**
     * FLEET-RADIO-PLAN F0: ArduPilot 4.7 renamed both system-id parameters. An unclassified name is
     * refused outright, so a rename must not silently take the remedy away.
     */
    @Test
    void renamedSystemIdParametersStayTierAUnderEitherSpelling() {
        assertEquals(Optional.of(ParameterTier.A), ParameterTier.classify("MAV_SYSID"));
        assertEquals(Optional.of(ParameterTier.A), ParameterTier.classify("SYSID_THISMAV"));
        assertEquals(Optional.of(ParameterTier.A), ParameterTier.classify("MAV_GCS_SYSID"));
        assertEquals(Optional.of(ParameterTier.A), ParameterTier.classify("SYSID_MYGCS"));
    }

    @Test
    void classifiesTierBLinkAndFailsafeParameters() {
        assertEquals(Optional.of(ParameterTier.B), ParameterTier.classify("FS_GCS_ENABLE"));
        assertEquals(Optional.of(ParameterTier.B), ParameterTier.classify("RC_FS_TIMEOUT"));
    }

    /**
     * FLEET-RADIO-PLAN R6: RC_OPTIONS bit 1 (IGNORE_OVERRIDES) silently discards every MAVLink RC
     * override this platform sends -- the readiness remedy for that row is only honest if the
     * parameter is actually writable through the existing R5 endpoint.
     */
    @Test
    void classifiesRcOptionsAsTierB() {
        assertEquals(Optional.of(ParameterTier.B), ParameterTier.classify("RC_OPTIONS"));
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
