package com.drones.vision.flight.domain.model;

import java.util.List;

/**
 * What a physical control <em>is</em>, as the operator declares it — the choice the controller-setup
 * page asks for on every detected input (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md §4.2,
 * decision C1).
 *
 * <h2>Why this is not the same thing as {@link ControlBinding.Source}</h2>
 * {@code Source} says which array a value is <em>read from</em>; this says how it <em>behaves</em>.
 * The two genuinely differ on real hardware: an EdgeTX three-position switch in USB-joystick mode
 * arrives on the <b>axes</b> array (as −1 / 0 / +1), while a latching toggle on a gamepad arrives on
 * the <b>buttons</b> array. Collapsing the two facts into one enum would make one of those two
 * shapes impossible to express.
 *
 * <p>{@link #SWITCH_3} accepts an axis source only. One button cannot report three positions, and
 * quietly degrading it to two would be a fabricated read — the operator would have configured a
 * middle position that can never fire.
 */
public enum ControlInputKind {

    /** Continuous travel: a stick, a slider, a pot. The only kind that can drive a channel smoothly. */
    AXIS("Axis", List.of()),

    /** Momentary: fires while held, rests released. Its one meaningful position is {@link SwitchPosition#HIGH}. */
    BUTTON("Button", List.of(SwitchPosition.HIGH)),

    /** Two detents — a latching toggle, or a 2-position switch on a transmitter. */
    SWITCH_2("2-position switch", List.of(SwitchPosition.LOW, SwitchPosition.HIGH)),

    /** Three detents. The classic flight-mode switch; on a transmitter it arrives on an axis. */
    SWITCH_3("3-position switch", List.of(SwitchPosition.LOW, SwitchPosition.MIDDLE, SwitchPosition.HIGH));

    private final String label;
    private final List<SwitchPosition> positions;

    ControlInputKind(String label, List<SwitchPosition> positions) {
        this.label = label;
        this.positions = positions;
    }

    /**
     * A short human label for this kind, for a driving adapter to show in a picker.
     *
     * @return e.g. {@code "3-position switch"}
     */
    public String label() {
        return label;
    }

    /**
     * The positions this kind can actually be in, in order — the exact set an {@link ActionBinding}
     * may assign an action to.
     *
     * @return the positions; empty for {@link #AXIS}, which has no detents at all
     */
    public List<SwitchPosition> positions() {
        return positions;
    }

    /**
     * Whether a control of this kind can be read from {@code source}.
     *
     * @param source the array the value would be read from
     * @return {@code true} if the combination is physically expressible — see this enum's own
     *         javadoc for why {@link #SWITCH_3} refuses a button
     */
    public boolean allows(ControlBinding.Source source) {
        if (source == null) {
            return false;
        }
        return switch (this) {
            case AXIS -> source == ControlBinding.Source.AXIS;
            case BUTTON -> source == ControlBinding.Source.BUTTON;
            case SWITCH_2 -> true;
            case SWITCH_3 -> source == ControlBinding.Source.AXIS;
        };
    }

    /**
     * Whether this kind fires discrete, position-based events rather than streaming a value.
     *
     * @return {@code true} for everything except {@link #AXIS}
     */
    public boolean isSwitched() {
        return this != AXIS;
    }
}
