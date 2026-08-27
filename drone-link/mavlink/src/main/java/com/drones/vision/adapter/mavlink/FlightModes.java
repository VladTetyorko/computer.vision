package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.VehicleClass;
import com.drones.vision.flight.domain.model.UnidentifiedReason;
import com.drones.vision.flight.domain.model.VehicleKind;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 *       then chosen by {@code mavType}'s {@link com.drones.mavlink.VehicleClass}: copter
 *       (quad/hex/octo/tri/deca/dodeca-rotor, coaxial, helicopter, generic multirotor), plane
 *       (fixed-wing plus every VTOL vehicle variant), or rover (ground rover, surface boat). A
 *       {@code mavType} outside all three families (including {@code VehicleClass.SUBMARINE} —
 *       ArduSub is out of scope, docs/plans/active/FLEET-RADIO-PLAN.md R1) has no table to select
 *       and falls back to {@code "Mode <n>"}.</li>
 *   <li>{@code autopilot == 0} (GENERIC) — Betaflight (BF ≥4.6's 8-entry flight-mode set);
 *       {@code mavType} is not consulted.</li>
 *   <li>{@code autopilot == 12} (PX4) — table out of scope for this phase; always falls back to
 *       {@code "Mode <n>"}.</li>
 *   <li>Any other {@code autopilot} value — unrecognized; always falls back to {@code "Mode <n>"}.</li>
 * </ul>
 * A {@code customMode} not present in the selected table (a firmware version reporting a mode
 * number this table doesn't yet know) also falls back to {@code "Mode <n>"} — never a fabricated
 * name.
 *
 * <p><b>FLEET-RADIO R1 (F1):</b> vehicle-family membership ({@code mavType} → copter/plane/rover)
 * used to be this class's own {@code Set<Integer>} triplet, hand-maintained separately from
 * {@code MavlinkHeartbeatScanner}'s and {@code MavlinkVehicleConfigurator}'s own copies — the three
 * disagreed with each other on the same vehicles and none of them knew a dodecarotor or a
 * decarotor. All three now delegate to {@link com.drones.mavlink.VehicleClass}, the one
 * {@code MAV_TYPE} table in {@code mavlink-core}.
 */
final class FlightModes {

    static final int AUTOPILOT_GENERIC = 0;
    static final int AUTOPILOT_ARDUPILOTMEGA = 3;
    static final int AUTOPILOT_PX4 = 12;

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

    // Verified 2026-08-27 against ArduPilot's own Rover/mode.h (docs/plans/active/FLEET-RADIO-PLAN.md
    // R1 grounding) for the stable-4.7.0 generation our SITL image targets. 8 (Dock), 9 (Circle) and
    // 16 (Initialising) were missing before this wave. "Initialising" (with an "s") is ArduRover's
    // own spelling, distinct from ARDUPILOT_PLANE's "Initializing" (with a "z") two tables below --
    // two different firmware source trees, kept exactly as each one spells it, not normalized.
    private static final Map<Integer, String> ARDUPILOT_ROVER = Map.ofEntries(
            Map.entry(0, "Manual"), Map.entry(1, "Acro"), Map.entry(3, "Steering"), Map.entry(4, "Hold"),
            Map.entry(5, "Loiter"), Map.entry(6, "Follow"), Map.entry(7, "Simple"), Map.entry(8, "Dock"),
            Map.entry(9, "Circle"), Map.entry(10, "Auto"), Map.entry(11, "RTL"), Map.entry(12, "SmartRTL"),
            Map.entry(15, "Guided"), Map.entry(16, "Initialising"));

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
     * <p><b>The same limitation applies one level down, per mode, not just per firmware
     * (docs/plans/active/FLEET-RADIO-PLAN.md R1).</b> ArduRover's {@code Dock} mode (mode 8) is
     * compiled in only when the firmware build defines {@code MODE_DOCK_ENABLED} — some builds
     * genuinely have no mode 8 at all. This method resolves {@code "Dock"} to {@code 8}
     * unconditionally anyway, deliberately, for two reasons: (1) hiding it would make Dock
     * uncommandable even on the builds that <em>do</em> have it, and this class has no live signal
     * (no capability bit, no probed parameter) for which specific optional modes a given firmware
     * build compiled in — the same "no such parameter" gap {@code MavlinkVehicleConfigurator}'s own
     * parameter probing already lives with, one layer up; (2) commanding a mode a build doesn't
     * have is not actually silent in the common case — {@code MavlinkFlightCommander#send} already
     * throws for an explicit non-ACCEPTED {@code COMMAND_ACK}, so a firmware that honestly refuses
     * an unrecognized {@code custom_mode} surfaces that refusal normally. The residual risk this
     * does <em>not</em> cover — a build that ACKs {@code ACCEPTED} without actually honoring the
     * mode change — is a firmware-honesty question no client-side table edit can fix; closing it
     * for real needs an onboarding-stage capability probe this class deliberately does not attempt
     * to fake by guessing.
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

    /**
     * The vehicle family behind a raw {@code HEARTBEAT.type}, as the control-shape taxonomy
     * {@code vision-flight} reasons in (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §1.4).
     *
     * <p>Delegates to {@link com.drones.mavlink.VehicleClass}, the one {@code MAV_TYPE} table
     * (docs/plans/active/FLEET-RADIO-PLAN.md R1/D1) — a vehicle whose {@code VehicleClass} is
     * {@code COPTER} is, by construction, a rover-table candidate's opposite. Before FLEET-RADIO R1
     * this method held its own copy of the family sets; the copy is gone, this delegates.
     *
     * <p>Deliberately <b>autopilot-independent</b>, unlike {@link #tableFor}: a quadrotor is a
     * quadrotor whether ArduPilot, PX4 or Betaflight is flying it. Only the <em>mode names</em> are
     * firmware-specific, not the physics. {@code VehicleClass.SUBMARINE} (ArduSub, out of scope),
     * {@code UNSUPPORTED_VEHICLE}, {@code NOT_A_VEHICLE} and a genuinely unrecognized {@code
     * mavType} all yield {@link VehicleKind#UNKNOWN} — {@code VehicleKind} has no room for those
     * finer distinctions (FLEET-RADIO R1 deliberately does not add one; see that plan's D2/R2) —
     * never a guess, per VEHICLE-CONTROL-PROFILES-CONTEXT.md §2 P8.
     *
     * @param mavType {@code HEARTBEAT.type} raw value
     * @return the control-shape family, or {@link VehicleKind#UNKNOWN} if this class knows no family for it
     */
    static VehicleKind vehicleKind(int mavType) {
        return switch (VehicleClass.of(mavType)) {
            case COPTER -> VehicleKind.COPTER;
            case PLANE -> VehicleKind.PLANE;
            case ROVER -> VehicleKind.ROVER;
            case SUBMARINE, UNSUPPORTED_VEHICLE, NOT_A_VEHICLE, UNKNOWN -> VehicleKind.UNKNOWN;
        };
    }

    /**
     * The finer distinction {@link #vehicleKind(int)} folds away when it answers {@link
     * VehicleKind#UNKNOWN} — the one this class' caller (see {@code
     * MavlinkManualControlSender.AdapterLink}) rides onto {@link
     * com.drones.vision.flight.domain.port.ManualControlLink#unidentifiedReason()} so a refused
     * {@code engage} can tell an operator <em>why</em> (docs/plans/active/FLEET-RADIO-PLAN.md R2).
     *
     * <p>{@code VehicleClass.SUBMARINE} answers {@link UnidentifiedReason#UNSUPPORTED_VEHICLE} here,
     * the same as {@code UNSUPPORTED_VEHICLE} itself: ArduSub is a real, recognized airframe this
     * platform has simply chosen not to support (D2) — indistinguishable, from an operator's engage
     * attempt, from an airship or a rocket. {@code VehicleClass} keeps it a separate constant only so
     * a later wave adding real ArduSub support has a slot to start reading from; this method has no
     * such use for the distinction.
     *
     * @param mavType {@code HEARTBEAT.type} raw value
     * @return empty for a recognized {@link VehicleKind}; otherwise the specific reason it is not
     */
    static Optional<UnidentifiedReason> unidentifiedReason(int mavType) {
        return switch (VehicleClass.of(mavType)) {
            case COPTER, PLANE, ROVER -> Optional.empty();
            case SUBMARINE, UNSUPPORTED_VEHICLE -> Optional.of(UnidentifiedReason.UNSUPPORTED_VEHICLE);
            case NOT_A_VEHICLE -> Optional.of(UnidentifiedReason.NOT_A_VEHICLE);
            case UNKNOWN -> Optional.of(UnidentifiedReason.NEVER_IDENTIFIED);
        };
    }

    private static Map<Integer, String> tableFor(int autopilot, int mavType) {
        if (autopilot == AUTOPILOT_ARDUPILOTMEGA) {
            return switch (VehicleClass.of(mavType)) {
                case COPTER -> ARDUPILOT_COPTER;
                case PLANE -> ARDUPILOT_PLANE;
                case ROVER -> ARDUPILOT_ROVER;
                // SUBMARINE (ArduSub, out of scope), an unsupported airframe, a non-vehicle
                // instrument, or a genuinely unknown number: no table, "Mode <n>" fallback.
                case SUBMARINE, UNSUPPORTED_VEHICLE, NOT_A_VEHICLE, UNKNOWN -> null;
            };
        }
        if (autopilot == AUTOPILOT_GENERIC) {
            return BETAFLIGHT;
        }
        return null; // PX4 (12) and any other/unrecognized autopilot: no table, "Mode <n>" fallback.
    }
}
