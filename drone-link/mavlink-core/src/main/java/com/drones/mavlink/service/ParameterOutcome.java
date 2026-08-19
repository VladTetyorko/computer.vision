package com.drones.mavlink.service;

import java.util.Objects;

/**
 * A {@code PARAM_REQUEST_READ} or {@code PARAM_SET} exchange's terminal result. Like
 * {@code CommandService.CommandOutcome}, a protocol-level "no" is a value here, never a thrown
 * exception — the returned future fails only for a send-level fault (an unreachable target).
 *
 * <p>{@link Status#MISMATCH} is the reason a write is a read-back and not a fire-and-forget: an
 * autopilot silently clamps an out-of-range value, and rounds a float into whatever integer width
 * the parameter actually is, without ever refusing the write. The only way to know what the vehicle
 * now holds is to compare what it echoed back, so that comparison is part of the exchange rather
 * than something each caller is trusted to remember.
 *
 * @param value  what the vehicle reported; {@code null} only for {@link Status#NO_REPLY}
 * @param detail a short human-readable elaboration — never {@code null}, may be blank
 */
public record ParameterOutcome(Status status, ParameterValue value, String detail) {

    public ParameterOutcome {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(detail, "detail");
        if (status != Status.NO_REPLY && value == null) {
            throw new IllegalArgumentException(status + " must carry the reported value");
        }
    }

    public enum Status {
        /** The vehicle answered, and (for a write) echoed back exactly what was asked for. */
        OK,
        /** The vehicle answered a write with a different value than requested — clamped, rounded, or refused. */
        MISMATCH,
        /** Nothing arrived within the timeout, across every retry. */
        NO_REPLY
    }

    public boolean ok() {
        return status == Status.OK;
    }

    static ParameterOutcome noReply(String name, java.time.Duration timeout, int attempts) {
        return new ParameterOutcome(Status.NO_REPLY, null,
                "no PARAM_VALUE for \"" + name + "\" after " + attempts + " attempt(s) of " + timeout);
    }
}
