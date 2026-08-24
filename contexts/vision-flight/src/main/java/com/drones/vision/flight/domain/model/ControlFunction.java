package com.drones.vision.flight.domain.model;

/**
 * What one {@link ControlBinding} actually <em>does</em> to the vehicle — the meaning behind an RC
 * channel number (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §2 P5).
 *
 * <p>Exists because the channel number alone does not carry the meaning: RC1 is roll on a copter
 * and steering on a rover. Before this type, vision-api's WebSocket handler inferred the label from
 * {@code (source, rcChannel)} with a hardcoded {@code case 1 -> "Roll"} — a drone assumption living
 * in the presentation layer, where nothing could ever correct it for a car. The binding now carries
 * its own meaning and every layer above simply reads it.
 *
 * <p>{@link #STEERING} and {@link #ROLL} are separate values on purpose even though both drive RC1
 * and both are centred: an operator looking at a control surface labelled "Roll" on a ground vehicle
 * has been told something false about what the machine will do.
 */
public enum ControlFunction {

    /** Bank left/right (aileron). Centred. */
    ROLL("Roll"),

    /** Nose up/down (elevator). Centred. */
    PITCH("Pitch"),

    /** Motor output. Travel is per-vehicle — see {@link ControlBinding#travel()}. */
    THROTTLE("Throttle"),

    /** Rotate about the vertical axis (rudder). Centred. */
    YAW("Yaw"),

    /** Turn left/right on the ground or water. Centred. The ground-vehicle counterpart of {@link #ROLL}. */
    STEERING("Steering"),

    AUX_1("Aux 1"),
    AUX_2("Aux 2"),
    AUX_3("Aux 3"),
    AUX_4("Aux 4");

    private final String label;

    ControlFunction(String label) {
        this.label = label;
    }

    /**
     * A short human label for this function, for a driving adapter to display beside the control.
     *
     * @return the display label, e.g. {@code "Throttle"}
     */
    public String label() {
        return label;
    }
}
