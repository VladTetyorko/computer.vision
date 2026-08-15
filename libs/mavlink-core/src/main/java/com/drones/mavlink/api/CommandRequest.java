package com.drones.mavlink.api;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * A command to submit through {@link CommandGateway}, as a self-describing value (plan §5.1 B1) — no
 * {@code Device}, no live references, serializable to JSON/Avro exactly as declared. A Kafka
 * consumer, a REST controller and an in-process caller all build the identical record; only the
 * driving adapter around {@link CommandGateway} differs.
 *
 * @param vehicleKey    an opaque string a {@link VehicleKeyResolver} maps to a {@link com.drones.mavlink.PeerId}
 *                       — this module never interprets it itself (plan §5.1: "keeps the module
 *                       domain-free and routable")
 * @param kind           names a high-level command (e.g. {@code "ARM"}, {@code "RTL"}) — see
 *                       {@link DefaultCommandGateway}'s own kind→command table for the full,
 *                       currently-supported set
 * @param params         the positional {@code param1}..{@code param7} escape hatch for anything not
 *                       named by {@code kind}, or extra positional data a named {@code kind} itself
 *                       needs (e.g. {@code SET_MODE}'s already-resolved numeric mode); size 0..7
 * @param correlationId  caller-supplied — the one correlation mechanism (plan §5.1 B2); never keyed
 *                       on object identity, since a broker reply may return on another node entirely
 * @param deadline       how long the caller is willing to wait for a terminal outcome
 * @param force          plumbed through to commands with a force/override semantic (e.g. {@code ARM})
 */
public record CommandRequest(String vehicleKey, String kind, List<Double> params, String correlationId,
                              Duration deadline, boolean force) {

    public CommandRequest {
        requireNonBlank(vehicleKey, "vehicleKey");
        requireNonBlank(kind, "kind");
        params = params == null ? List.of() : List.copyOf(params);
        if (params.size() > 7) {
            throw new IllegalArgumentException("params must have at most 7 entries, got " + params.size());
        }
        requireNonBlank(correlationId, "correlationId");
        Objects.requireNonNull(deadline, "deadline");
        if (deadline.isZero() || deadline.isNegative()) {
            throw new IllegalArgumentException("deadline must be positive, got " + deadline);
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
