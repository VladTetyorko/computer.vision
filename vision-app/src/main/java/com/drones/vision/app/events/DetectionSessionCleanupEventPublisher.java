package com.drones.vision.app.events;

import com.drones.vision.adapter.cvgrpc.GrpcDetectionPort;
import com.drones.vision.domain.model.Event;
import com.drones.vision.domain.model.EventType;
import com.drones.vision.domain.port.out.EventPublisherPort;

import java.util.Objects;

/**
 * {@link EventPublisherPort} decorator that additionally ends a {@link GrpcDetectionPort}'s
 * per-stream gRPC session when the stream it belongs to stops.
 *
 * <p>{@link GrpcDetectionPort} keeps one open bidi {@code DetectStream} call per stream id until
 * told the stream has ended (see adapter-cv-grpc/MODULE.md's "Stream lifecycle" section) —
 * without that signal, the call just lingers until the port itself is closed, leaking one idle
 * gRPC session per stream ever started. Neither {@code vision-application} nor {@code
 * vision-domain} may reference a concrete adapter, so this seam lives here instead: every event
 * is still delegated unchanged to the real {@link EventPublisherPort} (today {@code
 * com.drones.vision.app.devsupport.LoggingEventPublisher}), and a {@link
 * EventType#STREAM_STOPPED} event additionally calls {@link
 * GrpcDetectionPort#streamEnded(com.drones.vision.domain.model.StreamId)} for the event's stream.
 *
 * <p>Only wired ({@link WiringConfiguration#eventPublisherPort}) when {@code vision.cv.enabled}
 * is {@code true} and the current {@code DetectionPort} bean actually is a {@link
 * GrpcDetectionPort} — with CV disabled, the plain delegate is used directly and this class is
 * never constructed.
 */
public final class DetectionSessionCleanupEventPublisher implements EventPublisherPort {

    private final EventPublisherPort delegate;
    private final GrpcDetectionPort grpcDetectionPort;

    public DetectionSessionCleanupEventPublisher(EventPublisherPort delegate, GrpcDetectionPort grpcDetectionPort) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.grpcDetectionPort = Objects.requireNonNull(grpcDetectionPort, "grpcDetectionPort must not be null");
    }

    @Override
    public void publish(Event event) {
        delegate.publish(event);
        if (event.type() == EventType.STREAM_STOPPED && event.streamId() != null) {
            grpcDetectionPort.streamEnded(event.streamId());
        }
    }
}
