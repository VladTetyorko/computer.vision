package com.drones.vision.app.events;

import com.drones.vision.platform.Event;
import com.drones.vision.platform.EventType;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.platform.EventPublisherPort;
import com.drones.vision.platform.EventLiveUpdatePort;
import com.drones.vision.warehouse.domain.port.FleetLiveUpdatePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Pure unit test for {@link LiveUpdateEventPublisher} (docs/plans/done/REALTIME-PLAN.md §4) — no Spring
 * context.
 */
class LiveUpdateEventPublisherTest {

    private static final EnumSet<EventType> FLEET_LIFECYCLE_EVENTS = EnumSet.of(EventType.DEVICE_ONLINE,
            EventType.DEVICE_OFFLINE, EventType.STREAM_STARTED, EventType.STREAM_STOPPED);

    private EventPublisherPort delegate;
    private EventLiveUpdatePort eventLiveUpdatePort;
    private FleetLiveUpdatePort fleetLiveUpdatePort;
    private LiveUpdateEventPublisher publisher;

    @BeforeEach
    void setUp() {
        delegate = mock(EventPublisherPort.class);
        eventLiveUpdatePort = mock(EventLiveUpdatePort.class);
        fleetLiveUpdatePort = mock(FleetLiveUpdatePort.class);
        publisher = new LiveUpdateEventPublisher(delegate, eventLiveUpdatePort, fleetLiveUpdatePort);
    }

    @Test
    void everyEventIsDelegatedUnchangedAndAnnouncedAsALiveEvent() {
        Event event = Event.of(StreamId.random(), EventType.DETECTION, "detected something");

        publisher.publish(event);

        verify(delegate).publish(event);
        verify(eventLiveUpdatePort).publishEvent(event);
    }

    @Test
    void fleetLifecycleEventTypesAlsoAnnounceAFleetChangedUpdate() {
        for (EventType type : FLEET_LIFECYCLE_EVENTS) {
            FleetLiveUpdatePort port = mock(FleetLiveUpdatePort.class);
            new LiveUpdateEventPublisher(delegate, eventLiveUpdatePort, port)
                    .publish(Event.of(StreamId.random(), type, "lifecycle change"));

            verify(port).publishFleetChanged();
        }
    }

    @Test
    void nonLifecycleEventTypesNeverAnnounceAFleetChangedUpdate() {
        for (EventType type : EnumSet.complementOf(FLEET_LIFECYCLE_EVENTS)) {
            FleetLiveUpdatePort port = mock(FleetLiveUpdatePort.class);
            new LiveUpdateEventPublisher(delegate, eventLiveUpdatePort, port)
                    .publish(Event.of(StreamId.random(), type, "not lifecycle"));

            verify(port, never()).publishFleetChanged();
        }
    }
}
