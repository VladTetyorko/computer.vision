package com.drones.vision.api.dto;

import com.drones.vision.api.support.ControlEnumParsing;
import com.drones.vision.flight.domain.model.VehicleKind;

/**
 * Request body for {@code POST /api/control-profiles} (docs/plans/active/
 * CONTROLLER-SETUP-CONTEXT.md §4.4) — start a new layout for one vehicle kind.
 *
 * <p>Carries no bindings: a new profile is always a <em>copy of the built-in</em> for its kind
 * (decision C7), so the operator starts from something that already flies and edits it, rather than
 * from an empty map that would engage with nothing bound to the throttle.
 *
 * @param kind the vehicle kind whose built-in to copy, case-insensitive
 * @param name what the operator wants to call it; must not be blank
 */
public record CreateControlProfileRequest(String kind, String name) {

    /**
     * @return the requested vehicle kind
     * @throws IllegalArgumentException if {@code kind} is missing or unrecognized (→ 400)
     */
    public VehicleKind toKind() {
        return ControlEnumParsing.parse(VehicleKind.class, kind, "kind");
    }
}
