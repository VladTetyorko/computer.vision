package com.drones.vision.warehouse.domain.model;

import java.util.Arrays;
import java.util.Objects;

/**
 * A vehicle's 32-byte pairing key, minted once by {@code PairingService} when a device is first
 * paired (docs/plans/active/LINK-PAIRING-PLAN.md §3.3) — the shared secret a future radio-bind
 * handshake authenticates against.
 *
 * <p>A {@code byte[]} component breaks record {@code equals}/{@code hashCode} (reference
 * equality), so both are overridden by value here, same reasoning as every id record wrapping a
 * primitive. Key material is never printed: {@link #toString()} always redacts.
 *
 * @param value exactly 32 bytes, defensively copied on the way in and out
 */
public record VehicleKey(byte[] value) {

    /** MAVLink/pairing key length in bytes — not tunable, it is this type's whole contract. */
    private static final int LENGTH = 32;

    public VehicleKey {
        Objects.requireNonNull(value, "VehicleKey value must not be null");
        if (value.length != LENGTH) {
            throw new IllegalArgumentException("VehicleKey must be " + LENGTH + " bytes, got " + value.length);
        }
        value = Arrays.copyOf(value, value.length);
    }

    @Override
    public byte[] value() {
        return Arrays.copyOf(value, value.length);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VehicleKey other)) {
            return false;
        }
        return Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "VehicleKey[**redacted**]";
    }
}
