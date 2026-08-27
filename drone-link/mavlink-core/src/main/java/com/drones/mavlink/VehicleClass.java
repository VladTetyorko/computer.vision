package com.drones.mavlink;

import java.util.Map;

/**
 * The single {@code MAV_TYPE} (MAVLink's {@code HEARTBEAT.type}) taxonomy for this codebase
 * (docs/plans/active/FLEET-RADIO-PLAN.md R1, D1). Before this class existed, three call sites —
 * {@code adapter-mavlink}'s {@code FlightModes}, {@code MavlinkHeartbeatScanner} and
 * {@code MavlinkVehicleConfigurator} — each hand-maintained their own subset of {@code MAV_TYPE}
 * values, and the three subsets disagreed with each other on the very same vehicles (a boat named
 * {@code "rover"} in one table and {@code "boat"} in another; a submarine known to one table and
 * not the other; a dodecarotor known to none). This class is the one place that mapping lives now;
 * every consumer delegates to it rather than keeping its own copy (FLEET-RADIO F1).
 *
 * <h2>Four outcomes, not two</h2>
 * A naive table answers only "which vehicle family is this" and falls back to "unknown" for
 * everything else — which silently conflates two very different facts: a vehicle this platform
 * genuinely cannot identify, and a non-vehicle instrument ({@link #NOT_A_VEHICLE}, e.g. a gimbal or
 * a companion GCS) heartbeating on the very same link. The next wave (FLEET-RADIO R2) needs to
 * refuse to <em>engage</em> an unidentified vehicle — and refusing because a gimbal happens to be on
 * the link would be a bug that looks identical to that correct refusal unless the two are classified
 * apart here, first. This class therefore distinguishes:
 * <ul>
 *   <li>{@link #COPTER}, {@link #PLANE}, {@link #ROVER}, {@link #SUBMARINE} — a real, recognized
 *       vehicle family. {@link #SUBMARINE} (MAV_TYPE 12) is deliberately a slot in <em>this</em>
 *       table with no counterpart in {@code vision-flight}'s {@code VehicleKind} — ArduSub is out of
 *       scope by operator instruction (2026-08-26), but the table already has a place for it so
 *       adding real submarine support later is one row here, not a fourth hand-maintained table.</li>
 *   <li>{@link #UNSUPPORTED_VEHICLE} — a real airframe this platform has chosen not to support
 *       (airship, free balloon, rocket, flapping-wing, kite, parafoil). Distinct from "unknown": the
 *       vehicle told us exactly what it is, we simply have no control profile for it.</li>
 *   <li>{@link #NOT_A_VEHICLE} — an instrument heartbeating on the same link, never something to fly
 *       or drive (antenna tracker, GCS, onboard controller, gimbal, ADS-B, camera, charging station,
 *       FLARM, servo, remote-ID beacon, battery, parachute, logger, OSD, IMU, GPS receiver, winch,
 *       illuminator, spacecraft orbiter).</li>
 *   <li>{@link #UNKNOWN} — a {@code MAV_TYPE} number this table has genuinely never seen. The only
 *       one of the four that means "we have no information at all."</li>
 * </ul>
 *
 * <h2>Source</h2>
 * Numeric values and family membership verified 2026-08-27 against MAVLink's own
 * {@code minimal.xml}/{@code common.xml} {@code MAV_TYPE} enum (the wire authority — a raw integer
 * has no other meaning). Vehicle-family membership (which numbers are copter/plane/rover) matches
 * what {@code FlightModes} already knew for the numbers it covered; this class adds the numbers no
 * prior local table covered at all (dodecarotor 29, decarotor 35, generic multirotor 43, every
 * VTOL 19–25, submarine 12) and classifies every other defined {@code MAV_TYPE} number into
 * {@link #UNSUPPORTED_VEHICLE} or {@link #NOT_A_VEHICLE} rather than leaving it to fall through to
 * "unknown" by omission.
 *
 * <h2>Vocabulary</h2>
 * {@link #label(int)} is the one shared display vocabulary for a {@code MAV_TYPE} number. Before
 * this class, {@code MavlinkHeartbeatScanner} (device discovery) and {@code
 * MavlinkVehicleConfigurator} (the onboarding probe) each spelled the very same vehicles
 * differently ({@code "fixed-wing"}/{@code "fixed wing"}, {@code "rover"}/{@code "ground rover"},
 * {@code "boat"}/{@code "surface boat"}) — an aircraft discovered as one name and probed as another
 * looked, to an operator, like two different facts about the same vehicle. Both call sites now read
 * this method; there is exactly one spelling per {@code MAV_TYPE} number in this codebase.
 */
public enum VehicleClass {

    /** Multirotor or helicopter — quad/hex/octo/tri/deca/dodeca-rotor, coaxial, generic multirotor. */
    COPTER,

    /** Fixed-wing, including every VTOL variant. */
    PLANE,

    /** Ground rover or surface boat — same control shape, same ArduPilot mode table. */
    ROVER,

    /**
     * Submarine (MAV_TYPE 12). A real vehicle family with no {@code VehicleKind} counterpart today
     * — ArduSub is out of scope by operator instruction. Exists as its own constant, not folded
     * into {@link #UNSUPPORTED_VEHICLE}, precisely so that adding real support later needs no new
     * bucket, only a consumer that starts reading this one.
     */
    SUBMARINE,

    /** A recognized airframe this platform does not support flying or driving. */
    UNSUPPORTED_VEHICLE,

