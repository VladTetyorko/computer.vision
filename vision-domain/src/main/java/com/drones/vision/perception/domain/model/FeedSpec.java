package com.drones.vision.perception.domain.model;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/**
 * Describes a local media source to transmit over a real wire protocol (see
 * {@code com.drones.vision.perception.domain.port.FeedTransmitterPort} and
 * {@code docs/main/CYCLES-PLAN.md} §0 for why this exists: simulation
 * infrastructure, not egress).
 *
 * <p>{@code protocol} is a lower-case key (e.g. {@code "rtsp"}) used by
 * {@code FeedTransmitterPort.supports(FeedSpec)} to select the adapter that
 * knows how to transmit this spec — mirrors {@link StreamDescriptor#protocol()}'s
 * role for the receive side. {@code source} is the local media to read from
 * (typically a {@code file:} URI). {@code options} carries adapter-specific
 * parameters (e.g. {@code loop}) as an immutable map so a {@code FeedSpec}
 * instance can never be mutated after construction.
 *
 * @param protocol the lower-case protocol key selecting the transmitter adapter; must not be blank
 * @param source   the local media source to transmit; must not be {@code null}
 * @param options  adapter-specific parameters; defensively copied to an immutable map; must not be {@code null}
 */
public record FeedSpec(String protocol, URI source, Map<String, String> options) {

    public FeedSpec {
        if (protocol == null || protocol.isBlank()) {
            throw new IllegalArgumentException("FeedSpec protocol must not be blank");
        }
        if (!protocol.equals(protocol.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("FeedSpec protocol must be lower-case: " + protocol);
        }
        if (source == null) {
            throw new IllegalArgumentException("FeedSpec source must not be null");
        }
        if (options == null) {
            throw new IllegalArgumentException("FeedSpec options must not be null");
        }
        options = Map.copyOf(options);
    }
}
