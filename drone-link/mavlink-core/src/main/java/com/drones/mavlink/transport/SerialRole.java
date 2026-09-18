package com.drones.mavlink.transport;

/**
 * What a {@link CarrierKind#SERIAL} link is <i>for</i>, decided by the registering carrier
 * (drone-link/carrier-serial's {@code SerialPortEnumerator}) from its own allow/deny/bench
 * configuration — never guessed from a USB vendor/product id (LINK-PAIRING-PLAN.md §3.2 🔒 frozen
 * decision).
 *
 * <p>{@link #NONE} is this module's {@code Identity.NONE}/{@code Custody.NONE} sentinel
 * convention applied to serial role: every {@link CarrierKind#UDP} {@link LinkDescriptor} carries
 * {@code NONE} here, never {@code null} — a serial-only field must not force every UDP call site
 * to reason about a dimension that does not apply to it.
 */
public enum SerialRole {
    /** Not a serial link (paired with {@link CarrierKind#UDP} in every {@link LinkDescriptor}). */
    NONE,
    /** A ground radio (e.g. a SiK/ELRS-class telemetry radio) — eligible for auto-election. */
    GROUND_RADIO,
    /** A bench/USB debug cable — registers at priority {@code 0} and is never auto-elected. */
    BENCH
}
