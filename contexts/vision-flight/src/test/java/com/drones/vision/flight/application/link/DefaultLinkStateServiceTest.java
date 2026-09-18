package com.drones.vision.flight.application.link;

import com.drones.vision.flight.domain.model.CarrierKind;
import com.drones.vision.flight.domain.model.LinkGroupView;
import com.drones.vision.flight.domain.model.LinkId;
import com.drones.vision.flight.domain.model.LinkView;
import com.drones.vision.flight.domain.model.SerialRole;
import com.drones.vision.flight.domain.port.LinkStateLiveUpdatePort;
import com.drones.vision.flight.domain.port.VehicleLinkPort;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.Event;
import com.drones.vision.platform.EventPublisherPort;
import com.drones.vision.platform.EventType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LINK-PAIRING-PLAN.md §4 row L3's own required case: exactly one {@link EventType#LINK_FAILOVER}
 * per active-link change — zero for a repeat/no-op observation, exactly one for a real change (a
 * "kill" of the active link). A test that only ever calls {@link LinkStateService#linksFor} with an
 * unchanging fake port would pass vacuously (it never actually kills a link); every test below that
 * asserts zero also independently asserts exactly one elsewhere, so a version of this test that
 * degenerated to "never fires" would fail too.
 */
class DefaultLinkStateServiceTest {

    private static final AssetId ASSET_ID = AssetId.random();
    private static final DeviceId DEVICE_ID = DeviceId.random();
    private static final UserId ACTOR_ID = UserId.random();
    private static final LinkId LINK_A = new LinkId("udp-listen:0.0.0.0:14550");
    private static final LinkId LINK_B = new LinkId("serial:/dev/ttyUSB0");

    private VehicleLinkPort vehicleLinkPort;
    private LinkStateLiveUpdatePort liveUpdatePort;
    private EventPublisherPort eventPublisher;
    private LinkStateService service;

    @BeforeEach
    void setUp() {
        vehicleLinkPort = mock(VehicleLinkPort.class);
        liveUpdatePort = mock(LinkStateLiveUpdatePort.class);
        eventPublisher = mock(EventPublisherPort.class);
        service = new DefaultLinkStateService(vehicleLinkPort, liveUpdatePort, eventPublisher);
    }

    private static LinkView linkView(LinkId id, boolean active) {
        return new LinkView(id, CarrierKind.UDP, SerialRole.NONE, "lobby", active, active, Duration.ZERO, null,
                DEVICE_ID);
    }

    private static LinkGroupView snapshot(LinkId activeLinkId, LinkId... members) {
        List<LinkView> links = List.of(members).stream()
                .map(id -> linkView(id, id.equals(activeLinkId)))
                .toList();
        return new LinkGroupView(ASSET_ID, links, activeLinkId, false, null);
    }

    @Test
    void firstObservationEstablishesTheBaselineWithoutFiringAFailover() {
        when(vehicleLinkPort.linksFor(ASSET_ID)).thenReturn(snapshot(LINK_A, LINK_A));

        service.linksFor(ASSET_ID);

        verify(eventPublisher, never()).publish(any());
        verify(liveUpdatePort).publishLinks(eq(ASSET_ID), any());
    }

    @Test
    void aNoOpTickAfterTheBaselinePublishesZeroFailovers() {
        when(vehicleLinkPort.linksFor(ASSET_ID)).thenReturn(snapshot(LINK_A, LINK_A));

        service.linksFor(ASSET_ID); // establishes the baseline
        service.linksFor(ASSET_ID); // a genuine no-op tick -- same active link, must not fire

        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void killingTheActiveLinkPublishesExactlyOneFailover() {
        when(vehicleLinkPort.linksFor(ASSET_ID))
                .thenReturn(snapshot(LINK_A, LINK_A))
                .thenReturn(new LinkGroupView(ASSET_ID, List.of(linkView(LINK_A, false)), null, false, null));

        service.linksFor(ASSET_ID); // baseline: LINK_A active
        service.linksFor(ASSET_ID); // kill: no active link at all now

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventPublisher, times(1)).publish(captor.capture());
        Event event = captor.getValue();
        assertEquals(EventType.LINK_FAILOVER, event.type());
        assertNull(event.streamId());
        assertEquals(ASSET_ID.value().toString(), event.attributes().get("assetId"));
        assertEquals(LINK_A.value(), event.attributes().get("fromLinkId"));
        assertEquals(null, event.attributes().get("toLinkId"));
        assertEquals("auto", event.attributes().get("reason"));
    }

    @Test
    void reclaimToADifferentLinkPublishesExactlyOneFailover() {
        when(vehicleLinkPort.linksFor(ASSET_ID))
                .thenReturn(snapshot(LINK_A, LINK_A, LINK_B))
                .thenReturn(snapshot(LINK_B, LINK_A, LINK_B));

        service.linksFor(ASSET_ID);
        service.linksFor(ASSET_ID);

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventPublisher, times(1)).publish(captor.capture());
        Event event = captor.getValue();
        assertEquals(LINK_A.value(), event.attributes().get("fromLinkId"));
        assertEquals(LINK_B.value(), event.attributes().get("toLinkId"));
    }

    @Test
    void pinCallsThePortThenRepublishesAndAttributesTheFailoverToTheOperator() {
        when(vehicleLinkPort.linksFor(ASSET_ID))
                .thenReturn(snapshot(LINK_A, LINK_A, LINK_B))
                .thenReturn(snapshot(LINK_B, LINK_A, LINK_B));

        service.linksFor(ASSET_ID); // baseline: LINK_A active
        LinkGroupView result = service.pin(ASSET_ID, LINK_B, ACTOR_ID);

        verify(vehicleLinkPort).pin(ASSET_ID, LINK_B);
        assertEquals(LINK_B, result.activeLinkId());
        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventPublisher, times(1)).publish(captor.capture());
        assertEquals("operator", captor.getValue().attributes().get("reason"));
    }

    @Test
    void releaseCallsThePortAndRepublishesTheResultingSnapshot() {
        when(vehicleLinkPort.linksFor(ASSET_ID))
                .thenReturn(snapshot(LINK_B, LINK_A, LINK_B))
                .thenReturn(snapshot(LINK_A, LINK_A, LINK_B));

        service.linksFor(ASSET_ID); // baseline: LINK_B active (as if previously pinned)
        LinkGroupView result = service.release(ASSET_ID, ACTOR_ID);

        verify(vehicleLinkPort).release(ASSET_ID);
        assertEquals(LINK_A, result.activeLinkId());
        verify(eventPublisher, times(1)).publish(any());
    }
}
