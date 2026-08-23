package com.drones.vision.flight.domain.model;

/**
 * One physical joystick/gamepad control (an axis or a button) mapped to one RC channel, with a
 * linear calibration (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md §1). Full per-axis calibration UI is
 * deferred; this record models what a linear default needs today.
 *
 * <p>{@link #toMicros(double)} applies, in order: clamp the raw input to its source's natural
 * range, {@link #reversed() reverse} it, snap it to rest when it falls within the {@link
 * #deadband()}, linear-map it to {@code [minMicros, maxMicros]}, then clamp the result. See that
 * method's own javadoc for the two sources' different mapping shapes.
 *
 * <h2>Travel is in the microseconds, not in a flag</h2>
 * A binding is unidirectional exactly when its rest point equals its minimum ({@code centerMicros
 * == minMicros}) — a copter's throttle, idle at 1000&nbsp;µs — and centred otherwise — a rover's
 * throttle, stopped at 1500&nbsp;µs with reverse below it. {@link #travel()} <em>derives</em> that
 * rather than storing it, so a stored flag can never disagree with the numbers it describes
 * (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §2 P4). The mapping math needs no branch
 * for it: the negative half of {@link #toMicros} scales by {@code (centerMicros - minMicros)},
 * which is zero for a unidirectional binding, so an accidental negative input pins at idle instead
 * of doing something surprising.
 *
 * @param source       {@link Source#AXIS} (normalized input {@code [-1,1]}, e.g. a stick) or
 *                     {@link Source#BUTTON} (normalized input {@code [0,1]}, e.g. a switch)
 * @param function     what this binding does to the vehicle — the meaning the RC channel number
 *                     alone does not carry (RC1 is roll on a copter, steering on a rover)
 * @param sourceIndex  index into the Gamepad API's {@code axes}/{@code buttons} array this
 *                     binding reads from; must not be negative
 * @param rcChannel    the 1-based RC channel this binding drives; must be within {@code [1,18]}
 * @param minMicros    output at the control's minimum; within {@code [RcChannels.MIN_MICROS,
 *                     RcChannels.MAX_MICROS]}
 * @param centerMicros output at rest ({@link Source#AXIS} only — see {@link #toMicros(double)});
 *                     must satisfy {@code minMicros <= centerMicros <= maxMicros}
 * @param maxMicros    output at the control's maximum; within {@code [RcChannels.MIN_MICROS,
 *                     RcChannels.MAX_MICROS]}
 * @param deadband     fraction of the input's natural range, around rest, that snaps to rest;
 *                     {@code [0,1]}
 * @param reversed     whether the raw input direction is inverted before mapping
 */
