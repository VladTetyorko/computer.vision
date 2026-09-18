package com.drones.mavlink.session;

import com.drones.mavlink.CompId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.codec.MavFrame;
import com.drones.mavlink.codec.MavHeader;
import com.drones.mavlink.transport.LinkId;
import com.drones.mavlink.transport.LinkPeer;

import io.dronefleet.mavlink.common.RadioStatus;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure in-memory tests of {@link DefaultLinkQuality} -- hand-built {@link MavFrame}s dispatched
 * directly through a real {@link DefaultDispatcher}, no sockets (LINK-PAIRING-PLAN.md §4 L1).
 */
class DefaultLinkQualityTest {

    private static final LinkId LINK_A = new LinkId("test-link-a");
    private static final LinkId LINK_B = new LinkId("test-link-b");
    private static final SysId ORIGIN = new SysId(1);

    @Test
    void recordsQualityFromRadioStatusOnTheTelemetryRadioComponent() {
        DefaultDispatcher dispatcher = new DefaultDispatcher();
        DefaultLinkQuality quality = new DefaultLinkQuality(dispatcher);

        dispatcher.dispatch(radioStatusFrame(LINK_A, 68, 200, 180, 40, 3, 0, 0));

        LinkQuality.Quality reading = quality.of(LINK_A);
        assertEquals(LINK_A, reading.linkId());
        assertEquals(200, reading.rssi());
        assertEquals(180, reading.remoteRssi());
        assertEquals(40, reading.noise());
        assertEquals(3, reading.rxErrors());
        assertEquals(Boolean.FALSE, reading.fixed());
    }

    @Test
    void nonZeroFixedCountMapsToTrue() {
        DefaultDispatcher dispatcher = new DefaultDispatcher();
        DefaultLinkQuality quality = new DefaultLinkQuality(dispatcher);

        dispatcher.dispatch(radioStatusFrame(LINK_A, 110, 100, 100, 10, 0, 5, 0));

        assertEquals(Boolean.TRUE, quality.of(LINK_A).fixed());
    }

    @Test
    void ignoresRadioStatusFromAnUnrelatedComponent() {
        DefaultDispatcher dispatcher = new DefaultDispatcher();
        DefaultLinkQuality quality = new DefaultLinkQuality(dispatcher);

        // component 1 (autopilot) -- RADIO_STATUS never actually arrives from this component in
        // real MAVLink traffic, but the filter must reject it regardless of message type.
        dispatcher.dispatch(radioStatusFrame(LINK_A, 1, 200, 180, 40, 12, 0, 0));

        assertNull(quality.of(LINK_A), "a RADIO_STATUS from a non-radio component must not be recorded");
    }

    @Test
    void ignoresNonRadioStatusFrames() {
        DefaultDispatcher dispatcher = new DefaultDispatcher();
        DefaultLinkQuality quality = new DefaultLinkQuality(dispatcher);

        MavHeader header = new MavHeader(2, 0, ORIGIN, new CompId(68), 0, 0, 0, false);
        dispatcher.dispatch(new MavFrame(header, "not-a-radio-status", LINK_A, LinkPeer.NONE, Instant.now()));

        assertNull(quality.of(LINK_A));
    }

    @Test
    void tracksEachLinkIndependently() {
        DefaultDispatcher dispatcher = new DefaultDispatcher();
        DefaultLinkQuality quality = new DefaultLinkQuality(dispatcher);

        dispatcher.dispatch(radioStatusFrame(LINK_A, 68, 200, 180, 40, 0, 0, 0));
        dispatcher.dispatch(radioStatusFrame(LINK_B, 111, 50, 40, 10, 0, 0, 0));

        assertEquals(200, quality.of(LINK_A).rssi());
        assertEquals(50, quality.of(LINK_B).rssi());
        assertTrue(quality.of(LINK_A).lastRadioStatusAt().isBefore(Instant.now().plusSeconds(1)));
    }

    @Test
    void ofReturnsNullForALinkNeverHeardFrom() {
        DefaultLinkQuality quality = new DefaultLinkQuality(new DefaultDispatcher());
        assertNull(quality.of(new LinkId("never-heard")));
    }

    private static MavFrame radioStatusFrame(LinkId link, int componentId, int rssi, int remrssi, int noise,
                                              int rxerrors, int fixed, int txbuf) {
        MavHeader header = new MavHeader(2, 0, ORIGIN, new CompId(componentId), 109, 0, 0, false);
        RadioStatus status = RadioStatus.builder()
                .rssi(rssi)
                .remrssi(remrssi)
                .txbuf(txbuf)
                .noise(noise)
                .remnoise(0)
                .rxerrors(rxerrors)
                .fixed(fixed)
                .build();
        return new MavFrame(header, status, link, LinkPeer.NONE, Instant.now());
    }
}
