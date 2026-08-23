package com.drones.vision.adapter.mavlink;

import com.drones.vision.flight.domain.model.VehicleKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlightModesTest {

    private static final int AUTOPILOT_ARDUPILOTMEGA = 3;
    private static final int AUTOPILOT_GENERIC = 0;
    private static final int AUTOPILOT_PX4 = 12;

    private static final int MAV_TYPE_QUADROTOR = 2;
    private static final int MAV_TYPE_FIXED_WING = 1;
    private static final int MAV_TYPE_VTOL_TAILSITTER = 23;
    private static final int MAV_TYPE_GROUND_ROVER = 10;
    private static final int MAV_TYPE_HELICOPTER = 4;
    private static final int MAV_TYPE_GCS = 6; // not copter/plane/rover

    @Test
    void resolvesArdupilotCopterModesByName() {
        assertEquals("Stabilize", FlightModes.name(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_QUADROTOR, 0));
        assertEquals("RTL", FlightModes.name(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_QUADROTOR, 6));
        assertEquals("Land", FlightModes.name(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_QUADROTOR, 9));
        assertEquals("SmartRTL", FlightModes.name(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_QUADROTOR, 21));
        assertEquals("AutoRTL", FlightModes.name(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_QUADROTOR, 27));
        assertEquals("Turtle", FlightModes.name(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_QUADROTOR, 28));
    }

    @Test
    void resolvesArdupilotCopterModesForEveryCopterMavType() {
        assertEquals("Loiter", FlightModes.name(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_HELICOPTER, 5));
        assertEquals("Loiter", FlightModes.name(AUTOPILOT_ARDUPILOTMEGA, 13 /* hexarotor */, 5));
        assertEquals("Loiter", FlightModes.name(AUTOPILOT_ARDUPILOTMEGA, 14 /* octorotor */, 5));
        assertEquals("Loiter", FlightModes.name(AUTOPILOT_ARDUPILOTMEGA, 15 /* tricopter */, 5));
        assertEquals("Loiter", FlightModes.name(AUTOPILOT_ARDUPILOTMEGA, 3 /* coaxial */, 5));
    }

    @Test
    void resolvesArdupilotPlaneModesByName() {
        assertEquals("Manual", FlightModes.name(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_FIXED_WING, 0));
        assertEquals("RTL", FlightModes.name(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_FIXED_WING, 11));
        assertEquals("QLand", FlightModes.name(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_FIXED_WING, 20));
        assertEquals("QRTL", FlightModes.name(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_FIXED_WING, 21));
        assertEquals("AutoLand", FlightModes.name(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_FIXED_WING, 26));
    }

    @Test
    void resolvesArdupilotPlaneModesForAVtolMavType() {
        assertEquals("QHover", FlightModes.name(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_VTOL_TAILSITTER, 18));
    }

    @Test
    void resolvesArdupilotRoverModesByName() {
        assertEquals("Manual", FlightModes.name(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_GROUND_ROVER, 0));
        assertEquals("Hold", FlightModes.name(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_GROUND_ROVER, 4));
        assertEquals("RTL", FlightModes.name(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_GROUND_ROVER, 11));
        assertEquals("SmartRTL", FlightModes.name(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_GROUND_ROVER, 12));
        assertEquals("Guided", FlightModes.name(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_GROUND_ROVER, 15));
    }

    @Test
    void resolvesBetaflightModesRegardlessOfMavType() {
        assertEquals("Acro", FlightModes.name(AUTOPILOT_GENERIC, MAV_TYPE_QUADROTOR, 0));
        assertEquals("Angle", FlightModes.name(AUTOPILOT_GENERIC, MAV_TYPE_QUADROTOR, 1));
        assertEquals("RTL", FlightModes.name(AUTOPILOT_GENERIC, MAV_TYPE_QUADROTOR, 6));
        assertEquals("Failsafe", FlightModes.name(AUTOPILOT_GENERIC, MAV_TYPE_QUADROTOR, 7));
    }

    @Test
    void fallsBackToModeNForPx4() {
        assertEquals("Mode 4", FlightModes.name(AUTOPILOT_PX4, MAV_TYPE_QUADROTOR, 4));
    }

    @Test
    void fallsBackToModeNForAnUnrecognizedAutopilot() {
        assertEquals("Mode 2", FlightModes.name(99, MAV_TYPE_QUADROTOR, 2));
    }

    @Test
    void fallsBackToModeNForAnArdupilotMavTypeOutsideEveryKnownFamily() {
        assertEquals("Mode 0", FlightModes.name(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_GCS, 0));
    }

    @Test
    void fallsBackToModeNForACustomModeMissingFromTheSelectedTable() {
        assertEquals("Mode 8", FlightModes.name(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_QUADROTOR, 8));
        assertEquals("Mode 9", FlightModes.name(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_FIXED_WING, 9));
        assertEquals("Mode 2", FlightModes.name(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_GROUND_ROVER, 2));
        assertEquals("Mode 8", FlightModes.name(AUTOPILOT_GENERIC, MAV_TYPE_QUADROTOR, 8));
    }

    @Test
    void resolvesTheRtlCustomModeForEveryArdupilotVehicleFamily() {
        assertEquals(6, FlightModes.customModeFor(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_QUADROTOR, "RTL"));
        assertEquals(11, FlightModes.customModeFor(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_FIXED_WING, "RTL"));
        assertEquals(11, FlightModes.customModeFor(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_GROUND_ROVER, "RTL"));
    }

    @Test
    void resolvesTheBetaflightRtlCustomModeEvenThoughBetaflightIsNotCommandable() {
        // FlightModes is a pure name<->number lookup with no opinion on which firmwares this
        // platform is willing to command -- MavlinkFlightCommander rejects Betaflight itself
        // before ever consulting this table. See customModeFor's own javadoc.
        assertEquals(6, FlightModes.customModeFor(AUTOPILOT_GENERIC, MAV_TYPE_QUADROTOR, "RTL"));
    }

    @Test
    void customModeForReturnsNullForAnUnknownModeName() {
        assertNull(FlightModes.customModeFor(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_QUADROTOR, "NotAMode"));
    }

    @Test
    void customModeForReturnsNullWhenNoTableIsSelected() {
        assertNull(FlightModes.customModeFor(AUTOPILOT_PX4, MAV_TYPE_QUADROTOR, "RTL"));
        assertNull(FlightModes.customModeFor(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_GCS, "RTL"));
    }

    @Test
    void selectableModesListsEveryArdupilotCopterModeSortedAndDistinct() {
        List<String> modes = FlightModes.selectableModes(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_QUADROTOR);

        assertEquals(modes.stream().sorted().distinct().toList(), modes, "expected sorted, distinct");
        assertTrue(modes.contains("Stabilize") && modes.contains("RTL") && modes.contains("Loiter")
                && modes.contains("Land"), "expected the copter family modes, got: " + modes);
        // ArduPilot copter table has 26 distinct named entries.
        assertEquals(26, modes.size(), "expected every copter mode, got: " + modes);
    }

    @Test
    void selectableModesSelectsThePlaneAndRoverFamiliesByMavType() {
        List<String> plane = FlightModes.selectableModes(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_FIXED_WING);
        assertTrue(plane.contains("FBWA") && plane.contains("QLoiter") && plane.contains("RTL"),
                "expected plane-family modes, got: " + plane);

        List<String> rover = FlightModes.selectableModes(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_GROUND_ROVER);
        assertTrue(rover.contains("Steering") && rover.contains("Hold") && rover.contains("RTL"),
                "expected rover-family modes, got: " + rover);
        assertEquals(rover.stream().sorted().toList(), rover, "expected sorted");
    }

    @Test
    void selectableModesIsEmptyForBetaflightEvenThoughItHasAModeTable() {
        assertEquals(List.of(), FlightModes.selectableModes(AUTOPILOT_GENERIC, MAV_TYPE_QUADROTOR));
    }

    @Test
    void selectableModesIsEmptyForPx4UnknownAutopilotsAndAnUnknownArdupilotFamily() {
        assertEquals(List.of(), FlightModes.selectableModes(AUTOPILOT_PX4, MAV_TYPE_QUADROTOR));
        assertEquals(List.of(), FlightModes.selectableModes(99, MAV_TYPE_QUADROTOR));
        assertEquals(List.of(), FlightModes.selectableModes(AUTOPILOT_ARDUPILOTMEGA, MAV_TYPE_GCS));
    }

    // --- vehicleKind (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §1.4) --------------

    @Test
    void vehicleKindSortsEveryFamilyThisClassAlreadyKnows() {
        assertEquals(VehicleKind.COPTER, FlightModes.vehicleKind(2));   // MAV_TYPE_QUADROTOR
        assertEquals(VehicleKind.COPTER, FlightModes.vehicleKind(4));   // MAV_TYPE_HELICOPTER
        assertEquals(VehicleKind.COPTER, FlightModes.vehicleKind(13));  // MAV_TYPE_HEXAROTOR
        assertEquals(VehicleKind.PLANE, FlightModes.vehicleKind(1));    // MAV_TYPE_FIXED_WING
        assertEquals(VehicleKind.PLANE, FlightModes.vehicleKind(21));   // MAV_TYPE_VTOL_TILTROTOR
        assertEquals(VehicleKind.ROVER, FlightModes.vehicleKind(10));   // MAV_TYPE_GROUND_ROVER
        assertEquals(VehicleKind.ROVER, FlightModes.vehicleKind(11));   // MAV_TYPE_SURFACE_BOAT
    }

    @Test
    void anUnrecognizedMavTypeIsUnknownNotAGuess() {
        assertEquals(VehicleKind.UNKNOWN, FlightModes.vehicleKind(6));   // MAV_TYPE_GCS
        assertEquals(VehicleKind.UNKNOWN, FlightModes.vehicleKind(0));   // MAV_TYPE_GENERIC
        assertEquals(VehicleKind.UNKNOWN, FlightModes.vehicleKind(255));
    }

    @Test
    void vehicleKindIsTheSameWhicheverFirmwareIsFlyingIt() {
        // Unlike the mode tables, the classification never consults `autopilot`: a quadrotor is a
        // quadrotor whether ArduPilot, PX4 or Betaflight is flying it. Asserted by construction --
        // the method takes no autopilot argument at all -- and pinned here so a later "helpful"
        // overload that adds one has to justify itself.
        assertEquals(VehicleKind.COPTER, FlightModes.vehicleKind(2));
    }
}
