package com.drones.vision.flight.domain.model;

/**
 * What a bound button or switch <em>does</em> — the catalogue of commands this platform can actually
 * send to a vehicle (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md §2.4, decisions C2/C5/C8).
 *
 * <h2>This list is short on purpose</h2>
 * QGroundControl's own joystick catalogue runs to some forty actions — gimbal, camera, zoom, focus,
 * gripper, landing gear, VTOL transition. Most of them are unreachable here: this platform's video
 * is not a MAVLink camera and it commands no gimbal, so offering "Trigger Camera" would bind a
 * control to nothing and only report that at the moment it mattered. What is here is what
 * {@link com.drones.vision.flight.domain.port.FlightCommandPort} can genuinely send.
 *
 * <p>{@link #AUX_FUNCTION} is the deliberate escape hatch that keeps the list short without making
 * it poor: ArduPilot's {@code MAV_CMD_DO_AUX_FUNCTION} reaches <em>every</em> switch-driven feature
 * the airframe actually has — motor emergency stop, gripper, parachute, camera trigger, RC-override
 * enable — by number, with the switch position as its level. One honest command instead of a dozen
 * half-true ones.
 *
 * <p>The catalogue is served to the browser rather than duplicated there (C8): the set of commands
 * the server will accept is a backend fact, and a UI that keeps its own copy eventually offers one
 * the backend refuses.
 */
public enum ControlAction {

    /** Arm the vehicle — spin up its motors. The highest-danger action here. */
    ARM(Parameter.NONE, "Arm", true),

    /** Disarm the vehicle, respecting the autopilot's own checks. */
    DISARM(Parameter.NONE, "Disarm", true),

    /**
     * Arm if disarmed, disarm if armed — QGC's own {@code Toggle Arm}. Resolved against the latest
     * telemetry at the moment the control is used, never against a remembered state.
     */
    TOGGLE_ARM(Parameter.NONE, "Toggle arm", true),

    /**
     * Force-disarm regardless of the autopilot's checks — QGC's {@code Emergency Stop}, and the same
     * {@code 21196} magic parameter. <b>Stops the motors mid-air</b>; the vehicle falls.
     */
    EMERGENCY_STOP(Parameter.NONE, "Emergency stop", true),

    /** Return to launch. On ArduPilot this is a mode change, which is why it needs no parameter. */
    RETURN_TO_HOME(Parameter.NONE, "Return to home", false),

    /**
     * Change flight mode. The parameter is the mode name, validated against the vehicle's own
     * {@link FlightCapability#selectableModes()} when the command is actually sent — never here,
     * because a profile outlives any one vehicle's mode table.
     */
    SET_MODE(Parameter.MODE_NAME, "Set flight mode", false),

    /**
     * Trigger an ArduPilot auxiliary function by number ({@code RCx_OPTION}'s own numbering) with
     * this control's position as the switch level. The parameter is the function number.
     */
    AUX_FUNCTION(Parameter.AUX_FUNCTION, "Aux function", false);

    /** What extra value an action needs before it can be sent — and therefore what the UI must ask for. */
    public enum Parameter {

        /** Nothing to configure. */
        NONE,

        /** A flight-mode name, offered from the vehicle's live capability snapshot. */
        MODE_NAME,

        /** An ArduPilot {@code RCx_OPTION} function number. */
        AUX_FUNCTION
    }

    /**
     * Highest {@code RCx_OPTION} number ArduPilot currently defines, with headroom for new ones.
     * Public because the REST edge range-checks a directly-posted aux function against the same
     * bound a stored binding is validated against — one number, not two that can drift.
     */
    public static final int MAX_AUX_FUNCTION = 400;

    private final Parameter parameter;
    private final String label;
    private final boolean dangerous;

    ControlAction(Parameter parameter, String label, boolean dangerous) {
        this.parameter = parameter;
        this.label = label;
        this.dangerous = dangerous;
    }

    /**
     * What this action needs configured alongside it.
     *
     * @return the parameter kind
     */
    public Parameter parameter() {
        return parameter;
    }

    /**
     * A short human label, for a driving adapter to show in a picker.
     *
     * @return e.g. {@code "Emergency stop"}
     */
    public String label() {
        return label;
    }

    /**
     * Whether binding this action to a control needs the extra friction a driving adapter reserves
     * for irreversible physical consequences (decision C9).
     *
     * @return {@code true} for the arm/disarm family
     */
    public boolean dangerous() {
        return dangerous;
    }

    /**
     * Validates the parameter an operator configured for this action.
     *
     * @param value the configured parameter, {@code null} or blank when none was configured
     * @throws IllegalArgumentException if the value is missing where one is required, present where
     *                                   none is accepted, or out of range for {@link #AUX_FUNCTION}
     */
    public void requireValidParameter(String value) {
        boolean present = value != null && !value.isBlank();
        if (parameter == Parameter.NONE) {
            if (present) {
                throw new IllegalArgumentException(name() + " takes no parameter, got: " + value);
            }
            return;
        }
        if (!present) {
            throw new IllegalArgumentException(name() + " requires a " + parameter + " parameter");
        }
        if (parameter == Parameter.AUX_FUNCTION) {
            int function;
            try {
                function = Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        name() + " parameter must be an ArduPilot RCx_OPTION number, got: " + value, e);
            }
            if (function < 0 || function > MAX_AUX_FUNCTION) {
                throw new IllegalArgumentException(
                        name() + " parameter must be within [0," + MAX_AUX_FUNCTION + "]: " + function);
            }
        }
    }
}
