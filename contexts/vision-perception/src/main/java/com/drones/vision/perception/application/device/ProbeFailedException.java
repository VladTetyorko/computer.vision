package com.drones.vision.perception.application.device;

/**
 * Thrown by {@link DefaultProbeService} when the descriptor's protocol is recognized (an adapter
 * exists) but the actual connection attempt failed, timed out, or ended before a frame arrived.
 *
 * <p>Lives here rather than in {@code warehouse} because its thrower does: W1.2 (rule C1) filed
 * this in warehouse on the principle that an exception belongs to the context that throws it, and
 * W1.6d (docs/plans/active/DOMAIN-SEPARATION-W1.md §15) moved {@link DefaultProbeService} itself to
 * perception, so the same rule carries this exception along with it. It did not drift — it followed
 * its thrower.
 *
 * <p>Deliberately distinct from
 * {@link com.drones.vision.perception.application.stream.UnsupportedProtocolException} (an unrecognized
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
