package com.drones.vision.flight.domain.model;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** docs/plans/active/FLEET-RADIO-PLAN.md F0 — a firmware rename must not read as a missing parameter. */
class ParameterAliasesTest {

    /** Required behavior: the rename that broke readiness. */
    @Test
    void bothSpellingsOfTheVehicleSystemIdAreOneParameter() {
        assertTrue(ParameterAliases.sameParameter("SYSID_THISMAV", "MAV_SYSID"));
        assertTrue(ParameterAliases.sameParameter("MAV_SYSID", "SYSID_THISMAV"));
    }

    @Test
    void bothSpellingsOfTheGcsSystemIdAreOneParameter() {
        assertTrue(ParameterAliases.sameParameter("SYSID_MYGCS", "MAV_GCS_SYSID"));
    }

    @Test
    void unrelatedNamesStayDistinct() {
        assertFalse(ParameterAliases.sameParameter("MAV_SYSID", "FRAME_CLASS"));
        assertFalse(ParameterAliases.sameParameter("MAV_SYSID", "MAV_GCS_SYSID"));
    }

    @Test
    void aNameInNoAliasGroupIsItsOwnCanonicalForm() {
        assertEquals("FRAME_CLASS", ParameterAliases.canonical("FRAME_CLASS"));
        assertEquals(Set.of("FRAME_CLASS"), ParameterAliases.spellingsOf("FRAME_CLASS"));
        assertTrue(ParameterAliases.sameParameter("FRAME_CLASS", "FRAME_CLASS"));
    }

    @Test
    void canonicalFormIsTheSameWhicheverSpellingIsAskedAbout() {
        assertEquals(ParameterAliases.canonical("SYSID_THISMAV"), ParameterAliases.canonical("MAV_SYSID"));
    }

    /** A probe must ask for every spelling: MAVLink has no "no such parameter" reply. */
    @Test
    void spellingsCoverEveryFirmwareGeneration() {
        assertEquals(Set.of("SYSID_THISMAV", "MAV_SYSID"), ParameterAliases.spellingsOf("MAV_SYSID"));
        assertEquals(Set.of("SYSID_THISMAV", "MAV_SYSID"), ParameterAliases.spellingsOf("SYSID_THISMAV"));
    }

    @Test
    void nullIsNeverTheSameParameterAsAnythingIncludingNull() {
        assertFalse(ParameterAliases.sameParameter(null, "MAV_SYSID"));
        assertFalse(ParameterAliases.sameParameter("MAV_SYSID", null));
        assertFalse(ParameterAliases.sameParameter(null, null));
    }
}
