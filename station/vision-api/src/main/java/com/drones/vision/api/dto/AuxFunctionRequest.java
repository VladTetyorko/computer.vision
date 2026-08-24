package com.drones.vision.api.dto;

import com.drones.vision.flight.domain.model.ControlAction;
import com.drones.vision.flight.domain.model.SwitchPosition;

/**
 * Request body for {@code POST /api/assets/{id}/aux-function} (docs/plans/active/
 * CONTROLLER-SETUP-CONTEXT.md §2.3/§4.4) — fire one ArduPilot auxiliary function at one switch
 * level, which is what a bound 2- or 3-position switch does when the operator moves it.
 *
 * <p>{@code level} is ArduPilot's own: {@code 0} LOW, {@code 1} MIDDLE, {@code 2} HIGH, exactly as
 * {@code MAV_CMD_DO_AUX_FUNCTION}'s second parameter expects. A client that thinks in positions
 * reads the mapping off {@code GET /api/control-profiles/catalog} rather than assuming it.
 *
 * @param function the {@code RCx_OPTION} function number
 * @param level    the switch level to fire it at
 */
public record AuxFunctionRequest(int function, int level) {

    /**
     * @return the requested function number
     * @throws IllegalArgumentException if it is out of ArduPilot's option range (→ 400)
     */
    public int requireFunction() {
        if (function < 0 || function > ControlAction.MAX_AUX_FUNCTION) {
            throw new IllegalArgumentException("Aux function number must be within [0,"
                    + ControlAction.MAX_AUX_FUNCTION + "]: " + function);
        }
        return function;
    }

    /**
     * @return the requested level, guaranteed to name a real {@link SwitchPosition}
     * @throws IllegalArgumentException if it is outside {@code [0,2]} (→ 400)
     */
    public int requireLevel() {
        if (level < 0 || level >= SwitchPosition.values().length) {
            throw new IllegalArgumentException("Aux function switch level must be within [0,"
                    + (SwitchPosition.values().length - 1) + "]: " + level);
        }
        return level;
    }
}
