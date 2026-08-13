package com.drones.vision.kernel;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/**
 * Protocol-agnostic description of how to obtain frames for a device's stream.
 *
 * <p>{@code protocol} is a lower-case key (e.g. {@code "rtsp"}, {@code "mjpeg"},
 * {@code "sim"}) used by {@code VideoSourceRegistry} in the application layer to
 * select the adapter that knows how to open this descriptor — it is effectively
 * a discriminator for {@code VideoSourcePort.supports(StreamDescriptor)}.
 * {@code options} carries adapter-specific parameters (e.g. resolution, fps)
 * as an immutable map so a {@code StreamDescriptor} instance can never be
 * mutated after construction.
 *
 * @param protocol the lower-case protocol key selecting the adapter; must not be blank
 * @param uri      the resource locator interpreted by the selected adapter
 * @param options  adapter-specific parameters; defensively copied to an immutable map
 */
public record StreamDescriptor(String protocol, URI uri, Map<String, String> options) {

    public StreamDescriptor {
        if (protocol == null || protocol.isBlank()) {
            throw new IllegalArgumentException("StreamDescriptor protocol must not be blank");
        }
        if (!protocol.equals(protocol.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("StreamDescriptor protocol must be lower-case: " + protocol);
        }
        if (uri == null) {
            throw new IllegalArgumentException("StreamDescriptor uri must not be null");
        }
        if (options == null) {
            throw new IllegalArgumentException("StreamDescriptor options must not be null");
        }
        options = Map.copyOf(options);
    }
}
