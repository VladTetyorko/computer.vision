package com.drones.vision.flight.domain.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The stick layout for one kind of machine — which control drives which RC channel, and, critically,
 * <b>where each control rests</b> (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §2 P2/P3).
 *
 * <h2>The defect this type exists to close</h2>
 * Manual control used to relay one frozen, airframe-blind map in which <em>every</em> axis, throttle
 * included, rested at 1500&nbsp;µs. On a rover that is correct by luck — 1500&nbsp;µs is stop, below
 * it is reverse. On a multirotor the same number is roughly <b>half throttle</b>: a released stick
 * was not idle, so engaging manual control on an armed copter commanded half power on the first
 * frame. One map cannot be right for both, because the two machines are not the same machine.
 *
 * <p>{@link #forKind} is total: every {@link VehicleKind}, {@link VehicleKind#UNKNOWN} included, has
 * a profile, so a session can never end up choosing a map by accident.
 *
 * <h2>What is deliberately absent</h2>
 * No profile binds an aux channel. Arm, disarm and mode select travel over the flight-command REST
 * surface instead, per ArduPilot's own advice not to let a joystick own the mode or aux channels
 * (docs/plans/active/OPERATOR-CONTROL-CONTEXT.md D6, finding S3) — the previous default map bound
 * buttons 0..3 to channels 5..8 against exactly that advice.
 *
 * <p>A profile also binds only the channels its airframe actually has: a rover's map is silent about
 * channels 2 and 4, so {@link ChannelMap#apply} sends {@link RcChannels#IGNORE} for them rather than
 * a fabricated centred value a reader could mistake for real intent (§2 P6).
 *
 * <h2>Built-in versus saved</h2>
 * {@link #forKind} still answers for every {@link VehicleKind} — that totality is a safety property,
 * not a convenience. What is new is that a profile now has an <em>identity</em> and an {@link
 * ActionMap}, so an operator can save an edited copy and bind their own switches
 * (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md decisions C6/C7). A built-in profile's id is
 * derived from its kind ({@link ControlProfileId#builtIn}) rather than stored, so the fallback keeps
 * working on a server where nobody has ever saved anything.
 *
 * @param id          this profile's identity — {@link ControlProfileId#builtIn(VehicleKind)} for the
 *                    platform's own profiles, a random id for one an operator saved
 * @param kind        the machine this profile is for
 * @param code        a short, pasteable channel-order code in the style INAV uses for its own axis
 *                    order ({@code AETR1234}) — one letter per channel 1..4, {@code -} where the
 *                    profile binds nothing (docs/plans/active/OPERATOR-CONTROL-CONTEXT.md finding S7).
 *                    A convention made visible beats a convention left invisible
 * @param displayName what to call this vehicle in front of an operator
 * @param channelMap  the axis/switch bindings that drive RC channels, streamed at the link rate
 * @param actionMap   the button/switch bindings that fire commands, dispatched one-shot by whichever
 *                    driving adapter owns the physical input (decision C3). Empty on every built-in:
 *                    guessing which button of an unknown gamepad should arm a vehicle is precisely
 *                    the wrong kind of helpful
 */
public record ControlProfile(ControlProfileId id, VehicleKind kind, String code, String displayName,
                              ChannelMap channelMap, ActionMap actionMap) {

    /**
     * Gamepad/transmitter axis indices, in the AETR order EdgeTX reports in USB-joystick mode
     * (aileron, elevator, throttle, rudder on axes 0..3) — the order the platform's original map
     * already used, kept so a transmitter that worked before still works unchanged.
     */
    private static final int AXIS_AILERON = 0;
    private static final int AXIS_ELEVATOR = 1;
    private static final int AXIS_THROTTLE = 2;
    private static final int AXIS_RUDDER = 3;

    /** RC channels, matching ArduPilot's {@code RCMAP_*} defaults for every vehicle family. */
    private static final int CH_ROLL_OR_STEERING = 1;
    private static final int CH_PITCH = 2;
    private static final int CH_THROTTLE = 3;
    private static final int CH_YAW = 4;

    public ControlProfile {
        if (id == null) {
            throw new IllegalArgumentException("ControlProfile id must not be null");
        }
        if (kind == null) {
            throw new IllegalArgumentException("ControlProfile kind must not be null");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("ControlProfile code must not be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("ControlProfile displayName must not be blank");
        }
        if (channelMap == null) {
            throw new IllegalArgumentException("ControlProfile channelMap must not be null");
        }
        if (actionMap == null) {
            throw new IllegalArgumentException("ControlProfile actionMap must not be null");
        }
        requireEachControlBoundOnce(channelMap, actionMap);
        requireEachChannelDrivenOnce(channelMap);
    }

    /**
     * One physical control may drive a channel <em>or</em> fire commands, never both. A stick that
     * both flies channel 3 and arms the vehicle is not a configuration an operator meant to make.
     */
    private static void requireEachControlBoundOnce(ChannelMap channelMap, ActionMap actionMap) {
        Set<String> seen = new HashSet<>();
        List<String> controls = new ArrayList<>();
        for (ControlBinding binding : channelMap.bindings()) {
            controls.add(binding.source().name() + ":" + binding.sourceIndex());
        }
        for (ActionBinding binding : actionMap.bindings()) {
            controls.add(binding.source().name() + ":" + binding.sourceIndex());
        }
        for (String control : controls) {
            if (!seen.add(control)) {
                throw new IllegalArgumentException(
                        "ControlProfile binds the same physical control twice: " + control);
            }
        }
    }

    /** Two bindings driving one RC channel would race every frame, with the list order deciding. */
    private static void requireEachChannelDrivenOnce(ChannelMap channelMap) {
        Set<Integer> seen = new HashSet<>();
        for (ControlBinding binding : channelMap.bindings()) {
            if (!seen.add(binding.rcChannel())) {
                throw new IllegalArgumentException(
                        "ControlProfile binds RC channel " + binding.rcChannel() + " twice");
            }
        }
    }

    /**
     * This profile with a different set of bindings — the shape an edit takes, since a profile is a
     * value.
     *
     * @param newChannelMap the replacement channel bindings
     * @param newActionMap  the replacement action bindings
     * @return an otherwise-identical profile
     */
    public ControlProfile withBindings(ChannelMap newChannelMap, ActionMap newActionMap) {
        return new ControlProfile(id, kind, code, displayName, newChannelMap, newActionMap);
    }

    /**
     * A fresh, saveable copy of this profile under a new identity and name — what "create a profile
     * from this one" means, and the only way a saved profile ever comes into existence (C7).
     *
     * @param newId   the new identity
     * @param newName what the operator called it
     * @return the copy
     */
    public ControlProfile copyAs(ControlProfileId newId, String newName) {
        return new ControlProfile(newId, kind, code, newName, channelMap, actionMap);
    }

    /**
     * Whether this is one of the platform's own profiles, which an operator may copy but not edit or
     * delete.
     *
     * @return {@code true} if {@link #id()} is a {@link ControlProfileId#isBuiltIn() built-in} id
     */
    public boolean isBuiltIn() {
        return id.isBuiltIn();
    }

    /**
     * The profile for one kind of machine.
     *
     * @param kind the vehicle kind, as most recently heard from the vehicle itself
     * @return that kind's profile; never {@code null}
     * @throws IllegalArgumentException if {@code kind} is {@code null}
     */
    public static ControlProfile forKind(VehicleKind kind) {
        if (kind == null) {
            throw new IllegalArgumentException("ControlProfile kind must not be null");
        }
        ControlProfileId id = ControlProfileId.builtIn(kind);
        return switch (kind) {
            case COPTER -> new ControlProfile(id, kind, "AETR", "Multirotor", airborneMap(), ActionMap.empty());
            case PLANE -> new ControlProfile(id, kind, "AETR", "Fixed-wing", airborneMap(), ActionMap.empty());
            case ROVER -> new ControlProfile(id, kind, "S-T-", "Ground vehicle", roverMap(), ActionMap.empty());
            case UNKNOWN ->
                    new ControlProfile(id, kind, "AETR?", "Unrecognised vehicle", unknownMap(), ActionMap.empty());
        };
    }

    /**
     * Copter and plane: roll, pitch and yaw centred, and a <b>unidirectional</b> throttle whose rest
     * point is idle rather than half power. Physically the same stick layout on both — a plane's
     * throttle also only travels one way.
     */
    private static ChannelMap airborneMap() {
        return new ChannelMap(List.of(
                ControlBinding.centeredAxis(ControlFunction.ROLL, AXIS_AILERON, CH_ROLL_OR_STEERING),
                ControlBinding.centeredAxis(ControlFunction.PITCH, AXIS_ELEVATOR, CH_PITCH),
                ControlBinding.unidirectionalAxis(ControlFunction.THROTTLE, AXIS_THROTTLE, CH_THROTTLE),
                ControlBinding.centeredAxis(ControlFunction.YAW, AXIS_RUDDER, CH_YAW)));
    }

    /**
     * Rover and boat: steering, and a <b>centred</b> throttle — 1500&nbsp;µs is stop, below it is
     * reverse, above it is forward. Nothing is bound to channels 2 and 4: a car has no pitch and no
     * rudder, and saying so with {@link RcChannels#IGNORE} is more honest than sending it a centred
     * value for a control it does not have.
     */
    private static ChannelMap roverMap() {
        return new ChannelMap(List.of(
                ControlBinding.centeredAxis(ControlFunction.STEERING, AXIS_AILERON, CH_ROLL_OR_STEERING),
                ControlBinding.centeredAxis(ControlFunction.THROTTLE, AXIS_THROTTLE, CH_THROTTLE)));
    }

    /**
     * A vehicle that has not said what it is: the four-axis, everything-centred map this platform
     * relayed before profiles existed.
     *
     * <p>Deliberately not upgraded to a guess. A throttle resting at its minimum is idle on a copter
     * and <em>full reverse</em> on a rover, so neither travel is safe to assume; the platform keeps
     * the historical behaviour and reports {@link VehicleKind#UNKNOWN} on the wire so the operator —
     * who can see the vehicle — decides what to make of it (§2 P8).
     */
    private static ChannelMap unknownMap() {
        return new ChannelMap(List.of(
                ControlBinding.centeredAxis(ControlFunction.ROLL, AXIS_AILERON, CH_ROLL_OR_STEERING),
                ControlBinding.centeredAxis(ControlFunction.PITCH, AXIS_ELEVATOR, CH_PITCH),
                ControlBinding.centeredAxis(ControlFunction.THROTTLE, AXIS_THROTTLE, CH_THROTTLE),
                ControlBinding.centeredAxis(ControlFunction.YAW, AXIS_RUDDER, CH_YAW)));
    }
}
