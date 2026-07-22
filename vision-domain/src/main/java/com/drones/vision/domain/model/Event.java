package com.drones.vision.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A notable, semantic occurrence in the system.
 *
 * <p>Events are the platform's integration seam: everything notable emits
 * an {@code Event} through {@code EventPublisherPort}. Today that port is
 * backed by an in-process implementation; later it can be swapped for
 * MQTT/Kafka to support multi-instance deployments without any change to
 * core code, because callers only ever depend on the port.
 *
 * <p>{@code streamId} is nullable because some events are device-level
 * (e.g. {@link EventType#DEVICE_ONLINE}) rather than tied to a running
 * stream. {@code attributes} is defensively copied to an immutable map.
 *
 * @param id         unique event id; must not be blank
 * @param streamId   stream this event relates to, or {@code null} for device-level events
 * @param at         time the event occurred
 * @param type       kind of event
 * @param message    human-readable description
 * @param attributes free-form structured detail; defensively copied to an immutable map
 */
public record Event(String id, StreamId streamId, Instant at, EventType type, String message,
                     Map<String, String> attributes) {

    public Event {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Event id must not be blank");
        }
        if (at == null) {
            throw new IllegalArgumentException("Event at must not be null");
        }
        if (type == null) {
            throw new IllegalArgumentException("Event type must not be null");
        }
        if (message == null) {
            throw new IllegalArgumentException("Event message must not be null");
        }
        if (attributes == null) {
            throw new IllegalArgumentException("Event attributes must not be null");
        }
        attributes = Map.copyOf(attributes);
    }

    /**
     * Convenience factory for the common case: a stream-scoped event with no
     * extra structured attributes, a generated id, and a timestamp of "now".
     *
     * @param streamId stream this event relates to, or {@code null} for device-level events
     * @param type     kind of event
     * @param message  human-readable description
     * @return a new {@code Event}
     */
    public static Event of(StreamId streamId, EventType type, String message) {
        return new Event(UUID.randomUUID().toString(), streamId, Instant.now(), type, message, Map.of());
    }
}
