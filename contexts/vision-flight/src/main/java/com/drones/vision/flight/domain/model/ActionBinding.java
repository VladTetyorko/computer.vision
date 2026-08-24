package com.drones.vision.flight.domain.model;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One physical button or switch bound to <b>commands</b> rather than to an RC channel
 * (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md decision C2) — the half of the binding model that
 * fires once on a position change instead of streaming at the link rate.
 *
 * <p>This is what ArduPilot's own joystick documentation asks for in as many words: bind buttons to
 * <em>Arm</em>, <em>Disarm</em> and <em>Change Mode</em>, and do <b>not</b> let a joystick own the
 * flight-mode or auxiliary channels. Before this type the platform had only the channel half, so an
 * operator who wanted a mode switch had no honest way to build one — which is
 * OPERATOR-CONTROL-CONTEXT.md's gap <b>G7</b>.
 *
 * @param source      which array this control is read from
 * @param kind        how it behaves; must be {@link ControlInputKind#isSwitched() switched} — an
 *                    axis has no detents to fire on — and must {@link ControlInputKind#allows}
 *                    {@code source}
 * @param sourceIndex index into that array; must not be negative
 * @param positions   one entry per detent that does something; non-empty, no duplicates, and every
 *                    position must be one {@code kind} actually has. A detent the operator left
 *                    unassigned is simply absent, not present-with-a-no-op
 */
public record ActionBinding(ControlBinding.Source source, ControlInputKind kind, int sourceIndex,
                            List<PositionAction> positions) {

    public ActionBinding {
        if (source == null) {
            throw new IllegalArgumentException("ActionBinding source must not be null");
        }
        if (kind == null) {
            throw new IllegalArgumentException("ActionBinding kind must not be null");
        }
        if (!kind.isSwitched()) {
            throw new IllegalArgumentException("ActionBinding kind must be a button or switch, not " + kind
                    + " -- an axis has no positions to fire an action on");
        }
        if (!kind.allows(source)) {
            throw new IllegalArgumentException("ActionBinding " + kind + " cannot be read from a " + source
                    + " source (a single button cannot report three positions)");
        }
        if (sourceIndex < 0) {
            throw new IllegalArgumentException("ActionBinding sourceIndex must not be negative: " + sourceIndex);
        }
        if (positions == null || positions.isEmpty()) {
            throw new IllegalArgumentException("ActionBinding positions must not be empty");
        }
        Set<SwitchPosition> seen = EnumSet.noneOf(SwitchPosition.class);
        for (PositionAction entry : positions) {
            if (entry == null) {
                throw new IllegalArgumentException("ActionBinding positions must not contain null entries");
            }
            if (!kind.positions().contains(entry.position())) {
                throw new IllegalArgumentException("A " + kind + " has no " + entry.position() + " position");
            }
            if (!seen.add(entry.position())) {
                throw new IllegalArgumentException("ActionBinding has two actions on the same position: "
                        + entry.position());
            }
        }
        positions = List.copyOf(positions);
    }

    /**
     * A momentary button that fires one command when pressed.
     *
     * @param sourceIndex index into the buttons array
     * @param action      the command to send
     * @return the binding
     */
    public static ActionBinding pressButton(int sourceIndex, ControlAction action) {
        return new ActionBinding(ControlBinding.Source.BUTTON, ControlInputKind.BUTTON, sourceIndex,
                List.of(PositionAction.of(SwitchPosition.HIGH, action)));
    }

    /**
     * What this control does in one position.
     *
     * @param position the detent the control is currently in
     * @return the command for that position, or empty if the operator left it unassigned
     */
    public Optional<PositionAction> actionAt(SwitchPosition position) {
        return positions.stream().filter(entry -> entry.position() == position).findFirst();
    }

    /**
     * Whether this binding drives the same physical control as {@code other} — the check that keeps
     * one control from being bound twice.
     *
     * @param otherSource the other control's source array
     * @param otherIndex  the other control's index
     * @return {@code true} if they are the same physical control
     */
    public boolean isControl(ControlBinding.Source otherSource, int otherIndex) {
        return source == otherSource && sourceIndex == otherIndex;
    }
}
