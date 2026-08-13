package com.drones.vision.adapter.publishhls;

import com.drones.vision.kernel.Capability;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.perception.domain.model.PixelFormat;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.perception.domain.port.StreamPublisherPort;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PublisherRouter}'s routing decision and per-stream stickiness, against two
 * hand-fake {@link StreamPublisherPort}s (no I/O) so the routing logic is tested in isolation from
 * {@link MediamtxProxyPublisher}/{@link MediamtxStreamPublisher} — those are covered by their own
 * test classes.
 */
class PublisherRouterTest {

    @Test
    void proxiesSourceIsTrueOnlyForAnRtspDeviceWhenSourceProxyIsEnabled() {
        RecordingPublisher direct = new RecordingPublisher(false);
        RecordingPublisher proxy = new RecordingPublisher(true);
        PublisherRouter router = new PublisherRouter(direct, proxy, true);

        assertTrue(router.proxiesSource(deviceWithProtocol("rtsp")));
        assertFalse(router.proxiesSource(deviceWithProtocol("mjpeg")));
        assertFalse(router.proxiesSource(deviceWithProtocol("sim")));
        assertFalse(router.proxiesSource(null));
    }

    @Test
    void proxiesSourceIsAlwaysFalseWhenSourceProxyIsDisabledEvenForRtsp() {
        PublisherRouter router = new PublisherRouter(new RecordingPublisher(false), new RecordingPublisher(true), false);

        assertFalse(router.proxiesSource(deviceWithProtocol("rtsp")));
    }

    @Test
    void streamStartedRoutesAnRtspDeviceToTheProxyPublisherWhenEnabled() {
        RecordingPublisher direct = new RecordingPublisher(false);
        RecordingPublisher proxy = new RecordingPublisher(true);
        PublisherRouter router = new PublisherRouter(direct, proxy, true);
        StreamId streamId = StreamId.random();
        Device device = deviceWithProtocol("rtsp");

        router.streamStarted(streamId, device);

        assertEquals(List.of(streamId), proxy.startedIds);
        assertTrue(direct.startedIds.isEmpty());
    }

    @Test
    void streamStartedRoutesANonRtspDeviceToTheDirectPublisherEvenWhenProxyIsEnabled() {
        RecordingPublisher direct = new RecordingPublisher(false);
        RecordingPublisher proxy = new RecordingPublisher(true);
        PublisherRouter router = new PublisherRouter(direct, proxy, true);
        StreamId streamId = StreamId.random();

        router.streamStarted(streamId, deviceWithProtocol("mjpeg"));

        assertEquals(List.of(streamId), direct.startedIds);
        assertTrue(proxy.startedIds.isEmpty());
    }

    @Test
    void streamStartedRoutesEveryDeviceToTheDirectPublisherWhenSourceProxyIsDisabled() {
        RecordingPublisher direct = new RecordingPublisher(false);
        RecordingPublisher proxy = new RecordingPublisher(true);
        PublisherRouter router = new PublisherRouter(direct, proxy, false);
        StreamId streamId = StreamId.random();

        router.streamStarted(streamId, deviceWithProtocol("rtsp"));

        assertEquals(List.of(streamId), direct.startedIds);
        assertTrue(proxy.startedIds.isEmpty());
    }

