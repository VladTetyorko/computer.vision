package com.drones.vision.api.exception;

import com.drones.vision.api.proxy.HlsProxyController;

/**
 * Thrown by {@link HlsProxyController} when the configured upstream HLS
 * server (mediamtx) cannot be reached at all — connection refused, DNS
 * failure, timeout, or a broken redirect chain. Mapped to {@code 502} by
 * {@link ApiExceptionHandler}.
 *
 * <p>Distinct from a normal non-2xx response actually received from
 * upstream (e.g. {@code 404} for a segment that isn't ready yet), which
 * {@link HlsProxyController} passes through verbatim rather than treating
 * as an error.
 */
public class HlsUpstreamUnavailableException extends RuntimeException {

    public HlsUpstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
