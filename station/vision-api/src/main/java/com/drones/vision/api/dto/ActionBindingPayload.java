package com.drones.vision.api.dto;

import com.drones.vision.api.support.ControlEnumParsing;
import com.drones.vision.flight.domain.model.ActionBinding;
import com.drones.vision.flight.domain.model.ControlAction;
import com.drones.vision.flight.domain.model.ControlInputKind;
import com.drones.vision.flight.domain.model.PositionAction;
import com.drones.vision.flight.domain.model.SwitchPosition;

import java.util.List;

/**
 * One action binding on the {@code /api/control-profiles} wire (docs/plans/active/
 * CONTROLLER-SETUP-CONTEXT.md §4.1) — a button or switch whose <em>positions</em> fire one-shot
 * commands, as opposed to a {@link ControlBindingPayload} that streams into an RC channel.
 *
 * <p>Deliberately carries no labels: what an action is called and whether it is dangerous are
 * catalogue facts, served once by {@code GET /api/control-profiles/catalog} rather than repeated on
 * every row of every profile (decision C8).
 *
 * @param source      {@code "AXIS"} or {@code "BUTTON"} — which Gamepad array the value is read from
 * @param kind        {@code "BUTTON"}, {@code "SWITCH_2"} or {@code "SWITCH_3"}; an {@code "AXIS"}
 *                    has no positions and so cannot fire actions
 * @param sourceIndex index into the Gamepad API's {@code axes}/{@code buttons} array
 * @param positions   what each position of this control does; at least one, at most one entry per
 *                    position
 */
public record ActionBindingPayload(String source, String kind, int sourceIndex,
                                    List<PositionActionPayload> positions) {

    /**
     * What one position of a switch does.
     *
     * @param position  {@code "LOW"}, {@code "MIDDLE"} or {@code "HIGH"}
     * @param action    the action name, from the catalogue's {@code actions}
     * @param parameter the action's parameter where it takes one — a mode name for {@code SET_MODE},
     *                  an {@code RCx_OPTION} number for {@code AUX_FUNCTION} — otherwise {@code null}
     */
    public record PositionActionPayload(String position, String action, String parameter) {

        static PositionActionPayload from(PositionAction positionAction) {
            return new PositionActionPayload(positionAction.position().name(), positionAction.action().name(),
                    positionAction.parameter());
        }

        PositionAction toPositionAction() {
            return new PositionAction(ControlEnumParsing.parse(SwitchPosition.class, position, "position"),
                    ControlEnumParsing.parse(ControlAction.class, action, "action"), parameter);
        }
    }

    /**
     * Maps one domain action binding to its wire form.
     *
     * @param binding the binding to serialize
     * @return the wire entry
     */
    public static ActionBindingPayload from(ActionBinding binding) {
        return new ActionBindingPayload(binding.source().name(), binding.kind().name(), binding.sourceIndex(),
                binding.positions().stream().map(PositionActionPayload::from).toList());
    }

    /**
     * Converts this request entry to a domain action binding.
     *
     * @return the equivalent {@link ActionBinding}
     * @throws IllegalArgumentException if an enum name is missing/unrecognized, or the binding fails
     *                                   {@link ActionBinding}'s own validation (an axis kind, a
     *                                   position the kind does not have, a duplicated position, a
     *                                   parameter missing where the action requires one)
     */
    public ActionBinding toBinding() {
        return new ActionBinding(ControlEnumParsing.source(source),
                ControlEnumParsing.parse(ControlInputKind.class, kind, "kind"), sourceIndex,
                positions == null ? List.of() : positions.stream()
                        .map(PositionActionPayload::toPositionAction).toList());
    }
}
