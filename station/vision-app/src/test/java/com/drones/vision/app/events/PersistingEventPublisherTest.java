package com.drones.vision.app.events;

import com.drones.vision.kernel.StreamId;
import com.drones.vision.platform.Event;
import com.drones.vision.platform.EventHistoryPort;
import com.drones.vision.platform.EventPublisherPort;
import com.drones.vision.platform.EventType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Pure unit test for {@link PersistingEventPublisher} (docs/plans/active/ALWAYS-ON-FLOW-PLAN.md
 * wave B3) — no Spring context. The durable write runs on its own virtual thread (see the class
 * javadoc's "Off the hot path" section), so assertions on {@link EventHistoryPort} interactions use
 * {@code Mockito.timeout(long)} rather than a synchronous {@code verify} — the same convention
 * {@code PassportCaptureObserverTest} already uses for its own async collaborator assertions.
 */
class PersistingEventPublisherTest {

    private EventPublisherPort delegate;
    private EventHistoryPort eventHistoryPort;
    private PersistingEventPublisher publisher;

    @BeforeEach
    void setUp() {
        delegate = mock(EventPublisherPort.class);
        eventHistoryPort = mock(EventHistoryPort.class);
        publisher = new PersistingEventPublisher(delegate, eventHistoryPort);
    }

    @Test
    void everyNonDetectionEventIsDelegatedSynchronouslyAndRecordedDurably() {
        Event event = Event.of(StreamId.random(), EventType.STREAM_STARTED, "stream started");

        publisher.publish(event);

        // The delegate call is synchronous -- unlike the durable write, it must already have
        // happened by the time publish() returns, with no polling needed.
        verify(delegate).publish(event);
        verify(eventHistoryPort, timeout(2000)).record(event);
    }

    @Test
    void detectionEventsAreDelegatedButNeverDurablyRecorded() {
        Event event = Event.of(StreamId.random(), EventType.DETECTION, "detected something");

        publisher.publish(event);

        verify(delegate).publish(event);
        // timeout(...).times(0) waits out its whole budget before passing -- proving the async write
        // was never even submitted, rather than a plain never() that could pass on a lucky race with
        // a write still in flight.
        verify(eventHistoryPort, timeout(200).times(0)).record(any());
    }

    @Test
    void everyNonDetectionEventTypeIsEligibleForRecording() {
        for (EventType type : EnumSet.complementOf(EnumSet.of(EventType.DETECTION))) {
            EventHistoryPort port = mock(EventHistoryPort.class);
            Event event = Event.of(StreamId.random(), type, "occurrence");

            new PersistingEventPublisher(delegate, port).publish(event);

            verify(port, timeout(2000)).record(event);
        }
    }

    @Test
    void aFailingDurableWriteNeverPropagatesToTheCaller() {
        doThrow(new RuntimeException("database unavailable")).when(eventHistoryPort).record(any());
        Event event = Event.of(StreamId.random(), EventType.LINK_LOST, "link lost");

        // publish() must return normally even though the (async) durable write is doomed to throw --
        // the exception happens on a different thread entirely, but this call itself must never see it.
        publisher.publish(event);

        verify(delegate).publish(event);
        verify(eventHistoryPort, timeout(2000)).record(event);
    }
}
