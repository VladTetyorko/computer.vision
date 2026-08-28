package com.drones.vision.flight.domain.model;

/**
 * What kind of machine is on the other end of a manual-control link — the one fact that decides
 * what a stick means (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §2 P1).
 *
 * <p>This is a <em>control</em> taxonomy, not a catalogue of airframes: two vehicles share a kind
 * exactly when they share a control shape. That is why {@link #ROVER} covers both a ground rover
 * and a surface boat — they steer and drive identically, and ArduPilot gives them the same mode
 * table too, so splitting them would produce two identical {@link ControlProfile}s free to drift
 * apart.
 *
 * <p>Resolved <b>live, per engage</b>, from the {@code HEARTBEAT.type} currently being heard (see
 * {@link com.drones.vision.flight.domain.port.ManualControlLink#vehicleKind()}) — never from stored
 * configuration, which would go stale in exactly the case that matters, an operator re-flashing the
 * flight controller (CLAUDE.md rule 9, "newest data wins"). The mapping from a raw MAVLink
 * {@code MAV_TYPE} lives in the adapter that decodes heartbeats; this module never sees the number.
 */
public enum VehicleKind {

    /** Multirotor or helicopter: throttle is unidirectional, roll/pitch/yaw are centred. */
    COPTER,

    /** Fixed-wing, including every VTOL variant: same stick shape as {@link #COPTER}. */
    PLANE,

    /** Ground rover or surface boat: steering and a <em>bidirectional</em> throttle, no pitch, no yaw. */
    ROVER,

    /**
     * The vehicle has not identified itself as anything this platform recognizes — or has, and the
     * answer is "not something to fly or drive" or "a real airframe we do not support" (FLEET-RADIO
     * R2 folds three distinct facts in here; see {@link
     * com.drones.vision.flight.domain.port.ManualControlLink#unidentifiedReason()} and {@link
     * UnidentifiedReason} for which one, and why {@code VehicleKind} itself does not grow a fourth
     * constant to say so — all three share this one, nonexistent, control shape).
     *
     * <p>Deliberately <b>not</b> resolved to a guess. There is no safe default: a throttle resting
     * at its minimum is idle on a copter and <em>full reverse</em> on a rover, so either guess is
     * dangerous on the other machine. {@link ControlProfile#forKind} answers this with an empty,
     * unflyable map (no guess, no fabricated centred value either), and {@code
     * DefaultManualControlService#engage} refuses to open a session on this kind at all, naming the
     * specific reason on the wire so the operator — who can see the vehicle — can act on it
     * (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §2 P8).
     */
    UNKNOWN
}
