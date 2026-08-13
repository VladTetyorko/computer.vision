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
 * @param source       {@link Source#AXIS} (normalized input {@code [-1,1]}, e.g. a stick) or
 *                     {@link Source#BUTTON} (normalized input {@code [0,1]}, e.g. a switch)
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
public record ControlBinding(Source source, int sourceIndex, int rcChannel,
                              int minMicros, int centerMicros, int maxMicros,
                              double deadband, boolean reversed) {

    /** What kind of physical control feeds a {@link ControlBinding}. */
    public enum Source { AXIS, BUTTON }

    public ControlBinding {
        if (source == null) {
            throw new IllegalArgumentException("ControlBinding source must not be null");
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
