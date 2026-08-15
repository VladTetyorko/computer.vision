package com.drones.mavlink.api;

import java.time.Instant;
import java.util.Objects;

/**
 * A {@link CommandGateway#submit} result — the one delivery mode is a {@code CompletionStage}
 * in-process; the identical record published to a reply topic is another (plan §5.1 B2). Never
 * thrown as an exception: every terminal state {@link CommandGateway#submit} can reach, including
 * "vehicle unreachable" and "this is a duplicate," is a value here, not a fault.
 *
 * @param correlationId the caller-supplied id from the originating {@link CommandRequest}
 * @param resultCode    a status-specific extra code (e.g. a MAVLink {@code MAV_RESULT}'s own
 *                       {@code resultParam2}); {@code 0} when not meaningful for {@code status}
 * @param detail         a short human-readable elaboration (e.g. the raw protocol result name);
 *                       never {@code null}, may be blank
 */
public record CommandOutcome(String correlationId, Status status, int resultCode, String detail, Instant at) {

    public CommandOutcome {
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(at, "at");
    }

    public enum Status {
        ACCEPTED, IN_PROGRESS, DENIED, NO_ACK, UNSUPPORTED, DUPLICATE, UNREACHABLE
    }
}
