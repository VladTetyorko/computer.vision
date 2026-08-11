package com.drones.vision.adapter.mavlink;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Firmware/vehicle-aware flight-mode name lookup (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-a). MAVLink's
 * {@code HEARTBEAT.custom_mode} is a firmware-specific integer with no meaning shared across
 * autopilots, so turning it into a human-readable name requires knowing both which firmware
 * ({@code HEARTBEAT.autopilot}) and, for ArduPilot, which vehicle family ({@code HEARTBEAT.type})
 * sent it.
 *
 * <h2>Firmware/table selection</h2>
 * <ul>
 *   <li>{@code autopilot == 3} (ARDUPILOTMEGA) — ArduPilot, including INAV (which defaults to
 *       masquerading as ArduPilot and maps its own modes onto ArduPilot's numbers, so the same
 *       tables are correct for it too — see this plan's research-facts section). The table is
 *       then chosen by {@code mavType}: copter (quad/hex/octo/tri-rotor, coaxial, helicopter),
 *       plane (fixed-wing plus every VTOL vehicle variant), or rover (ground rover, surface
 *       boat). A {@code mavType} outside all three families has no table to select and falls back
 *       to {@code "Mode <n>"}.</li>
 *   <li>{@code autopilot == 0} (GENERIC) — Betaflight (BF ≥4.6's 8-entry flight-mode set);
 *       {@code mavType} is not consulted.</li>
 *   <li>{@code autopilot == 12} (PX4) — table out of scope for this phase; always falls back to
 *       {@code "Mode <n>"}.</li>
 *   <li>Any other {@code autopilot} value — unrecognized; always falls back to {@code "Mode <n>"}.</li>
 * </ul>
 * A {@code customMode} not present in the selected table (a firmware version reporting a mode
 * number this table doesn't yet know) also falls back to {@code "Mode <n>"} — never a fabricated
 * name.
 */
final class FlightModes {

    static final int AUTOPILOT_GENERIC = 0;
    static final int AUTOPILOT_ARDUPILOTMEGA = 3;
    static final int AUTOPILOT_PX4 = 12;

    private static final int MAV_TYPE_FIXED_WING = 1;
    private static final int MAV_TYPE_QUADROTOR = 2;
    private static final int MAV_TYPE_COAXIAL = 3;
    private static final int MAV_TYPE_HELICOPTER = 4;
    private static final int MAV_TYPE_GROUND_ROVER = 10;
    private static final int MAV_TYPE_SURFACE_BOAT = 11;
    private static final int MAV_TYPE_HEXAROTOR = 13;
    private static final int MAV_TYPE_OCTOROTOR = 14;
    private static final int MAV_TYPE_TRICOPTER = 15;
    private static final int MAV_TYPE_VTOL_TAILSITTER_DUOROTOR = 19;
    private static final int MAV_TYPE_VTOL_TAILSITTER_QUADROTOR = 20;
    private static final int MAV_TYPE_VTOL_TILTROTOR = 21;
    private static final int MAV_TYPE_VTOL_FIXEDROTOR = 22;
    private static final int MAV_TYPE_VTOL_TAILSITTER = 23;
    private static final int MAV_TYPE_VTOL_TILTWING = 24;
    private static final int MAV_TYPE_VTOL_RESERVED5 = 25;

    private static final Set<Integer> COPTER_MAV_TYPES = Set.of(
            MAV_TYPE_QUADROTOR, MAV_TYPE_HEXAROTOR, MAV_TYPE_OCTOROTOR, MAV_TYPE_TRICOPTER,
            MAV_TYPE_COAXIAL, MAV_TYPE_HELICOPTER);

    private static final Set<Integer> PLANE_MAV_TYPES = Set.of(
            MAV_TYPE_FIXED_WING, MAV_TYPE_VTOL_TAILSITTER_DUOROTOR, MAV_TYPE_VTOL_TAILSITTER_QUADROTOR,
            MAV_TYPE_VTOL_TILTROTOR, MAV_TYPE_VTOL_FIXEDROTOR, MAV_TYPE_VTOL_TAILSITTER, MAV_TYPE_VTOL_TILTWING,
            MAV_TYPE_VTOL_RESERVED5);

    private static final Set<Integer> ROVER_MAV_TYPES = Set.of(MAV_TYPE_GROUND_ROVER, MAV_TYPE_SURFACE_BOAT);

    private static final Map<Integer, String> ARDUPILOT_COPTER = Map.ofEntries(
            Map.entry(0, "Stabilize"), Map.entry(1, "Acro"), Map.entry(2, "AltHold"), Map.entry(3, "Auto"),
            Map.entry(4, "Guided"), Map.entry(5, "Loiter"), Map.entry(6, "RTL"), Map.entry(7, "Circle"),
            Map.entry(9, "Land"), Map.entry(11, "Drift"), Map.entry(13, "Sport"), Map.entry(14, "Flip"),
            Map.entry(15, "AutoTune"), Map.entry(16, "PosHold"), Map.entry(17, "Brake"), Map.entry(18, "Throw"),
            Map.entry(19, "AvoidADSB"), Map.entry(20, "GuidedNoGPS"), Map.entry(21, "SmartRTL"),
            Map.entry(22, "FlowHold"), Map.entry(23, "Follow"), Map.entry(24, "ZigZag"), Map.entry(25, "SystemId"),
            Map.entry(26, "AutoRotate"), Map.entry(27, "AutoRTL"), Map.entry(28, "Turtle"));

    private static final Map<Integer, String> ARDUPILOT_PLANE = Map.ofEntries(
            Map.entry(0, "Manual"), Map.entry(1, "Circle"), Map.entry(2, "Stabilize"), Map.entry(3, "Training"),
            Map.entry(4, "Acro"), Map.entry(5, "FBWA"), Map.entry(6, "FBWB"), Map.entry(7, "Cruise"),
            Map.entry(8, "AutoTune"), Map.entry(10, "Auto"), Map.entry(11, "RTL"), Map.entry(12, "Loiter"),
            Map.entry(13, "Takeoff"), Map.entry(14, "AvoidADSB"), Map.entry(15, "Guided"),
            Map.entry(16, "Initializing"), Map.entry(17, "QStabilize"), Map.entry(18, "QHover"),
            Map.entry(19, "QLoiter"), Map.entry(20, "QLand"), Map.entry(21, "QRTL"), Map.entry(22, "QAutoTune"),
            Map.entry(23, "QAcro"), Map.entry(24, "Thermal"), Map.entry(25, "LoiterAltQLand"),
            Map.entry(26, "AutoLand"));

    private static final Map<Integer, String> ARDUPILOT_ROVER = Map.ofEntries(
            Map.entry(0, "Manual"), Map.entry(1, "Acro"), Map.entry(3, "Steering"), Map.entry(4, "Hold"),
            Map.entry(5, "Loiter"), Map.entry(6, "Follow"), Map.entry(7, "Simple"), Map.entry(10, "Auto"),
            Map.entry(11, "RTL"), Map.entry(12, "SmartRTL"), Map.entry(15, "Guided"));

    private static final Map<Integer, String> BETAFLIGHT = Map.of(
            0, "Acro", 1, "Angle", 2, "Horizon", 3, "AltHold", 4, "PosHold", 5, "Autopilot", 6, "RTL", 7, "Failsafe");

    private FlightModes() {
    }

    /**
     * @param autopilot  {@code HEARTBEAT.autopilot} raw value (e.g. 3 = ArduPilot/INAV, 0 = generic/Betaflight,
     *                   12 = PX4)
     * @param mavType    {@code HEARTBEAT.type} raw value; consulted only to pick an ArduPilot vehicle-family table
     * @param customMode {@code HEARTBEAT.custom_mode} raw value
     * @return the human-readable mode name, or {@code "Mode <n>"} when the firmware/vehicle has no known table, or
     *         the table has no entry for {@code customMode}
     */
    static String name(int autopilot, int mavType, long customMode) {
        Map<Integer, String> table = tableFor(autopilot, mavType);
        if (table != null && customMode <= Integer.MAX_VALUE) {
            String name = table.get((int) customMode);
            if (name != null) {
                return name;
            }
        }
        return "Mode " + customMode;
    }

    /**
     * Reverse of {@link #name}: the numeric {@code custom_mode} for a named mode within the table
     * selected by {@code autopilot}/{@code mavType} (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1 —
     * {@code MavlinkFlightCommander}'s return-to-home mode resolution). {@code null} when the
     * firmware/vehicle combination has no table at all, or the table has no entry with exactly
     * this name — never a guessed/fabricated mode number.
     *
     * <p>This is a pure name lookup with no awareness of which firmwares this platform is willing
     * to actually <em>command</em> — Betaflight's table, for instance, does contain an entry named
     * {@code "RTL"} that this method will happily resolve, even though Betaflight's own RC link
     * does not process {@code MAV_CMD_DO_SET_MODE} at all. Deciding which firmwares are
     * commandable is the caller's job (see {@code MavlinkFlightCommander}), not this class's — see
     * its own class javadoc for why: this table only knows mode names, never which firmwares
     * accept remote mode-set commands.
     *
     * @param autopilot {@code HEARTBEAT.autopilot} raw value
     * @param mavType   {@code HEARTBEAT.type} raw value; consulted only to pick an ArduPilot vehicle-family table
     * @param modeName  the exact mode name as returned by {@link #name}, e.g. {@code "RTL"}
     */
    static Integer customModeFor(int autopilot, int mavType, String modeName) {
        Map<Integer, String> table = tableFor(autopilot, mavType);
        if (table == null) {
            return null;
        }
        for (Map.Entry<Integer, String> entry : table.entrySet()) {
            if (entry.getValue().equals(modeName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * The distinct mode names a caller may pass to {@code MavlinkFlightCommander.setMode} for the
     * vehicle-family table selected by {@code autopilot}/{@code mavType} (docs/plans/active/DRONE-INFRA-PLAN.md
     * I-e Stage 2 — the {@code selectableModes} half of a {@code FlightCapability} snapshot).
     *
     * <p>Deliberately <b>ArduPilot-only</b>: only an ArduPilot copter/plane/rover table yields a
     * non-empty list. Betaflight ({@code autopilot} GENERIC) returns an empty list even though it
     * has a mode table, because Betaflight's RC link does not process {@code MAV_CMD_DO_SET_MODE}
     * at all — offering its mode names as "selectable" would be dishonest (see {@code
     * MavlinkFlightCommander}'s own commandability rules). PX4/unknown autopilots and an ArduPilot
     * {@code mavType} outside every family also return empty. Names are returned <b>sorted
     * alphabetically</b> for a stable, testable order (the underlying {@link Map#ofEntries} tables
     * have no meaningful iteration order of their own).
     */
    static List<String> selectableModes(int autopilot, int mavType) {
        if (autopilot != AUTOPILOT_ARDUPILOTMEGA) {
            return List.of();
        }
        Map<Integer, String> table = tableFor(autopilot, mavType);
        if (table == null) {
            return List.of();
        }
        return table.values().stream().distinct().sorted().toList();
    }

    private static Map<Integer, String> tableFor(int autopilot, int mavType) {
        if (autopilot == AUTOPILOT_ARDUPILOTMEGA) {
            if (COPTER_MAV_TYPES.contains(mavType)) {
                return ARDUPILOT_COPTER;
            }
            if (PLANE_MAV_TYPES.contains(mavType)) {
                return ARDUPILOT_PLANE;
            }
            if (ROVER_MAV_TYPES.contains(mavType)) {
                return ARDUPILOT_ROVER;
            }
            return null;
        }
        if (autopilot == AUTOPILOT_GENERIC) {
            return BETAFLIGHT;
        }
        return null; // PX4 (12) and any other/unrecognized autopilot: no table, "Mode <n>" fallback.
    }
}
