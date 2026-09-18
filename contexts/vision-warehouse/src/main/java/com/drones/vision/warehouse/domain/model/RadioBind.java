package com.drones.vision.warehouse.domain.model;

import java.util.Map;
import java.util.Objects;

/**
 * Radio-layer binding facts for a {@link Pairing} — reserved for a future radio-bind handshake
 * (docs/plans/active/LINK-PAIRING-PLAN.md §3.3); nothing writes non-{@link #NONE} values yet.
 *
 * <p>Same {@code NONE} sentinel convention as {@link Identity#NONE}/{@link Custody#NONE}: a
 * compound value object that is "not yet known" is never {@code null}, it is the empty instance.
 *
 * @param attributes free-form radio-bind facts, defensively copied to an immutable map
 */
public record RadioBind(Map<String, String> attributes) {

    /** No radio-bind facts recorded — every pairing starts here. */
    public static final RadioBind NONE = new RadioBind(Map.of());

    public RadioBind {
        Objects.requireNonNull(attributes, "RadioBind attributes must not be null");
        attributes = Map.copyOf(attributes);
    }
}
