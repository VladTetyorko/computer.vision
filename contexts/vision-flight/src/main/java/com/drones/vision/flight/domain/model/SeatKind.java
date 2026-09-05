package com.drones.vision.flight.domain.model;

/**
 * Which of an asset's two seats is held (docs/plans/active/CREW-CONTROL-PLAN.md &sect;3.1).
 *
 * <p>Exactly two values, and deliberately <b>unordered in meaning</b>: unlike, say, {@code
 * SwitchPosition}, {@link #ordinal()} carries no semantics here — nothing in this codebase may
 * switch, compare, or rank on this enum's declaration order. Each value's meaning comes only from
 * its name: {@link #FLIGHT} guards the verbs that command the aircraft (arm/disarm/mode/RTH/
 * emergency-stop/aux, RC engage, session engage/disengage); {@link #CAMERA} guards the verbs that
 * command the camera/CV surface (stream start/stop, stream config including the target lock).
 */
public enum SeatKind {

    /** Guards the verbs that command the aircraft itself. Never lent — see &sect;3.2 rule 4. */
    FLIGHT,

    /** Guards the verbs that command the camera/CV surface. Lent, and always reclaimable by the
     * {@link #FLIGHT} seat's holder — see &sect;3.2 rule 3. */
    CAMERA
}
