package com.drones.vision.flight.domain.model;

/**
 * Why a link's {@link com.drones.vision.flight.domain.port.ManualControlLink#vehicleKind()} is
 * {@link VehicleKind#UNKNOWN} — three different facts an operator can act on three different ways
 * (docs/plans/active/FLEET-RADIO-PLAN.md R2).
 *
 * <p>{@link VehicleKind} itself stays a pure control-shape taxonomy — its own javadoc: "two vehicles
 * share a kind exactly when they share a control shape" — and gains no fourth constant for this,
 * because all three reasons below share the identical (nonexistent) control shape: there is nothing
 * to split a stick layout on here, only something to tell an operator. This enum answers a different
 * question than {@code VehicleKind} does — not "how do I drive it" but "why can't I".
 *
 * <p>Refusing to engage is correct for all three, but a refusal that reads the same for all three
 * tells the operator nothing: "we cannot identify this" invites them to look at the vehicle and pick
 * a kind themselves; "this is a real airframe we do not support" tells them no amount of looking will
 * help; "this is not a vehicle at all" tells them they pointed manual control at the wrong device
 * entirely. {@code DefaultManualControlService#engage} carries this distinction into the refusal
 * message it throws with; {@code ManualControlWebSocketHandler} carries it onto the wire in the
 * {@code denied} frame's free-text {@code reason} (see that class's own javadoc for why the wire
 * {@code code} itself stays a single new value rather than three).
 */
public enum UnidentifiedReason {

    /**
     * The vehicle reported exactly what it is, and it is a real, recognized airframe — just not one
     * this platform has chosen to support flying or driving (for example an airship, a free balloon,
     * or a rocket; see {@code com.drones.mavlink.VehicleClass#UNSUPPORTED_VEHICLE}). Distinct from
     * {@link #NEVER_IDENTIFIED}: nothing is missing here, there is simply no control profile for this
     * airframe, and none is coming from more looking.
     */
    UNSUPPORTED_VEHICLE,

    /**
     * What is heartbeating on this link is not a vehicle at all — an instrument or ground system
     * (a gimbal, a companion ground control station, an ADS-B transponder, a battery, ...; see
     * {@code com.drones.mavlink.VehicleClass#NOT_A_VEHICLE}). Refusing to engage it is correct, but
     * for a reason that has nothing to do with classification difficulty: there was never anything
     * here to fly or drive, and "we refused because a gimbal is on your link" must not read like the
     * other two causes or it looks like this platform simply failed to recognize a real vehicle.
     */
    NOT_A_VEHICLE,

    /**
     * A {@code MAV_TYPE} number this platform has genuinely never seen. The only one of the three
     * that means "we have no information at all" — an operator who can see the vehicle may still be
     * able to say what it is, even though this platform cannot. An implementation unable to
     * distinguish the three causes must answer this one, never a guess at the other two.
     */
    NEVER_IDENTIFIED
}
