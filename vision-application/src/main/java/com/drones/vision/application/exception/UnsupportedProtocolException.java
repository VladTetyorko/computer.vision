package com.drones.vision.application.exception;

import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.application.pipeline.VideoSourceRegistry;

/**
 * Thrown by {@link VideoSourceRegistry#sourceFor(StreamDescriptor)} when no
 * registered {@code VideoSourcePort} supports a given descriptor's protocol.
 *
 * <p>Unchecked because this represents a configuration/wiring problem (an
 * adapter for the protocol was never registered) rather than a recoverable
 * runtime condition the caller is expected to handle per call site.
 */
public final class UnsupportedProtocolException extends RuntimeException {

    private final String protocol;

    /**
     * @param protocol the unsupported protocol key (e.g. {@code "rtsp"})
     */
    public UnsupportedProtocolException(String protocol) {
        super("No VideoSourcePort registered for protocol: " + protocol);
        this.protocol = protocol;
    }

    /**
     * @return the protocol key that no adapter supported
     */
    public String protocol() {
        return protocol;
    }
}
