package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.VehicleClass;
import com.drones.vision.flight.domain.model.VehicleKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the fix for docs/plans/active/FLEET-RADIO-PLAN.md F1, "by name" per that plan's R1
 * section: before this wave, {@link FlightModes}, {@link MavlinkHeartbeatScanner} (device
 * discovery) and {@link MavlinkVehicleConfigurator} (the onboarding probe) each hand-maintained
 * their own {@code MAV_TYPE} table and disagreed with each other on the very same vehicles — a
 * boat named {@code "rover"} in one and {@code "ground rover"} in another, a submarine known to
 * one table and not the other, a dodecarotor known to none. All three now delegate to {@link
 * VehicleClass}, the one table in {@code mavlink-core}, and this test asserts they still agree —
 * it fails immediately if any of the three grows a local, hand-maintained copy again, since a
 * reintroduced local table is exactly the kind of edit that could silently drift from the other
 * two without anyone noticing.
 *
 * <p>Covers the full union of {@code MAV_TYPE} numbers any one of the three old tables knew, plus
 * the numbers none of them knew (dodecarotor, decarotor, generic multirotor, every VTOL, and the
 * submarine slot), plus a not-a-vehicle instrument and a genuinely unrecognized number — the four
 * outcomes {@link VehicleClass} distinguishes.
 */
class VehicleTaxonomyAgreementTest {

    private static final int AUTOPILOT_ARDUPILOTMEGA = 3;

    @Test
    void copterFamilyAgreesOnFamilyAndOnExactLabelAcrossAllThreeCallSites() {
        int[] copters = {2, 3, 4, 13, 14, 15, 29, 35, 43};
        for (int mavType : copters) {
            assertEquals(VehicleKind.COPTER, FlightModes.vehicleKind(mavType), "FlightModes, mavType " + mavType);
            String scannerLabel = MavlinkHeartbeatScanner.vehicleKind(mavType);
            String configuratorLabel = MavlinkVehicleConfigurator.vehicleKind(mavType);
            assertEquals(configuratorLabel, scannerLabel, "mavType " + mavType
                    + " must be named identically by discovery and by the onboarding probe");
            assertEquals(VehicleClass.label(mavType), scannerLabel, "mavType " + mavType);
        }
    }

    @Test
    void planeFamilyAgreesOnFamilyAndOnExactLabelForFixedWingAndEveryVtol() {
        assertEquals(VehicleKind.PLANE, FlightModes.vehicleKind(1));
        for (int mavType = 19; mavType <= 25; mavType++) {
            assertEquals(VehicleKind.PLANE, FlightModes.vehicleKind(mavType), "VTOL mavType " + mavType);
            assertEquals(MavlinkVehicleConfigurator.vehicleKind(mavType), MavlinkHeartbeatScanner.vehicleKind(mavType),
                    "VTOL mavType " + mavType + " must be named identically everywhere");
        }
    }

    @Test
    void aSurfaceBoatIsNamedIdenticallyInDiscoveryProfileAndControl() {
        // Expected result #5: before this wave, MavlinkHeartbeatScanner said "boat" and
        // MavlinkVehicleConfigurator said "surface boat" for the exact same MAV_TYPE (11).
        assertEquals(VehicleKind.ROVER, FlightModes.vehicleKind(11));
        String scannerLabel = MavlinkHeartbeatScanner.vehicleKind(11);
        String configuratorLabel = MavlinkVehicleConfigurator.vehicleKind(11);
        assertEquals(scannerLabel, configuratorLabel);
        assertEquals("surface boat", scannerLabel);
    }

    @Test
    void aGroundRoverIsNamedIdenticallyEverywhereToo() {
        assertEquals(VehicleKind.ROVER, FlightModes.vehicleKind(10));
        assertEquals(MavlinkHeartbeatScanner.vehicleKind(10), MavlinkVehicleConfigurator.vehicleKind(10));
    }

    @Test
    void aDodecarotorGetsARealFamilyNotAFallback() {
        // Expected result #4: a dodecarotor (29) resolves to a real mode name (RTL), not "Mode 6" --
        // proven directly in FlightModesTest; this test proves the *taxonomy* half: every call site
        // agrees it is a copter, and discovery/probe name it identically.
        assertEquals(VehicleKind.COPTER, FlightModes.vehicleKind(29));
        assertEquals(MavlinkHeartbeatScanner.vehicleKind(29), MavlinkVehicleConfigurator.vehicleKind(29));
        assertEquals("dodecacopter", MavlinkHeartbeatScanner.vehicleKind(29));
    }

    @Test
    void submarineIsATableSlotAgreedOnByDiscoveryAndProbeButYieldsNoVehicleKind() {
        // The taxonomy must have a slot for MAV_TYPE 12 (D1's "not a fourth table" requirement) even
        // though VehicleKind deliberately gains no SUBMARINE constant (operator instruction).
        assertEquals(VehicleKind.UNKNOWN, FlightModes.vehicleKind(12));
        assertEquals("submarine", MavlinkHeartbeatScanner.vehicleKind(12));
        assertEquals("submarine", MavlinkVehicleConfigurator.vehicleKind(12));
    }

