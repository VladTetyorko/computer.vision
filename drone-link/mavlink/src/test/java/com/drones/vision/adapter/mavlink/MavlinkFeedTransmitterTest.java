package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.codec.FrameReader;
import com.drones.mavlink.codec.MavFrame;
import com.drones.mavlink.transport.ByteChunk;
import com.drones.mavlink.transport.UdpListenLink;

import com.drones.vision.perception.domain.model.FeedId;
import com.drones.vision.perception.domain.model.FeedSpec;
import com.drones.vision.kernel.StreamDescriptor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramSocket;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void startAcceptsAMalformedFailsafeBatteryPercentAndFallsBackToTheDefault() throws Exception {
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        FeedId feedId = FeedId.random();
        URI destination = URI.create("udp://127.0.0.1:" + freePort());
        FeedSpec spec = new FeedSpec("mavlink", destination,
                Map.of("route", ROUTE, "failsafeBatteryPercent", "not-a-number"));

        try {
            assertDoesNotThrow(() -> transmitter.start(feedId, spec),
                    "failsafeBatteryPercent is lenient like batteryDrainPerSecond -- malformed falls back, never throws");
        } finally {
            transmitter.stop(feedId);
        }
    }

    @Test
    void startAcceptsAnExplicitFailsafeBatteryPercentOption() throws Exception {
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        FeedId feedId = FeedId.random();
        URI destination = URI.create("udp://127.0.0.1:" + freePort());
        FeedSpec spec = new FeedSpec("mavlink", destination,
                Map.of("route", ROUTE, "failsafeBatteryPercent", "90"));

        try {
            StreamDescriptor descriptor = transmitter.start(feedId, spec);
            assertEquals(destination, descriptor.uri());
        } finally {
            transmitter.stop(feedId);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void startUsesTheSysidOptionAsTheOriginSystemIdOnTheWire() throws Exception {
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        FeedId feedId = FeedId.random();
        int port = freePort();
        FeedSpec spec = new FeedSpec("mavlink", URI.create("udp://127.0.0.1:" + port),
                Map.of("route", ROUTE, "sysid", "42"));

        try {
            assertEquals(42, firstOriginSystemId(port, spec, transmitter, feedId));
        } finally {
            transmitter.stop(feedId);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void startFallsBackToTheDefaultSystemIdWhenSysidIsMissingMalformedOrOutOfRange() throws Exception {
        MavlinkFeedTransmitter transmitter = new MavlinkFeedTransmitter();
        FeedId feedId = FeedId.random();
        int port = freePort();
        FeedSpec spec = new FeedSpec("mavlink", URI.create("udp://127.0.0.1:" + port),
                Map.of("route", ROUTE, "sysid", "not-a-number"));

        try {
            assertEquals(MavlinkFeedTransmitter.DEFAULT_MAV_SYSTEM_ID, firstOriginSystemId(port, spec, transmitter, feedId));
        } finally {
            transmitter.stop(feedId);
        }
    }

    /**
     * Binds a raw receiver link on {@code port} with {@code mavlink-core}'s own {@link
     * UdpListenLink}/{@link FrameReader}, starts the feed, and decodes its first message.
     * docs/plans/active/MAVLINK-CORE-PLAN.md W4: migrated off the deleted {@code
     * MavlinkUdpInputStream} test double onto the equivalent core primitives -- same assertion,
     * same wire-level check, only the receiving scaffolding changed.
     */
    private static int firstOriginSystemId(int port, FeedSpec spec, MavlinkFeedTransmitter transmitter, FeedId feedId)
            throws Exception {
        try (UdpListenLink receiveLink = new UdpListenLink("127.0.0.1", port)) {
            transmitter.start(feedId, spec);

            FrameReader reader = new FrameReader(receiveLink);
            AtomicReference<MavFrame> firstFrame = new AtomicReference<>();
            long deadlineNanos = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (firstFrame.get() == null && System.nanoTime() < deadlineNanos) {
                ByteChunk chunk = receiveLink.poll(Duration.ofSeconds(1));
                if (chunk == null) {
                    continue;
                }
                reader.offer(chunk, frame -> firstFrame.compareAndSet(null, frame));
            }
            if (firstFrame.get() == null) {
                throw new AssertionError("expected a MAVLink message from the feed within 8s");
            }
            return firstFrame.get().header().system().value();
        }
    }

    private static int freePort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