    /**
     * Once {@link PublisherRouter#streamStarted} has routed a stream to the proxy publisher, every
     * later call for that {@link StreamId} — publish, streamEnded, the URL methods — must reach the
     * <i>same</i> publisher, even though only {@code streamStarted} is ever given a {@link Device} to
     * evaluate the routing rule against.
     */
    @Test
    void laterCallsForARoutedStreamStayOnTheSamePublisherTheyStartedOn() {
        RecordingPublisher direct = new RecordingPublisher(false);
        RecordingPublisher proxy = new RecordingPublisher(true);
        PublisherRouter router = new PublisherRouter(direct, proxy, true);
        StreamId streamId = StreamId.random();
        VideoFrame frame = new VideoFrame(streamId, 0L, Instant.now(), 4, 4, PixelFormat.BGR24, ByteBuffer.wrap(new byte[4 * 4 * 3]));

        router.streamStarted(streamId, deviceWithProtocol("rtsp"));
        router.publish(streamId, frame);
        router.viewUrl(streamId);
        router.whepUrl(streamId);
        router.playbackUrl(streamId, Instant.now(), Duration.ofSeconds(1));
        router.streamEnded(streamId);

        assertEquals(List.of(frame), proxy.publishedFrames);
        assertEquals(1, proxy.viewUrlCalls);
        assertEquals(1, proxy.whepUrlCalls);
        assertEquals(1, proxy.playbackUrlCalls);
        assertEquals(List.of(streamId), proxy.endedIds);
        assertTrue(direct.publishedFrames.isEmpty());
        assertTrue(direct.endedIds.isEmpty());
    }

    /** A stream id this router never routed (no matching {@code streamStarted}) falls back to the direct publisher. */
    @Test
    void aStreamIdNeverStartedFallsBackToTheDirectPublisher() {
        RecordingPublisher direct = new RecordingPublisher(false);
        RecordingPublisher proxy = new RecordingPublisher(true);
        PublisherRouter router = new PublisherRouter(direct, proxy, true);
        StreamId unknownId = StreamId.random();

        router.streamEnded(unknownId);
        router.viewUrl(unknownId);

        assertEquals(List.of(unknownId), direct.endedIds);
        assertEquals(1, direct.viewUrlCalls);
        assertTrue(proxy.endedIds.isEmpty());
    }

    @Test
    void streamStartedTreatsANullDeviceAsNonProxyEligible() {
        RecordingPublisher direct = new RecordingPublisher(false);
        RecordingPublisher proxy = new RecordingPublisher(true);
        PublisherRouter router = new PublisherRouter(direct, proxy, true);
        StreamId streamId = StreamId.random();

        router.streamStarted(streamId, null);

        assertEquals(List.of(streamId), direct.startedIds);
        assertTrue(proxy.startedIds.isEmpty());
    }

    private static Device deviceWithProtocol(String protocol) {
        return new Device(DeviceId.random(), "device-" + protocol, Set.of(Capability.VIDEO),
                new StreamDescriptor(protocol, URI.create(protocol + "://camera/feed"), Map.of()));
    }

    /** A plain, in-memory {@link StreamPublisherPort} fake that records every call it receives. */
    private static final class RecordingPublisher implements StreamPublisherPort {
        final List<StreamId> startedIds = new ArrayList<>();
        final List<VideoFrame> publishedFrames = new ArrayList<>();
        final List<StreamId> endedIds = new ArrayList<>();
        final boolean proxiesSourceAnswer;
        int viewUrlCalls;
        int whepUrlCalls;
        int playbackUrlCalls;

        /** @param proxiesSourceAnswer what {@link #proxiesSource} answers — mirrors the fixed answer a real publisher gives. */
        RecordingPublisher(boolean proxiesSourceAnswer) {
            this.proxiesSourceAnswer = proxiesSourceAnswer;
        }

        @Override
        public void streamStarted(StreamId id, Device device) {
            startedIds.add(id);
        }

        @Override
        public void publish(StreamId id, VideoFrame frame) {
            publishedFrames.add(frame);
        }

        @Override
        public void streamEnded(StreamId id) {
            endedIds.add(id);
        }

        @Override
        public Optional<URI> viewUrl(StreamId id) {
            viewUrlCalls++;
            return Optional.empty();
        }

        @Override
        public Optional<URI> whepUrl(StreamId id) {
            whepUrlCalls++;
            return Optional.empty();
        }

        @Override
        public Optional<URI> playbackUrl(StreamId id, Instant start, Duration duration) {
            playbackUrlCalls++;
            return Optional.empty();
        }

        @Override
        public boolean proxiesSource(Device device) {
            return proxiesSourceAnswer;
        }
    }
}
