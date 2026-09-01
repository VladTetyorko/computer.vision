package com.drones.vision.adapter.mavlink;

import com.drones.vision.flight.domain.model.ParameterReading;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two-pass parameter read (docs/plans/active/FLEET-RADIO-PLAN.md F0). What matters is that the
 * second pass is normally empty: it exists so a renamed parameter can still be found on older
 * firmware, without every probe of every vehicle paying a guaranteed timeout for the spelling its
 * firmware does not have.
 */
class MavlinkVehicleConfiguratorAliasTest {

    private static final List<String> PROBED = List.of("MAV_SYSID", "FRAME_CLASS");

    private static ParameterReading read(String name) {
        return new ParameterReading(name, 7.0, "INT32");
    }

    /** The fast path, and the common one: current firmware answered everything asked. */
    @Test
    void nothingIsReAskedWhenTheFirstPassAnsweredEveryName() {
        List<ParameterReading> firstPass = List.of(read("MAV_SYSID"), read("FRAME_CLASS"));

        assertTrue(MavlinkVehicleConfigurator.unansweredSpellings(PROBED, firstPass).isEmpty());
    }

    /** The path this exists for: pre-4.7 firmware ignores MAV_SYSID, so ask the old spelling. */
    @Test
    void anUnansweredRenamedParameterIsReAskedUnderItsOtherSpelling() {
        List<ParameterReading> firstPass = List.of(read("FRAME_CLASS"));

        assertEquals(List.of("SYSID_THISMAV"),
                MavlinkVehicleConfigurator.unansweredSpellings(PROBED, firstPass));
    }

    /** A name with no other spelling has no fallback to offer -- re-asking it would just burn a timeout. */
    @Test
    void anUnansweredNameWithNoAliasIsNotReAsked() {
        List<ParameterReading> firstPass = List.of(read("MAV_SYSID"));

        assertTrue(MavlinkVehicleConfigurator.unansweredSpellings(PROBED, firstPass).isEmpty());
    }

    /** An answer under any spelling settles the parameter; the requested spelling is not re-asked. */
    @Test
    void anAnswerUnderTheOtherSpellingCountsAsAnswered() {
        List<ParameterReading> firstPass = List.of(read("SYSID_THISMAV"), read("FRAME_CLASS"));

        assertTrue(MavlinkVehicleConfigurator.unansweredSpellings(PROBED, firstPass).isEmpty());
    }

    @Test
    void aNameIsNeverOfferedAsItsOwnFallback() {
        assertTrue(MavlinkVehicleConfigurator.unansweredSpellings(List.of("MAV_SYSID"), List.of())
                .stream().noneMatch("MAV_SYSID"::equals));
    }

    /** A name the first pass already asked is never re-asked, however it got into the list. */
    @Test
    void aSpellingAlreadyInTheProbeListIsNotOfferedAsAFallbackForAnother() {
        List<String> fallbacks = MavlinkVehicleConfigurator.unansweredSpellings(
                List.of("MAV_SYSID", "SYSID_THISMAV"), List.of());

        assertTrue(fallbacks.isEmpty(),
                "both spellings were asked in pass one; re-asking them would double the timeout: " + fallbacks);
    }
}
