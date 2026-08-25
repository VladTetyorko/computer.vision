package com.drones.vision.api.dto;

import com.drones.vision.api.support.AuxFunctionCatalog;
import com.drones.vision.flight.domain.model.ControlAction;
import com.drones.vision.flight.domain.model.ControlBinding;
import com.drones.vision.flight.domain.model.ControlFunction;
import com.drones.vision.flight.domain.model.ControlInputKind;
import com.drones.vision.flight.domain.model.ControlProfile;
import com.drones.vision.flight.domain.model.RcChannels;
import com.drones.vision.flight.domain.model.SwitchPosition;
import com.drones.vision.flight.domain.model.VehicleKind;

import java.util.Arrays;
import java.util.List;

/**
 * Response for {@code GET /api/control-profiles/catalog} (decision C8) — everything a controller
 * setup page may offer, answered by the backend rather than duplicated in the browser.
 *
 * <p>The reason this endpoint exists at all: the set of commands this platform can send is a
 * backend fact. A UI holding its own copy drifts, and the way it drifts is by offering an operator
 * an action the server then refuses — discovered at the moment they flick the switch. Every picker
 * on the setup page is filled from here, so an action that is not sendable is not offerable.
 *
 * @param vehicleKinds the vehicle kinds a profile can be made for, with the name each one's built-in
 *                     layout goes by
 * @param inputKinds   what a control can be declared to be, and which Gamepad array each kind can be
 *                     read from — a 3-position switch reports on the axes array, so it lists only
 *                     {@code "AXIS"} (decision C1)
 * @param positions    the switch positions, with the level ArduPilot's {@code DO_AUX_FUNCTION}
 *                     expects for each — so a client never has to know that {@code HIGH} is 2
 * @param functions    what a streaming control can drive on the vehicle
 * @param actions      what a switch position can fire, and what each needs configured alongside it
 * @param auxFunctions the {@code RCx_OPTION} numbers this deployment offers by name; a menu, not a
 *                     whitelist — any number in range may be bound
 * @param maxRcChannel the highest RC channel a binding may usefully drive — the number the relay
 *                     actually puts on the wire ({@code RcChannels#RELAYED_CHANNELS}), not the
 *                     {@code [1,18]} a {@code ControlBinding} will validate. A UI offering more
 *                     would be offering channels the aircraft never hears about
 */
public record ControlCatalogResponse(List<VehicleKindOption> vehicleKinds, List<InputKindOption> inputKinds,
                                      List<PositionOption> positions, List<FunctionOption> functions,
                                      List<ActionOption> actions, List<AuxFunctionOption> auxFunctions,
                                      int maxRcChannel) {

    /**
     * @param name  the {@code VehicleKind} name
     * @param label what its built-in layout is called, e.g. {@code "Multirotor"}
     */
    public record VehicleKindOption(String name, String label) {
    }

    /**
     * @param name      the {@code ControlInputKind} name
     * @param label     e.g. {@code "3-position switch"}
     * @param sources   the {@code source} values this kind may be read from
     * @param positions the positions this kind reports; empty for a continuous axis
     */
    public record InputKindOption(String name, String label, List<String> sources, List<String> positions) {
    }

    /**
     * @param name  the {@code SwitchPosition} name
     * @param label e.g. {@code "Middle"}
     * @param level the value {@code MAV_CMD_DO_AUX_FUNCTION} expects for this position
     */
    public record PositionOption(String name, String label, int level) {
    }

    /**
     * @param name  the {@code ControlFunction} name
     * @param label e.g. {@code "Steering"}
     */
    public record FunctionOption(String name, String label) {
    }

    /**
     * @param name      the {@code ControlAction} name
     * @param label     e.g. {@code "Emergency stop"}
     * @param parameter what it needs configured alongside it: {@code "NONE"}, {@code "MODE_NAME"} or
     *                  {@code "AUX_FUNCTION"}
     * @param dangerous whether binding it needs the extra confirmation friction of decision C9
     */
    public record ActionOption(String name, String label, String parameter, boolean dangerous) {
    }

    /**
     * @param number the {@code RCx_OPTION} number
     * @param label  what to call it in front of an operator
     */
    public record AuxFunctionOption(int number, String label) {
    }

    /**
     * Builds the catalogue from the domain's own enums plus this deployment's aux-function menu.
     *
     * @param auxFunctions the configured aux-function menu
     * @return the full catalogue
     */
    public static ControlCatalogResponse of(AuxFunctionCatalog auxFunctions) {
        return new ControlCatalogResponse(
                Arrays.stream(VehicleKind.values())
                        .map(kind -> new VehicleKindOption(kind.name(), ControlProfile.forKind(kind).displayName()))
                        .toList(),
                Arrays.stream(ControlInputKind.values())
                        .map(kind -> new InputKindOption(kind.name(), kind.label(),
                                Arrays.stream(ControlBinding.Source.values()).filter(kind::allows).map(Enum::name)
                                        .toList(),
                                kind.positions().stream().map(Enum::name).toList()))
                        .toList(),
                Arrays.stream(SwitchPosition.values())
                        .map(position -> new PositionOption(position.name(), position.label(),
                                position.auxFunctionLevel()))
                        .toList(),
                Arrays.stream(ControlFunction.values())
                        .map(function -> new FunctionOption(function.name(), function.label()))
                        .toList(),
                Arrays.stream(ControlAction.values())
                        .map(action -> new ActionOption(action.name(), action.label(), action.parameter().name(),
                                action.dangerous()))
                        .toList(),
                auxFunctions.functions().stream()
                        .map(function -> new AuxFunctionOption(function.number(), function.label()))
                        .toList(),
                RcChannels.RELAYED_CHANNELS);
    }
}
