package com.drones.vision.platform;

/**
 * Driven port: publish a domain event.
 *
 * <p>Events are the platform's integration seam: everything notable (device
 * online/offline, stream started/stopped, detections, pipeline errors,
 * training progress) is emitted through this single port rather than
 * through direct, type-specific callbacks. Today the reference
 * implementation is in-process (e.g. logging or an in-memory subscriber
 * list); later it can be swapped for a message-broker-backed implementation
 * (MQTT/Kafka) to support multi-instance deployments — because callers only
 * ever depend on this port, that swap requires no change to core code.
 *
 * <h2>Contract</h2>
 * {@link #publish(Event)} hands the event to the configured sink(s).
 * Implementations should not throw for ordinary delivery failures (e.g. a
 * disconnected broker) since a failure to publish an event must never take
 * down the stream pipeline that raised it; log and continue instead.
 *
 * <h2>Threading</h2>
 * May be called concurrently from many stream pipelines and control-plane
 * operations at once (one JVM instance can host many streams, each raising
 * events independently); implementations must be safe for concurrent use
 * and should return quickly — this is called from the hot pipeline path
 * (e.g. on every non-empty detection result), so implementations that need
 * to do slow I/O should hand off internally rather than blocking the
 * caller.
 */
public interface EventPublisherPort {

    /**
     * Publishes a domain event.
     *
     * @param event the event to publish
     */
    void publish(Event event);
}
