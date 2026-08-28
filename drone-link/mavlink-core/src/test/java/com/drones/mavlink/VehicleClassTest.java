package com.drones.mavlink;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The one {@code MAV_TYPE} taxonomy (docs/plans/active/FLEET-RADIO-PLAN.md R1). Pins the exact
 * numeric membership of every one of the four outcomes so a future edit that moves a number
 * between buckets (or reintroduces a second, locally-hand-maintained table elsewhere) shows up as
 * a failing assertion here rather than a silent drift.
 */
class VehicleClassTest {

    @Test
    void copterFamilyCoversEveryRotorcraftIncludingTheOnesNoPriorTableKnew() {
        for (int mavType : new int[] {2, 3, 4, 13, 14, 15, 29, 35, 43}) {
            assertEquals(VehicleClass.COPTER, VehicleClass.of(mavType), "mavType " + mavType);
        }
        assertEquals("quadcopter", VehicleClass.label(2));
        assertEquals("coaxial helicopter", VehicleClass.label(3));
        assertEquals("helicopter", VehicleClass.label(4));
        assertEquals("hexacopter", VehicleClass.label(13));
        assertEquals("octocopter", VehicleClass.label(14));
        assertEquals("tricopter", VehicleClass.label(15));
        assertEquals("dodecacopter", VehicleClass.label(29), "MAV_TYPE_DODECAROTOR");
        assertEquals("decacopter", VehicleClass.label(35), "MAV_TYPE_DECAROTOR");
        assertEquals("multirotor", VehicleClass.label(43), "MAV_TYPE_GENERIC_MULTIROTOR");
    }

    @Test
    void planeFamilyCoversFixedWingAndEveryVtolVariant() {
        assertEquals(VehicleClass.PLANE, VehicleClass.of(1));
        assertEquals("fixed-wing", VehicleClass.label(1));
        for (int mavType = 19; mavType <= 25; mavType++) {
            assertEquals(VehicleClass.PLANE, VehicleClass.of(mavType), "VTOL mavType " + mavType);
            assertEquals("VTOL", VehicleClass.label(mavType));
        }
    }

    @Test
    void roverFamilyNamesAGroundRoverAndASurfaceBoatIdentically() {
        assertEquals(VehicleClass.ROVER, VehicleClass.of(10));
        assertEquals(VehicleClass.ROVER, VehicleClass.of(11));
        assertEquals("rover", VehicleClass.label(10));
        assertEquals("surface boat", VehicleClass.label(11));
    }

    @Test
    void submarineIsItsOwnSlotNotFoldedIntoAnythingElse() {
        assertEquals(VehicleClass.SUBMARINE, VehicleClass.of(12));
        assertEquals("submarine", VehicleClass.label(12));
    }

    @Test
    void unsupportedVehiclesAreRecognizedNotUnknown() {
        int[] unsupported = {7, 8, 9, 16, 17, 28};
        for (int mavType : unsupported) {
            assertEquals(VehicleClass.UNSUPPORTED_VEHICLE, VehicleClass.of(mavType), "mavType " + mavType);
        }
        assertEquals("airship", VehicleClass.label(7));
        assertEquals("free balloon", VehicleClass.label(8));
        assertEquals("rocket", VehicleClass.label(9));
        assertEquals("flapping-wing aircraft", VehicleClass.label(16));
        assertEquals("kite", VehicleClass.label(17));
        assertEquals("parafoil", VehicleClass.label(28));
    }

    @Test
    void nonVehicleInstrumentsAreClassifiedDistinctlyFromAnUnrecognizedVehicle() {
        int[] notVehicles = {5, 6, 18, 26, 27, 30, 31, 32, 33, 34, 36, 37, 38, 39, 40, 41, 42, 44, 45};
        for (int mavType : notVehicles) {
            assertEquals(VehicleClass.NOT_A_VEHICLE, VehicleClass.of(mavType), "mavType " + mavType);
        }
        // A gimbal (26) is a real, known fact -- distinct from "we never heard of this number".
        assertEquals("gimbal", VehicleClass.label(26));
        assertEquals("ground control station", VehicleClass.label(6));
    }

    @Test
    void aGenuinelyUnrecognizedNumberIsUnknownWithNoLabelAtAll() {
        assertEquals(VehicleClass.UNKNOWN, VehicleClass.of(250));
        assertNull(VehicleClass.label(250));
        // MAV_TYPE_GENERIC (0) never told us anything specific about the airframe either.
        assertEquals(VehicleClass.UNKNOWN, VehicleClass.of(0));
        assertNull(VehicleClass.label(0));
    }

    @Test
    void unknownAndNotAVehicleAreDistinctOutcomes() {
        // The whole point of NOT_A_VEHICLE existing: a gimbal must never look like "unidentified".
        assertEquals(VehicleClass.NOT_A_VEHICLE, VehicleClass.of(26));
        assertEquals(VehicleClass.UNKNOWN, VehicleClass.of(9999));
        org.junit.jupiter.api.Assertions.assertNotEquals(VehicleClass.of(26), VehicleClass.of(9999));
    }
}
