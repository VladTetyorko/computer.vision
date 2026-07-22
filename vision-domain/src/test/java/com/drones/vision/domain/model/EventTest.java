package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventTest {

    @Test
    void attributesAreDefensivelyCopied() {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("label", "person");

        Event event = new Event("evt-1", StreamId.random(), Instant.now(), EventType.DETECTION, "msg", attributes);

        attributes.put("extra", "1");

        assertEquals(1, event.attributes().size(), "later mutation of the source map must not affect the event");
        assertThrows(UnsupportedOperationException.class, () -> event.attributes().put("x", "y"),
                "returned attributes map must be immutable");
    }

    @Test
    void streamIdIsNullableForDeviceLevelEvents() {
        Event event = new Event("evt-1", null, Instant.now(), EventType.DEVICE_ONLINE, "device online", Map.of());
        assertNull(event.streamId());
    }

    @Test
    void rejectsInvalidArguments() {
        StreamId streamId = StreamId.random();
        Instant now = Instant.now();

        assertThrows(IllegalArgumentException.class,
                () -> new Event(null, streamId, now, EventType.DETECTION, "msg", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Event("", streamId, now, EventType.DETECTION, "msg", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Event("evt-1", streamId, null, EventType.DETECTION, "msg", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Event("evt-1", streamId, now, null, "msg", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Event("evt-1", streamId, now, EventType.DETECTION, null, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Event("evt-1", streamId, now, EventType.DETECTION, "msg", null));
    }

    @Test
    void ofFactoryGeneratesIdAndTimestamp() {
        StreamId streamId = StreamId.random();

        Event event = Event.of(streamId, EventType.STREAM_STARTED, "started");

        assertNotNull(event.id());
        assertEquals(streamId, event.streamId());
        assertEquals(EventType.STREAM_STARTED, event.type());
        assertEquals("started", event.message());
        assertNotNull(event.at());
        assertEquals(Map.of(), event.attributes());
    }

    @Test
    void ofFactoryAllowsNullStreamId() {
        Event event = Event.of(null, EventType.DEVICE_OFFLINE, "offline");
        assertNull(event.streamId());
    }
}
