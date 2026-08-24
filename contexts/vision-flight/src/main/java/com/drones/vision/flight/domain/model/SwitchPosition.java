package com.drones.vision.flight.domain.model;

/**
 * Where a switch is sitting — ArduPilot's own three positions, at ArduPilot's own thresholds
 * (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md §2.3, decision C4).
 *
 * <p>The bands are the firmware's, not ours: an RC auxiliary function activates above
 * {@value #HIGH_MICROS} µs and deactivates below {@value #LOW_MICROS} µs, with the gap between them
 * being MIDDLE on a three-position switch. Quantizing anywhere else would mean the station and the
 * vehicle disagreeing about what the operator just did with a switch — the station showing "middle"
 * while the vehicle acts on "high".
 *
 * <p>Deliberately not an on/off boolean: a two-position switch is this same type restricted to
 * {@link #LOW}/{@link #HIGH}, so the three-position case needs no separate model and a
 * {@link ControlInputKind} change cannot silently reinterpret an existing binding.
 */
public enum SwitchPosition {

    LOW("Low"),
    MIDDLE("Middle"),
    HIGH("High");

    /** Below this pulse width ArduPilot treats an aux switch as LOW (deactivated). */
    public static final int LOW_MICROS = 1200;

    /** Above this pulse width ArduPilot treats an aux switch as HIGH (activated). */
    public static final int HIGH_MICROS = 1800;

    private final String label;

    SwitchPosition(String label) {
        this.label = label;
    }

    /** Normalized magnitude at which a switch reported on an axis is considered off-centre. */
    private static final double AXIS_DETENT = 0.5;

    /** Normalized value at which a switch reported on a button is considered pressed. */
    private static final double BUTTON_PRESSED = 0.5;

    /**
     * Quantizes one raw, normalized reading into the position the operator has the switch in.
     *
     * <p>This is the single quantizer for the whole platform: {@link ControlBinding#toMicros}
     * (switch driving a channel) and the browser's own action dispatch (switch driving a command)
     * must agree exactly, or a bound switch would send a command that disagrees with the channel it
     * is simultaneously showing.
     *
     * @param source what array the value was read from — an axis reports {@code [-1,1]} and can
     *               therefore express three positions; a button reports {@code [0,1]} and can only
     *               ever be {@link #LOW} or {@link #HIGH}
     * @param kind   how the operator declared this control behaves; {@link ControlInputKind#SWITCH_3}
     *               is the only kind that can return {@link #MIDDLE}
     * @param raw    the raw reading; out-of-range values are clamped, not rejected
     * @return the switch position
     * @throws IllegalArgumentException if {@code source} or {@code kind} is {@code null}
     */
    public static SwitchPosition of(ControlBinding.Source source, ControlInputKind kind, double raw) {
        if (source == null) {
            throw new IllegalArgumentException("SwitchPosition source must not be null");
        }
        if (kind == null) {
            throw new IllegalArgumentException("SwitchPosition kind must not be null");
        }
        if (source == ControlBinding.Source.BUTTON) {
            return raw >= BUTTON_PRESSED ? HIGH : LOW;
        }
        if (kind == ControlInputKind.SWITCH_3) {
            if (raw <= -AXIS_DETENT) {
                return LOW;
            }
            return raw >= AXIS_DETENT ? HIGH : MIDDLE;
        }
        return raw >= 0.0 ? HIGH : LOW;
    }

    /**
     * This position mirrored, for a binding the operator has marked reversed.
     *
     * @return {@link #LOW} for {@link #HIGH} and vice versa; {@link #MIDDLE} is its own mirror
     */
    public SwitchPosition reversed() {
        return switch (this) {
            case LOW -> HIGH;
            case MIDDLE -> MIDDLE;
            case HIGH -> LOW;
        };
    }

    /**
     * The value ArduPilot's {@code MAV_CMD_DO_AUX_FUNCTION} expects in its switch-level parameter.
     *
     * @return {@code 0} LOW, {@code 1} MIDDLE, {@code 2} HIGH
     */
    public int auxFunctionLevel() {
        return ordinal();
    }

    /**
     * A short human label, so a driving adapter shows the operator the same three words the
     * firmware's own documentation uses rather than an enum constant.
     *
     * @return {@code "Low"}, {@code "Middle"} or {@code "High"}
     */
    public String label() {
        return label;
    }
}
