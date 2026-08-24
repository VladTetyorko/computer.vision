package com.drones.vision.flight.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-vehicle stick layouts (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §3.2).
 *
 * <p>The regression these guard is the one the profiles exist for: every axis, throttle included,
 * used to rest at 1500&nbsp;µs. That is <em>stop</em> on a rover and roughly <em>half power</em> on a
 * multirotor, so a released stick on a copter was not idle. {@link #releasedSticksAreIdleOnACopter()}
 * and {@link #releasedSticksAreStopOnARover()} are the two halves of that, stated in microseconds.
 */
class ControlProfileTest {

    /** No input at all — what the vehicle is commanded the instant a session engages. */
    private static RcChannels atRest(ControlProfile profile) {
        return profile.channelMap().apply(List.of(), List.of());
    }

    private static ControlBinding bindingFor(ControlProfile profile, ControlFunction function) {
        return profile.channelMap().bindings().stream()
                .filter(binding -> binding.function() == function)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        profile.kind() + " profile has no " + function + " binding"));
    }

    // --- The headline: what a released stick commands ------------------------------------------

    @Test
    void releasedSticksAreIdleOnACopter() {
        ControlProfile copter = ControlProfile.forKind(VehicleKind.COPTER);

        RcChannels rest = atRest(copter);

        assertEquals(RcChannels.MIN_MICROS, rest.channel(3), "a copter's throttle must rest at idle, not half power");
        assertEquals(ControlBinding.CENTER_MICROS, rest.channel(1));
        assertEquals(ControlBinding.CENTER_MICROS, rest.channel(2));
        assertEquals(ControlBinding.CENTER_MICROS, rest.channel(4));
    }

    @Test
    void releasedSticksAreStopOnARover() {
        ControlProfile rover = ControlProfile.forKind(VehicleKind.ROVER);

        RcChannels rest = atRest(rover);

        assertEquals(ControlBinding.CENTER_MICROS, rest.channel(3), "a rover's throttle must rest at stop, not reverse");
        assertEquals(ControlBinding.CENTER_MICROS, rest.channel(1));
    }

    @Test
    void aCopterThrottleTravelsOneWayAndARoversTravelsBoth() {
        ControlBinding copterThrottle = bindingFor(ControlProfile.forKind(VehicleKind.COPTER), ControlFunction.THROTTLE);
        ControlBinding roverThrottle = bindingFor(ControlProfile.forKind(VehicleKind.ROVER), ControlFunction.THROTTLE);

        assertEquals(ControlBinding.Travel.UNIDIRECTIONAL, copterThrottle.travel());
        assertEquals(ControlBinding.Travel.CENTERED, roverThrottle.travel());

        // 0..100 on a copter: rest is idle, full stick is full power, and a stray negative pins at idle.
        assertEquals(1000, copterThrottle.toMicros(0.0));
        assertEquals(1500, copterThrottle.toMicros(0.5));
        assertEquals(2000, copterThrottle.toMicros(1.0));
        assertEquals(1000, copterThrottle.toMicros(-1.0));

        // 50-0 reverse / 50-100 forward on a rover.
        assertEquals(1500, roverThrottle.toMicros(0.0));
        assertEquals(2000, roverThrottle.toMicros(1.0));
        assertEquals(1000, roverThrottle.toMicros(-1.0));
    }

    // --- Shape ---------------------------------------------------------------------------------

    @Test
    void everyKindHasAProfileAndNoneBindsAnAuxChannel() {
        for (VehicleKind kind : VehicleKind.values()) {
            ControlProfile profile = ControlProfile.forKind(kind);

            assertEquals(kind, profile.kind());
            assertTrue(profile.channelMap().bindings().stream()
                            .allMatch(binding -> binding.source() == ControlBinding.Source.AXIS),
                    kind + " must bind no buttons: arm/mode go through the flight-command surface (D6)");
            assertTrue(profile.channelMap().bindings().stream().allMatch(binding -> binding.rcChannel() <= 4),
                    kind + " must not reach past channel 4");
        }
    }

    @Test
    void aRoverBindsSteeringAndThrottleOnlyAndIgnoresPitchAndYaw() {
        ControlProfile rover = ControlProfile.forKind(VehicleKind.ROVER);

        assertEquals(2, rover.channelMap().bindings().size());
        assertEquals(1, bindingFor(rover, ControlFunction.STEERING).rcChannel());
        assertEquals(3, bindingFor(rover, ControlFunction.THROTTLE).rcChannel());

        // The frame runs to the highest bound channel (3) and no further: channel 2 is explicitly
        // IGNORE, and channels 4..8 are simply absent -- the mavlink adapter pads a short frame with
        // IGNORE too (`com.drones.mavlink.service.RcChannels#channelOrIgnore`). Either way the car is
        // never sent a fabricated 1500 for a control it does not have.
        RcChannels rest = atRest(rover);
        assertEquals(3, rest.microsByChannel().size());
        assertEquals(RcChannels.IGNORE, rest.channel(2), "a car has no pitch -- say IGNORE, do not fabricate 1500");
    }

    @Test
    void aRoverIsSteeredNotRolled() {
        ControlProfile rover = ControlProfile.forKind(VehicleKind.ROVER);

        assertEquals(ControlFunction.STEERING, rover.channelMap().bindings().get(0).function());
        assertEquals("Steering", rover.channelMap().bindings().get(0).function().label());
    }

    @Test
    void aPlaneSharesTheCoptersStickLayout() {
        assertEquals(ControlProfile.forKind(VehicleKind.COPTER).channelMap(),
                ControlProfile.forKind(VehicleKind.PLANE).channelMap());
        assertNotEquals(ControlProfile.forKind(VehicleKind.COPTER).displayName(),
                ControlProfile.forKind(VehicleKind.PLANE).displayName());
    }

    @Test
    void anUnknownVehicleKeepsTheHistoricalCentredMapRatherThanGuessing() {
        ControlProfile unknown = ControlProfile.forKind(VehicleKind.UNKNOWN);

        RcChannels rest = atRest(unknown);

        assertEquals(List.of(1500, 1500, 1500, 1500), rest.microsByChannel());
        assertEquals(ControlBinding.Travel.CENTERED, bindingFor(unknown, ControlFunction.THROTTLE).travel());
    }

    @Test
    void everyProfileCarriesAPasteableChannelOrderCode() {
        assertEquals("AETR", ControlProfile.forKind(VehicleKind.COPTER).code());
        assertEquals("S-T-", ControlProfile.forKind(VehicleKind.ROVER).code());
    }

    @Test
    void rejectsANullKind() {
        assertThrows(IllegalArgumentException.class, () -> ControlProfile.forKind(null));
    }

    // --- CONTROLLER-SETUP-CONTEXT.md C1/C6/C7: identity, editing, and one-control-one-job ---

    @Test
    void everyBuiltInHasAStableDerivedIdentityAndBindsNoActions() {
        for (VehicleKind kind : VehicleKind.values()) {
            ControlProfile profile = ControlProfile.forKind(kind);

            assertEquals(ControlProfileId.builtIn(kind), profile.id());
            assertTrue(profile.isBuiltIn());
            assertTrue(profile.actionMap().isEmpty(),
                    kind + " must bind no actions: which button arms an unknown gamepad is not ours to guess");
        }
    }

    @Test
    void aSavedCopyIsNoLongerBuiltIn() {
        ControlProfileId id = ControlProfileId.random();

        ControlProfile copy = ControlProfile.forKind(VehicleKind.ROVER).copyAs(id, "Bench rover");

        assertEquals(id, copy.id());
        assertEquals("Bench rover", copy.displayName());
        assertEquals(VehicleKind.ROVER, copy.kind());
        assertFalse(copy.isBuiltIn());
    }

    @Test
    void oneControlCannotBothFlyAChannelAndFireACommand() {
        ControlProfile rover = ControlProfile.forKind(VehicleKind.ROVER);
        // Axis 0 already steers this rover.
        ActionMap clash = new ActionMap(List.of(new ActionBinding(ControlBinding.Source.AXIS,
                ControlInputKind.SWITCH_2, 0, List.of(PositionAction.of(SwitchPosition.HIGH, ControlAction.ARM)))));

        assertThrows(IllegalArgumentException.class, () -> rover.withBindings(rover.channelMap(), clash));
    }

    @Test
    void twoBindingsCannotDriveOneRcChannel() {
        ChannelMap doubled = new ChannelMap(List.of(
                ControlBinding.centeredAxis(ControlFunction.ROLL, 0, 1),
                ControlBinding.centeredAxis(ControlFunction.STEERING, 1, 1)));

        assertThrows(IllegalArgumentException.class,
                () -> ControlProfile.forKind(VehicleKind.COPTER).withBindings(doubled, ActionMap.empty()));
    }

    @Test
    void aSwitchDrivingAChannelSnapsToItsDetents() {
        ControlBinding aux = ControlBinding.switched(ControlFunction.AUX_1, ControlBinding.Source.AXIS,
                ControlInputKind.SWITCH_3, 5, 6);

        assertEquals(RcChannels.MIN_MICROS, aux.toMicros(-1.0));
        assertEquals(ControlBinding.CENTER_MICROS, aux.toMicros(0.0));
        assertEquals(RcChannels.MAX_MICROS, aux.toMicros(1.0));
        // Nothing in between leaks through: 0.9 is still just "high".
        assertEquals(RcChannels.MAX_MICROS, aux.toMicros(0.9));
    }
}
