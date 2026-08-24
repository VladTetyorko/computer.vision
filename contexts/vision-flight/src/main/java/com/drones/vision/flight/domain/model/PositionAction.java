package com.drones.vision.flight.domain.model;

/**
 * One switch position and the command it sends — the leaf of the binding model
 * (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md §4.1).
 *
 * <p>Modelling the action <em>per position</em> rather than per control is what makes a three-position
 * switch the flight-mode selector every RC pilot already knows: low is Stabilize, middle is Loiter,
 * high is RTL — three commands from one control, which is precisely how the same switch behaves when
 * it is wired to the receiver instead of to this platform.
 *
 * @param position which detent fires this; must be one the owning {@link ControlInputKind} actually
 *                 has, which {@link ActionBinding} enforces
 * @param action   what to send
 * @param parameter the action's parameter — a mode name, an aux-function number, or {@code null}
 *                  when the action takes none; validated against {@link ControlAction#parameter()}
 */
public record PositionAction(SwitchPosition position, ControlAction action, String parameter) {

    public PositionAction {
        if (position == null) {
            throw new IllegalArgumentException("PositionAction position must not be null");
        }
        if (action == null) {
            throw new IllegalArgumentException("PositionAction action must not be null");
        }
        parameter = parameter == null || parameter.isBlank() ? null : parameter.trim();
        action.requireValidParameter(parameter);
    }

    /**
     * A position that sends a command needing no parameter.
     *
     * @param position the detent
     * @param action   an action whose {@link ControlAction#parameter()} is {@link
     *                 ControlAction.Parameter#NONE}
     * @return the binding leaf
     */
    public static PositionAction of(SwitchPosition position, ControlAction action) {
        return new PositionAction(position, action, null);
    }

    /**
     * The aux-function number this position triggers.
     *
     * @return the parsed {@code RCx_OPTION} number
     * @throws IllegalStateException if this position's action is not {@link ControlAction#AUX_FUNCTION}
     */
    public int auxFunctionNumber() {
        if (action != ControlAction.AUX_FUNCTION) {
            throw new IllegalStateException("Not an AUX_FUNCTION position action: " + action);
        }
        return Integer.parseInt(parameter);
    }
}
