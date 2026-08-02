package com.drones.vision.app.events;

import com.drones.vision.domain.model.DetectionEvent;
import com.drones.vision.domain.model.DetectionEventId;
import com.drones.vision.domain.model.DetectionEventState;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.port.out.DetectionEventRepositoryPort;
import com.drones.vision.domain.port.out.LiveUpdatePublisherPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit test for {@link LiveUpdateDetectionEventRepository} (docs/REALTIME-PLAN.md §4,
 * extended for the {@code detection-events} live topic) — no Spring context, mirroring {@link
 * LiveUpdateAuditTrailTest}'s own shape.
 */
class LiveUpdateDetectionEventRepositoryTest {

    private DetectionEventRepositoryPort delegate;
    private LiveUpdatePublisherPort liveUpdatePublisherPort;
    private LiveUpdateDetectionEventRepository repository;

    @BeforeEach
    void setUp() {
        delegate = mock(DetectionEventRepositoryPort.class);
        liveUpdatePublisherPort = mock(LiveUpdatePublisherPort.class);
        repository = new LiveUpdateDetectionEventRepository(delegate, liveUpdatePublisherPort);
    }

    @Test
    void saveDelegatesAndAnnouncesTheSavedEvent() {
        Instant now = Instant.now();
        DetectionEvent event = new DetectionEvent(DetectionEventId.random(), StreamId.random(), null, "person", 0.7,
                now, now, DetectionEventState.OPEN, null);
        when(delegate.save(event)).thenReturn(event);

        DetectionEvent saved = repository.save(event);

        assertEquals(event, saved);
        verify(delegate).save(event);
        verify(liveUpdatePublisherPort).publishDetectionEvent(event);
    }

    @Test
    void findRecentAndFindByStreamAreThinPassThroughsWithNoLiveUpdateAnnouncement() {
        StreamId streamId = StreamId.random();
        repository.findRecent(null, 10);
        repository.findByStream(streamId, 10);

        verify(delegate).findRecent(null, 10);
        verify(delegate).findByStream(streamId, 10);
        verifyNoInteractions(liveUpdatePublisherPort);
    }
}
