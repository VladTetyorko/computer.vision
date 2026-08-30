package com.drones.vision.warehouse.domain.model;

/**
 * The physical-world identity facts for an {@link Asset}: what it is, not what it is used for.
 *
 * <p>Every field is optional — an asset may be registered before its serial number is known, or
 * may never carry one (a fabricated rig has no manufacturer plate) — so every component is
 * nullable and blank strings normalize to {@code null} rather than being treated as present.
 * {@link #NONE} is the value every asset starts with; {@link Asset#register} defaults to it unless
 * the caller already knows these facts at creation time.
 *
 * @param serialNumber the manufacturer's serial number, or {@code null} if unknown
 * @param make         the manufacturer, or {@code null} if unknown
 * @param model        the model name/number, or {@code null} if unknown
 * @param registration a regulatory registration mark (e.g. a tail number), or {@code null} if none
 */
public record Identity(String serialNumber, String make, String model, String registration) {

    /** No identity facts recorded — every asset starts here. */
    public static final Identity NONE = new Identity(null, null, null, null);

    public Identity {
        serialNumber = blankToNull(serialNumber);
        make = blankToNull(make);
        model = blankToNull(model);
        registration = blankToNull(registration);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
