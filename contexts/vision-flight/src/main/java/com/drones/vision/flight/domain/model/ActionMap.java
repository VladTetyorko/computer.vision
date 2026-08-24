package com.drones.vision.flight.domain.model;

import java.util.List;
import java.util.Optional;

/**
 * Every {@link ActionBinding} in one {@link ControlProfile} — the command half of a profile, sitting
 * beside {@link ChannelMap}'s streaming half (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md §4.1).
 *
 * <p>A plain container with one lookup, deliberately: unlike {@link ChannelMap#apply}, nothing here
 * turns readings into a frame. Action bindings are dispatched by the driving adapter that owns the
 * physical input — the browser — because a one-shot command belongs on the audited REST command
 * surface rather than in a 33 Hz relay (decision C3). This module's job is to say what is bound, not
 * to press it.
 *
 * @param bindings the action bindings; defensively copied. May be empty — the built-in profiles bind
 *                 no actions at all, because guessing which button on an unknown gamepad should arm
 *                 a vehicle is exactly the wrong kind of helpful
 */
public record ActionMap(List<ActionBinding> bindings) {

    public ActionMap {
        if (bindings == null) {
            throw new IllegalArgumentException("ActionMap bindings must not be null");
        }
        bindings = List.copyOf(bindings);
    }

    /** @return an action map that binds nothing */
    public static ActionMap empty() {
        return new ActionMap(List.of());
    }

    /**
     * The binding on one physical control.
     *
     * @param source      which array the control is read from
     * @param sourceIndex its index in that array
     * @return the binding, or empty if that control is unbound
     */
    public Optional<ActionBinding> find(ControlBinding.Source source, int sourceIndex) {
        return bindings.stream().filter(binding -> binding.isControl(source, sourceIndex)).findFirst();
    }

    /** @return {@code true} if nothing is bound */
    public boolean isEmpty() {
        return bindings.isEmpty();
    }
}