public record ControlBinding(Source source, ControlFunction function, int sourceIndex, int rcChannel,
                              int minMicros, int centerMicros, int maxMicros,
                              double deadband, boolean reversed) {

    /** What kind of physical control feeds a {@link ControlBinding}. */
    public enum Source { AXIS, BUTTON }

    /**
     * Where a control rests when nothing is touching it, and therefore what its full travel means.
     * Derived from the microseconds — see this record's own "Travel is in the microseconds" section.
     */
    public enum Travel {

        /** Rests at {@link ControlBinding#centerMicros()}, travels both ways: roll, pitch, yaw, a rover's throttle. */
        CENTERED,

        /** Rests at {@link ControlBinding#minMicros()}, travels one way: a copter's throttle, and every button. */
        UNIDIRECTIONAL
    }

    /** Neutral pulse width — the rest point of every centred binding, and RC's own convention. */
    public static final int CENTER_MICROS = 1500;

    public ControlBinding {
        if (source == null) {
            throw new IllegalArgumentException("ControlBinding source must not be null");
        }
        if (function == null) {
            throw new IllegalArgumentException("ControlBinding function must not be null");
        }
        if (sourceIndex < 0) {
            throw new IllegalArgumentException("ControlBinding sourceIndex must not be negative: " + sourceIndex);
        }
        if (rcChannel < 1 || rcChannel > 18) {
            throw new IllegalArgumentException("ControlBinding rcChannel must be within [1,18]: " + rcChannel);
        }
        requireMicros("minMicros", minMicros);
        requireMicros("centerMicros", centerMicros);
        requireMicros("maxMicros", maxMicros);
        if (!(minMicros <= centerMicros && centerMicros <= maxMicros)) {
            throw new IllegalArgumentException(
                    "ControlBinding must satisfy minMicros<=centerMicros<=maxMicros, got "
                            + minMicros + "<=" + centerMicros + "<=" + maxMicros);
        }
        if (Double.isNaN(deadband) || deadband < 0.0 || deadband > 1.0) {
            throw new IllegalArgumentException("ControlBinding deadband must be within [0,1]: " + deadband);
        }
    }

    private static void requireMicros(String field, int micros) {
        if (micros < RcChannels.MIN_MICROS || micros > RcChannels.MAX_MICROS) {
            throw new IllegalArgumentException(
                    "ControlBinding " + field + " must be within [" + RcChannels.MIN_MICROS + ","
                            + RcChannels.MAX_MICROS + "]: " + micros);
        }
    }

    /**
     * A stick that rests at its centre and travels both ways — roll, pitch, yaw, steering, and a
     * rover's throttle, where centre is <em>stop</em> and the lower half is reverse.
     *
     * @param function    what the binding does to the vehicle
     * @param sourceIndex index into the input's {@code axes} array
     * @param rcChannel   the 1-based RC channel to drive
     * @return a centred binding over the full {@code [1000,2000]} µs travel, no deadband, not reversed
     */
    public static ControlBinding centeredAxis(ControlFunction function, int sourceIndex, int rcChannel) {
        return new ControlBinding(Source.AXIS, function, sourceIndex, rcChannel,
                RcChannels.MIN_MICROS, CENTER_MICROS, RcChannels.MAX_MICROS, 0.0, false);
    }

    /**
     * A control that rests at its minimum and travels one way — a copter's or plane's throttle,
     * where rest is <em>idle</em>, not half power.
     *
     * @param function    what the binding does to the vehicle
     * @param sourceIndex index into the input's {@code axes} array
     * @param rcChannel   the 1-based RC channel to drive
     * @return a unidirectional binding whose rest point is {@link RcChannels#MIN_MICROS}
     */
    public static ControlBinding unidirectionalAxis(ControlFunction function, int sourceIndex, int rcChannel) {
        return new ControlBinding(Source.AXIS, function, sourceIndex, rcChannel,
                RcChannels.MIN_MICROS, RcChannels.MIN_MICROS, RcChannels.MAX_MICROS, 0.0, false);
    }

    /**
     * A two-position switch: unpressed maps to {@link RcChannels#MIN_MICROS}, pressed to {@link
     * RcChannels#MAX_MICROS}. {@code centerMicros} plays no part in a button's mapping (see {@link
     * #toMicros(double)}) and is set to the minimum only to satisfy this record's own
     * {@code min <= center <= max} invariant.
     *
     * <p>No {@link ControlProfile} binds a button by default — arm, disarm and mode select go
     * through the flight-command REST surface instead, per ArduPilot's own advice not to let a
     * joystick own the mode or aux channels (docs/plans/active/OPERATOR-CONTROL-CONTEXT.md D6/S3).
     *
     * @param function    what the binding does to the vehicle
     * @param sourceIndex index into the input's {@code buttons} array
     * @param rcChannel   the 1-based RC channel to drive
     * @return a button binding over the full {@code [1000,2000]} µs travel
     */
    public static ControlBinding button(ControlFunction function, int sourceIndex, int rcChannel) {
        return new ControlBinding(Source.BUTTON, function, sourceIndex, rcChannel,
                RcChannels.MIN_MICROS, RcChannels.MIN_MICROS, RcChannels.MAX_MICROS, 0.0, false);
    }

    /**
     * Where this control rests, derived from its own microseconds rather than stored beside them.
     *
     * @return {@link Travel#UNIDIRECTIONAL} when the rest point is the minimum, {@link
     *         Travel#CENTERED} otherwise
     */
    public Travel travel() {
        return centerMicros == minMicros ? Travel.UNIDIRECTIONAL : Travel.CENTERED;
    }

    /**
     * Maps one raw, normalized reading of this control to a microsecond RC pulse width.
     *
     * <p>{@link Source#AXIS}: {@code normalized} is clamped to {@code [-1,1]}, reversed (negated)
     * if {@link #reversed()}, snapped to {@code 0} (rest) when its magnitude is within {@link
     * #deadband()}, then piecewise-linear mapped — {@code [-1,0]} onto {@code
     * [minMicros,centerMicros]} and {@code [0,1]} onto {@code [centerMicros,maxMicros]}.
     *
     * <p>{@link Source#BUTTON}: {@code normalized} is clamped to {@code [0,1]}, reversed (as
     * {@code 1 - normalized}) if {@link #reversed()}, snapped to {@code 0} (unpressed/rest) when
     * within {@link #deadband()} of it, then linear-mapped straight from {@code [0,1]} onto
     * {@code [minMicros,maxMicros]} — {@link #centerMicros()} plays no part in a button's mapping.
     *
     * @param normalized the raw reading: {@code [-1,1]} for {@link Source#AXIS}, {@code [0,1]}
     *                    for {@link Source#BUTTON} — out-of-range values are clamped, not rejected
     * @return the microsecond pulse width, clamped to {@code [minMicros, maxMicros]}
     */
    public int toMicros(double normalized) {
        return source == Source.AXIS ? axisMicros(normalized) : buttonMicros(normalized);
    }

    private int axisMicros(double normalized) {
        double value = clamp(normalized, -1.0, 1.0);
        if (reversed) {
            value = -value;
        }
        if (Math.abs(value) <= deadband) {
            value = 0.0;
        }
        double micros = value >= 0
                ? centerMicros + value * (maxMicros - centerMicros)
                : centerMicros + value * (centerMicros - minMicros);
        return clampMicros(micros);
    }

    private int buttonMicros(double normalized) {
        double value = clamp(normalized, 0.0, 1.0);
        if (reversed) {
            value = 1.0 - value;
        }
        if (value <= deadband) {
            value = 0.0;
        }
        double micros = minMicros + value * (maxMicros - minMicros);
        return clampMicros(micros);
    }

    private int clampMicros(double micros) {
        return (int) Math.round(clamp(micros, minMicros, maxMicros));
    }

    private static double clamp(double value, double lo, double hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}