    /** Not a vehicle at all — an instrument or ground system heartbeating on the same link. */
    NOT_A_VEHICLE,

    /** A {@code MAV_TYPE} number this table has never seen. The only "we know nothing" outcome. */
    UNKNOWN;

    private record Entry(VehicleClass vehicleClass, String label) {
    }

    private static Entry entry(VehicleClass vehicleClass, String label) {
        return new Entry(vehicleClass, label);
    }

    private static final Map<Integer, Entry> MAV_TYPES = Map.ofEntries(
            // --- COPTER (multirotor/helicopter family) ---
            Map.entry(2, entry(COPTER, "quadcopter")),
            Map.entry(3, entry(COPTER, "coaxial helicopter")),
            Map.entry(4, entry(COPTER, "helicopter")),
            Map.entry(13, entry(COPTER, "hexacopter")),
            Map.entry(14, entry(COPTER, "octocopter")),
            Map.entry(15, entry(COPTER, "tricopter")),
            Map.entry(29, entry(COPTER, "dodecacopter")),
            Map.entry(35, entry(COPTER, "decacopter")),
            Map.entry(43, entry(COPTER, "multirotor")),

            // --- PLANE (fixed-wing + every VTOL variant) ---
            Map.entry(1, entry(PLANE, "fixed-wing")),
            Map.entry(19, entry(PLANE, "VTOL")),
            Map.entry(20, entry(PLANE, "VTOL")),
            Map.entry(21, entry(PLANE, "VTOL")),
            Map.entry(22, entry(PLANE, "VTOL")),
            Map.entry(23, entry(PLANE, "VTOL")),
            Map.entry(24, entry(PLANE, "VTOL")),
            Map.entry(25, entry(PLANE, "VTOL")),

            // --- ROVER (ground rover + surface boat: same control shape, same mode table) ---
            Map.entry(10, entry(ROVER, "rover")),
            Map.entry(11, entry(ROVER, "surface boat")),

            // --- SUBMARINE (a slot, not a VehicleKind -- see class javadoc) ---
            Map.entry(12, entry(SUBMARINE, "submarine")),

            // --- UNSUPPORTED_VEHICLE (a real airframe, no control profile) ---
            Map.entry(7, entry(UNSUPPORTED_VEHICLE, "airship")),
            Map.entry(8, entry(UNSUPPORTED_VEHICLE, "free balloon")),
            Map.entry(9, entry(UNSUPPORTED_VEHICLE, "rocket")),
            Map.entry(16, entry(UNSUPPORTED_VEHICLE, "flapping-wing aircraft")),
            Map.entry(17, entry(UNSUPPORTED_VEHICLE, "kite")),
            Map.entry(28, entry(UNSUPPORTED_VEHICLE, "parafoil")),

            // --- NOT_A_VEHICLE (instruments/ground systems heartbeating on the same link) ---
            Map.entry(5, entry(NOT_A_VEHICLE, "antenna tracker")),
            Map.entry(6, entry(NOT_A_VEHICLE, "ground control station")),
            Map.entry(18, entry(NOT_A_VEHICLE, "onboard controller")),
            Map.entry(26, entry(NOT_A_VEHICLE, "gimbal")),
            Map.entry(27, entry(NOT_A_VEHICLE, "ADS-B transponder")),
            Map.entry(30, entry(NOT_A_VEHICLE, "camera")),
            Map.entry(31, entry(NOT_A_VEHICLE, "charging station")),
            Map.entry(32, entry(NOT_A_VEHICLE, "FLARM transponder")),
            Map.entry(33, entry(NOT_A_VEHICLE, "servo")),
            Map.entry(34, entry(NOT_A_VEHICLE, "remote ID beacon")),
            Map.entry(36, entry(NOT_A_VEHICLE, "battery")),
            Map.entry(37, entry(NOT_A_VEHICLE, "parachute")),
            Map.entry(38, entry(NOT_A_VEHICLE, "onboard logger")),
            Map.entry(39, entry(NOT_A_VEHICLE, "OSD")),
            Map.entry(40, entry(NOT_A_VEHICLE, "IMU")),
            Map.entry(41, entry(NOT_A_VEHICLE, "GPS receiver")),
            Map.entry(42, entry(NOT_A_VEHICLE, "winch")),
            Map.entry(44, entry(NOT_A_VEHICLE, "illuminator")),
            Map.entry(45, entry(NOT_A_VEHICLE, "spacecraft orbiter")));

    /**
     * @param mavType raw {@code HEARTBEAT.type} wire value
     * @return the classification for this number, or {@link #UNKNOWN} if this table has never seen
     *         it — never a guess
     */
    public static VehicleClass of(int mavType) {
        Entry found = MAV_TYPES.get(mavType);
        return found == null ? UNKNOWN : found.vehicleClass();
    }

    /**
     * The one shared display label for a {@code MAV_TYPE} number (see class javadoc,
     * "Vocabulary"). {@code null} only for a genuinely {@link #UNKNOWN} number — every recognized
     * entry, including {@link #UNSUPPORTED_VEHICLE} and {@link #NOT_A_VEHICLE} ones, has a real
     * label, because "gimbal" and "airship" are known facts, not guesses.
     *
     * @param mavType raw {@code HEARTBEAT.type} wire value
     * @return the shared label, or {@code null} if {@code mavType} is not in this table
     */
    public static String label(int mavType) {
        Entry found = MAV_TYPES.get(mavType);
        return found == null ? null : found.label();
    }
}
