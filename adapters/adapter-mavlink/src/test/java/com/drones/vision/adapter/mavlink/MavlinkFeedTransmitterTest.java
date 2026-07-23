package com.drones.vision.adapter.mavlink;

import com.drones.vision.domain.model.FeedId;
import com.drones.vision.domain.model.FeedSpec;
import com.drones.vision.domain.model.StreamDescriptor;

import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;
import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavlinkFeedTransmitterTest {

    private static final String ROUTE = "50.0,30.0;50.01,30.0";

    @Test
    void supportsMavlinkProtocolWithAUdpUriCarryingAHostAndAPositivePort() {
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();

        assertTrue(transmitter.supports(
                new FeedSpec("mavlink", URI.create("udp://127.0.0.1:14550"), Map.of("route", ROUTE))));
    }

    @Test
    void rejectsANonMavlinkProtocol() {
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();

        assertFalse(transmitter.supports(new FeedSpec("rtsp", URI.create("udp://127.0.0.1:14550"), Map.of())));
    }

    @Test
    void rejectsANonUdpScheme() {
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();

        assertFalse(transmitter.supports(new FeedSpec("mavlink", URI.create("file:///tmp/clip.mp4"), Map.of())));
    }

    @Test
    void rejectsAMissingHostOrPort() {
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();

        assertFalse(transmitter.supports(new FeedSpec("mavlink", URI.create("udp://127.0.0.1"), Map.of())));
    }

    @Test
    void rejectsANullSpec() {
        assertFalse(new MavlinkFeedTransmitter().supports(null));
    }

    @Test
    void startRejectsANullId() {
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        FeedSpec spec = new FeedSpec("mavlink", URI.create("udp://127.0.0.1:14550"), Map.of("route", ROUTE));

        assertThrows(IllegalArgumentException.class, () -> transmitter.start(null, spec));
    }

    @Test
    void startRejectsAnUnsupportedSpec() {
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        FeedSpec spec = new FeedSpec("rtsp", URI.create("file:///tmp/clip.mp4"), Map.of());

        assertThrows(IllegalArgumentException.class, () -> transmitter.start(FeedId.random(), spec));
    }

    @Test
    void startRejectsAMissingRouteOption() {
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        FeedSpec spec = new FeedSpec("mavlink", URI.create("udp://127.0.0.1:14550"), Map.of());

        assertThrows(IllegalArgumentException.class, () -> transmitter.start(FeedId.random(), spec));
    }

    @Test
    void startRejectsAMalformedRouteOption() {
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        FeedSpec spec = new FeedSpec("mavlink", URI.create("udp://127.0.0.1:14550"), Map.of("route", "not-a-route"));

        assertThrows(IllegalArgumentException.class, () -> transmitter.start(FeedId.random(), spec));
    }

    @Test
    void startReturnsADescriptorEqualToTheRequestedDestination() throws Exception {
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        FeedId feedId = FeedId.random();
        URI destination = URI.create("udp://127.0.0.1:" + freePort());
        FeedSpec spec = new FeedSpec("mavlink", destination, Map.of("route", ROUTE));

        try {
            StreamDescriptor descriptor = transmitter.start(feedId, spec);

            assertEquals("mavlink", descriptor.protocol());
            assertEquals(destination, descriptor.uri());
        } finally {
            transmitter.stop(feedId);
        }
    }

    @Test
    void stopOnAnUnknownOrUnstartedFeedIsANoop() {
        assertDoesNotThrow(() -> new MavlinkFeedTransmitter().stop(FeedId.random()));
    }

    private static int freePort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
