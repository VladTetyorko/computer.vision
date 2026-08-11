package com.drones.vision.application.device;

/**
 * Thrown by {@link DefaultProbeService} when the descriptor's protocol is recognized (an adapter
 * exists) but the actual connection attempt failed, timed out, or ended before a frame arrived.
 *
 * <p>Deliberately distinct from
 * {@link com.drones.vision.application.stream.UnsupportedProtocolException} (an unrecognized
 * protocol — a malformed request, mapped to 400) — this exception means the request was well-formed and the
 * right adapter was found, but the device itself didn't cooperate, mapped to 422 by {@code
 * vision-api}'s {@code ApiExceptionHandler} (UX-DESIGN.md §5.1: "a device that cannot produce a
 * frame is never saved", with a specific, actionable message rather than a generic "start
 * failed").
 */
public final class ProbeFailedException extends RuntimeException {

    public ProbeFailedException(String message) {
        super(message);
    }

    public ProbeFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
