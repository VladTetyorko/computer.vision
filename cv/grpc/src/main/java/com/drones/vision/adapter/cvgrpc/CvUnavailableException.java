package com.drones.vision.adapter.cvgrpc;

/**
 * Thrown by {@link GrpcDetectionPort#detect} when a {@link CvChannelSupervisor}'s connectivity gate
 * is closed — cv-service is already known to be unreachable, so this call fails fast instead of
 * opening a session or spending CPU encoding a frame that would only be thrown away (see {@link
 * CvChannelSupervisor}'s class javadoc for the gate's state machine).
 *
 * <p><b>Stackless by design</b>: this is constructed on the hot detection path, potentially once per
 * gated {@code detect()} call, for a condition that is already fully described by its message. A
 * stack trace here is pure noise — during a real outage every rejected probe would otherwise pay the
 * (non-trivial) cost of capturing one for no diagnostic benefit, since every capture points at the
 * same line. {@link RuntimeException} exposes a {@code (message, cause, enableSuppression,
 * writableStackTrace)} constructor that skips capture entirely, but {@link IllegalStateException}
 * itself does not re-declare it — a subclass can only chain to a constructor its immediate superclass
 * actually declares, and only {@code (String)}/{@code (String, Throwable)}/{@code (Throwable)}/{@code
 * ()} exist there. This class gets the same outcome the other way: {@link #fillInStackTrace()} is
 * overridden to skip capture, the standard technique for a stackless exception whose supertype
 * doesn't expose the writable-stack-trace constructor.
 *
 * <p>The message names the cv-service authority, the channel's {@link io.grpc.ConnectivityState},
 * how long the current outage has lasted, and how many reconnect attempts the supervisor has made so
 * far (built by {@link CvChannelSupervisor#describe()}) — {@code StreamPipeline#describeFailure}
 * (vision-perception) uses {@link Throwable#getMessage()} verbatim for the {@code PIPELINE_ERROR}
 * event that actually reaches an operator, so this message is this exception's entire public
 * contract; there is no other field to inspect.
 */
public final class CvUnavailableException extends IllegalStateException {

    /**
     * @param message a human-readable description naming the authority, connectivity state, outage
     *                 duration, and reconnect attempt count — see {@link CvChannelSupervisor#describe()}
     */
    public CvUnavailableException(String message) {
        super(message);
    }

    /** No-op: skips stack-trace capture entirely — see class javadoc. */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
