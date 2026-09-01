package com.drones.vision.flight.application.alerting;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.platform.Event;
import com.drones.vision.platform.EventLiveUpdatePort;
import com.drones.vision.platform.EventPublisherPort;
import com.drones.vision.platform.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class LinkLossNotifierTest {

    private EventPublisherPort eventPublisher;
    private EventLiveUpdatePort liveUpdatePublisherPort;

    @BeforeEach
    void setUp() {
        eventPublisher = mock(EventPublisherPort.class);
        liveUpdatePublisherPort = mock(EventLiveUpdatePort.class);
    }

    private LinkLossNotifier notifier() {
        return new LinkLossNotifier(eventPublisher, liveUpdatePublisherPort);
    }

    @Test
    void reportLinkLostPublishesOneEventThroughBothPorts() {
        LinkLossNotifier notifier = notifier();
        AssetId assetId = AssetId.random();

        notifier.reportLinkLost(assetId, "MAVLink link to Rover 1 lost");

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventPublisher, times(1)).publish(captor.capture());
        verify(liveUpdatePublisherPort, times(1)).publishEvent(any());
        Event event = captor.getValue();
        assertEquals(EventType.LINK_LOST, event.type());
        assertEquals("MAVLink link to Rover 1 lost", event.message());
        assertEquals(assetId.value().toString(), event.attributes().get("assetId"));
        assertNull(event.streamId());
    }

    @Test
    void everyCallPublishesIndependently() {
        LinkLossNotifier notifier = notifier();
        AssetId assetId = AssetId.random();

        notifier.reportLinkLost(assetId, "MAVLink link to Rover 1 lost");
        notifier.reportLinkLost(assetId, "MAVLink link to Rover 1 lost");

        verify(eventPublisher, times(2)).publish(any());
    }

    @Test
    void neverThrowsWhenBothPublishersAreAbsent() {
        LinkLossNotifier notifier = new LinkLossNotifier(null, null);

        notifier.reportLinkLost(AssetId.random(), "MAVLink link to Rover 1 lost");
    }

    @Test
    void reportLinkLostRejectsNullAssetIdAndSummary() {
        LinkLossNotifier notifier = notifier();

        assertThrows(NullPointerException.class, () -> notifier.reportLinkLost(null, "lost"));
        assertThrows(NullPointerException.class, () -> notifier.reportLinkLost(AssetId.random(), null));
    }
}