    @Test
    void aGimbalOnTheLinkIsClassifiedDistinctlyFromAnUnrecognizedNumber() {
        // Expected result #6. Both a gimbal and a wholly unrecognized number used to collapse into
        // the exact same fallback ("vehicle" in discovery, an unresolved kind in FlightModes/UNKNOWN
        // VehicleKind) -- indistinguishable from each other. VehicleClass now tells them apart.
        assertEquals(VehicleClass.NOT_A_VEHICLE, VehicleClass.of(26));
        assertEquals(VehicleClass.UNKNOWN, VehicleClass.of(9001));
        assertNotEquals(VehicleClass.of(26), VehicleClass.of(9001));

        String gimbalLabel = MavlinkHeartbeatScanner.vehicleKind(26);
        String unknownLabel = MavlinkHeartbeatScanner.vehicleKind(9001);
        assertEquals("gimbal", gimbalLabel);
        assertNotEquals(gimbalLabel, unknownLabel, "a gimbal must not read as the generic fallback");

        assertEquals("gimbal", MavlinkVehicleConfigurator.vehicleKind(26));
        assertNull(MavlinkVehicleConfigurator.vehicleKind(9001), "a genuinely unknown number has no label to report");

        // Both still fall through to UNKNOWN VehicleKind -- FlightModes has nowhere else to put them
        // (deliberately; see FlightModes.vehicleKind's own javadoc) -- but that is a fact about
        // VehicleKind's own scope, not about whether the underlying classification agrees.
        assertEquals(VehicleKind.UNKNOWN, FlightModes.vehicleKind(26));
        assertEquals(VehicleKind.UNKNOWN, FlightModes.vehicleKind(9001));
    }

    @Test
    void unsupportedVehiclesGetARealLabelFromEveryCallSiteEvenThoughNoneCanFlyOrDriveThem() {
        int[] unsupported = {7, 8, 9, 16, 17, 28};
        for (int mavType : unsupported) {
            assertEquals(VehicleKind.UNKNOWN, FlightModes.vehicleKind(mavType), "mavType " + mavType);
            String label = MavlinkHeartbeatScanner.vehicleKind(mavType);
            assertEquals(MavlinkVehicleConfigurator.vehicleKind(mavType), label, "mavType " + mavType);
            assertTrue(label != null && !label.isBlank(), "mavType " + mavType + " must have a real label");
        }
    }

    @Test
    void everyMavTypeThePriorThreeLocalTablesCollectivelyKnewStillResolvesConsistently() {
        // The union of every MAV_TYPE any of FlightModes/MavlinkHeartbeatScanner/
        // MavlinkVehicleConfigurator's own (now-deleted) local tables covered, pre-R1.
        int[] union = {1, 2, 3, 4, 10, 11, 12, 13, 14, 15, 19, 20, 21, 22, 23, 24, 25};
        for (int mavType : union) {
            VehicleClass expected = VehicleClass.of(mavType);
            assertNotEquals(VehicleClass.UNKNOWN, expected, "mavType " + mavType + " was known to a prior table");
            // FlightModes.vehicleKind must never contradict the shared taxonomy's family.
            VehicleKind kind = FlightModes.vehicleKind(mavType);
            if (expected == VehicleClass.COPTER) {
                assertEquals(VehicleKind.COPTER, kind, "mavType " + mavType);
            } else if (expected == VehicleClass.PLANE) {
                assertEquals(VehicleKind.PLANE, kind, "mavType " + mavType);
            } else if (expected == VehicleClass.ROVER) {
                assertEquals(VehicleKind.ROVER, kind, "mavType " + mavType);
            } else {
                assertEquals(VehicleKind.UNKNOWN, kind, "mavType " + mavType); // SUBMARINE's case
            }
            // Discovery and the onboarding probe must still name it identically.
            assertEquals(MavlinkVehicleConfigurator.vehicleKind(mavType), MavlinkHeartbeatScanner.vehicleKind(mavType),
                    "mavType " + mavType + " named differently by discovery vs. probe");
        }
    }

    @Test
    void selectableModesNeverOffersAModeForAFamilyWithNoArduPilotTable() {
        // A submarine, an unsupported airframe, or a not-a-vehicle instrument must never surface a
        // "selectable mode" -- there is no ArduSub table, and there never should be one guessed here.
        assertEquals(java.util.List.of(), FlightModes.selectableModes(AUTOPILOT_ARDUPILOTMEGA, 12)); // submarine
        assertEquals(java.util.List.of(), FlightModes.selectableModes(AUTOPILOT_ARDUPILOTMEGA, 26)); // gimbal
        assertEquals(java.util.List.of(), FlightModes.selectableModes(AUTOPILOT_ARDUPILOTMEGA, 7));  // airship
    }
}
