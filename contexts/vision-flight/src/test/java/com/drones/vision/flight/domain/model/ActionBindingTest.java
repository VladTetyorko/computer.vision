package com.drones.vision.flight.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionBindingTest {

    @Test
    void aThreePositionSwitchIsTheClassicModeSelector() {
        ActionBinding binding = new ActionBinding(ControlBinding.Source.AXIS, ControlInputKind.SWITCH_3, 4,
                List.of(new PositionAction(SwitchPosition.LOW, ControlAction.SET_MODE, "Stabilize"),
                        new PositionAction(SwitchPosition.MIDDLE, ControlAction.SET_MODE, "Loiter"),
                        PositionAction.of(SwitchPosition.HIGH, ControlAction.RETURN_TO_HOME)));

        assertEquals("Stabilize", binding.actionAt(SwitchPosition.LOW).orElseThrow().parameter());
        assertEquals(ControlAction.RETURN_TO_HOME, binding.actionAt(SwitchPosition.HIGH).orElseThrow().action());
    }

    @Test
    void anUnassignedDetentIsAbsentRatherThanANoOp() {
        ActionBinding binding = ActionBinding.pressButton(2, ControlAction.ARM);

        assertTrue(binding.actionAt(SwitchPosition.LOW).isEmpty());
        assertEquals(ControlAction.ARM, binding.actionAt(SwitchPosition.HIGH).orElseThrow().action());
    }

    @Test
    void anAxisCannotFireAnAction() {
        assertThrows(IllegalArgumentException.class,
                () -> new ActionBinding(ControlBinding.Source.AXIS, ControlInputKind.AXIS, 0,
                        List.of(PositionAction.of(SwitchPosition.HIGH, ControlAction.ARM))));
    }

    @Test
    void aButtonCannotBeAThreePositionSwitch() {
        assertThrows(IllegalArgumentException.class,
                () -> new ActionBinding(ControlBinding.Source.BUTTON, ControlInputKind.SWITCH_3, 0,
                        List.of(PositionAction.of(SwitchPosition.HIGH, ControlAction.ARM))));
    }

    @Test
    void aTwoPositionSwitchHasNoMiddleToBindTo() {
        assertThrows(IllegalArgumentException.class,
                () -> new ActionBinding(ControlBinding.Source.AXIS, ControlInputKind.SWITCH_2, 0,
                        List.of(PositionAction.of(SwitchPosition.MIDDLE, ControlAction.ARM))));
    }

    @Test
    void rejectsTwoActionsOnOnePosition() {
        assertThrows(IllegalArgumentException.class,
                () -> new ActionBinding(ControlBinding.Source.BUTTON, ControlInputKind.BUTTON, 0,
                        List.of(PositionAction.of(SwitchPosition.HIGH, ControlAction.ARM),
                                PositionAction.of(SwitchPosition.HIGH, ControlAction.DISARM))));
    }

    @Test
    void rejectsEmptyAndNegativeIndex() {
        assertThrows(IllegalArgumentException.class,
                () -> new ActionBinding(ControlBinding.Source.BUTTON, ControlInputKind.BUTTON, 0, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ActionBinding(ControlBinding.Source.BUTTON, ControlInputKind.BUTTON, -1,
                        List.of(PositionAction.of(SwitchPosition.HIGH, ControlAction.ARM))));
    }

    @Test
    void anActionThatNeedsAParameterRefusesToBeBoundWithout() {
        assertThrows(IllegalArgumentException.class,
                () -> PositionAction.of(SwitchPosition.HIGH, ControlAction.SET_MODE));
        assertThrows(IllegalArgumentException.class,
                () -> new PositionAction(SwitchPosition.HIGH, ControlAction.AUX_FUNCTION, "  "));
    }

    @Test
    void anActionThatTakesNoParameterRefusesOne() {
        assertThrows(IllegalArgumentException.class,
                () -> new PositionAction(SwitchPosition.HIGH, ControlAction.ARM, "Loiter"));
    }

    @Test
    void anAuxFunctionParameterMustBeAnArdupilotOptionNumber() {
        assertEquals(46, new PositionAction(SwitchPosition.HIGH, ControlAction.AUX_FUNCTION, "46")
                .auxFunctionNumber());
        assertThrows(IllegalArgumentException.class,
                () -> new PositionAction(SwitchPosition.HIGH, ControlAction.AUX_FUNCTION, "RC override"));
        assertThrows(IllegalArgumentException.class,
                () -> new PositionAction(SwitchPosition.HIGH, ControlAction.AUX_FUNCTION, "-1"));
        assertThrows(IllegalArgumentException.class,
                () -> new PositionAction(SwitchPosition.HIGH, ControlAction.AUX_FUNCTION, "9999"));
    }

    @Test
    void anActionMapFindsBindingsByPhysicalControl() {
        ActionMap map = new ActionMap(List.of(ActionBinding.pressButton(3, ControlAction.EMERGENCY_STOP)));

        assertEquals(ControlInputKind.BUTTON,
                map.find(ControlBinding.Source.BUTTON, 3).orElseThrow().kind());
        assertTrue(map.find(ControlBinding.Source.AXIS, 3).isEmpty());
        assertTrue(ActionMap.empty().isEmpty());
    }
}
