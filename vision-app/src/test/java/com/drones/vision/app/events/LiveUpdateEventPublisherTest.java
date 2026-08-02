package com.drones.vision.app.events;

import com.drones.vision.domain.model.Event;
import com.drones.vision.domain.model.EventType;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.port.out.EventPublisherPort;
import com.drones.vision.domain.port.out.LiveUpdatePublisherPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Pure unit test for {@link LiveUpdateEventPublisher} (docs/REALTIME-PLAN.md §4) — no Spring
 * context.
 */
class LiveUpdateEventPublisherTest {

    private static final EnumSet<EventType> FLEET_LIFECYCLE_EVENTS = EnumSet.of(EventType.DEVICE_ONLINE,
            EventType.DEVICE_OFFLINE, EventType.STREAM_STARTED, EventType.STREAM_STOPPED);

    private EventPublisherPort delegate;
    private LiveUpdatePublisherPort liveUpdatePublisherPort;
    private LiveUpdateEventPublisher publisher;

    @BeforeEach
    void setUp() {
        delegate = mock(EventPublisherPort.class);
        liveUpdatePublisherPort = mock(LiveUpdatePublisherPort.class);
        publisher = new LiveUpdateEventPublisher(delegate, liveUpdatePublisherPort);
    }

    @Test
    void everyEventIsDelegatedUnchangedAndAnnouncedAsALiveEvent() {
        Event event = Event.of(StreamId.random(), EventType.DETECTION, "detected something");

        publisher.publish(event);

        verify(delegate).publish(event);
        verify(liveUpdatePublisherPort).publishEvent(event);
    }

    @Test
    void fleetLifecycleEventTypesAlsoAnnounceAFleetChangedUpdate() {
        for (EventType type : FLEET_LIFECYCLE_EVENTS) {
            LiveUpdatePublisherPort port = mock(LiveUpdatePublisherPort.class);
            new LiveUpdateEventPublisher(delegate, port).publish(Event.of(StreamId.random(), type, "lifecycle change"));

            verify(port).publishFleetChanged();
        }
    }

    @Test
    void nonLifecycleEventTypesNeverAnnounceAFleetChangedUpdate() {
        for (EventType type : EnumSet.complementOf(FLEET_LIFECYCLE_EVENTS)) {
            LiveUpdatePublisherPort port = mock(LiveUpdatePublisherPort.class);
            new LiveUpdateEventPublisher(delegate, port).publish(Event.of(StreamId.random(), type, "not lifecycle"));

            verify(port, never()).publishFleetChanged();
        }
    }
}
