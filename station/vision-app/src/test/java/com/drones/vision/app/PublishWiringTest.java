package com.drones.vision.app;

import com.drones.vision.adapter.publishhls.MediamtxReplayFrameExtractor;
import com.drones.vision.adapter.publishhls.MediamtxStreamPublisher;
import com.drones.vision.adapter.publishhls.PublisherRouter;
import com.drones.vision.api.proxy.HlsProxyController;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.events.domain.port.ReplayFrameExtractionPort;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
import com.drones.vision.perception.domain.port.VideoSourcePort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Context test for the <em>default</em> configuration (no {@code
 * vision.publish.*} overrides, unlike {@link SimStreamSmokeTest} and {@link
 * com.drones.vision.VisionApplicationTests}, which disable mediamtx
 * publishing for determinism): asserts {@link WiringConfiguration} selects
 * the mediamtx-backed {@link StreamPublisherPort} per {@link
 * VisionPublishProperties}'s {@code enabled} default of {@code true}, that
 * both the simulation and RTSP {@link VideoSourcePort} adapters are
 * registered, that the {@link HlsProxyController} bean exists (so {@code
 * /hls/**} is actually served), and that {@code viewUrl} is app-relative —
 * the point of the HLS-proxy feature: browsers are never handed mediamtx's
 * own address.
 *
 * <p>Extended for docs/plans/active/MEDIA-SOT-PLAN.md wave M7: the top-level bean is now a {@link
 * PublisherRouter} wrapping {@code MediamtxStreamPublisher} (D1 — {@code
 * vision.publish.source-proxy.enabled} defaults to {@code false}, so the router never routes anywhere
 * else). {@code defaultConfigurationSelectsPublisherRouterWrappingMediamtxPublisher} (renamed from
 * {@code defaultConfigurationSelectsMediamtxPublisher}) asserts the new top-level type; every
 * URL-shape assertion below is unchanged and still exercises the same {@code MediamtxStreamPublisher}
 * instance, reached through the router's own "no {@code streamStarted} call for this id yet ⇒ fall
 * back to the direct publisher" degrade rule (see {@code PublisherRouter}'s own javadoc) — proof that
 * the wrap is behaviourally invisible by default.
 *
 * <p>This context never calls {@link StreamPublisherPort#publish}, so no
 * connection to mediamtx is ever attempted — {@code MediamtxStreamPublisher}
 * only opens its RTSP push lazily, on the first published frame (see
 * docs/plans/done/PHASE1-PLAN.md §3) — so this test stays green without mediamtx
 * running. {@code viewUrl} is a pure function of {@link
 * VisionPublishProperties#viewBase()}, so asserting it needs no running
 * mediamtx either.
 *
 * <p>Extended for docs/main/CYCLES-PLAN.md §5: {@code mjpeg} is asserted alongside {@code sim}/{@code
 * rtsp} as a third registered {@link VideoSourcePort} protocol ({@code MjpegVideoSource},
 * adapter-mjpeg) — the RX half of the mjpeg TX/RX pair.
 *
 * <p>Extended for docs/plans/done/MVP2-PLAN.md X-b: {@code v4l2} (adapter-v4l2's {@code V4l2VideoSource})
 * is asserted as a fourth registered protocol, using the exact descriptor shape {@code
 * adapter-discovery}'s {@code V4l2Scanner} emits ({@code file:/dev/videoN}), not the plan's
 * originally-proposed {@code "usb"}/{@code v4l2://} shape — see adapter-v4l2/MODULE.md.
 */
@SpringBootTest
class PublishWiringTest {

    @Autowired
    private StreamPublisherPort streamPublisherPort;

    @Autowired
    private List<VideoSourcePort> videoSources;

    @Autowired
    private HlsProxyController hlsProxyController;

    @Autowired
    private ReplayFrameExtractionPort replayFrameExtractionPort;

    @Test
    void defaultConfigurationSelectsPublisherRouterWrappingMediamtxPublisher() {
        // docs/plans/active/MEDIA-SOT-PLAN.md wave M7: the router is now always the top-level bean when
        // publish is enabled; every URL-shape test below proves it behaves byte-identically to the
        // plain MediamtxStreamPublisher this method used to assert directly, since
        // vision.publish.source-proxy.enabled defaults to false (D1).
        assertInstanceOf(PublisherRouter.class, streamPublisherPort);
    }

    @Test
    void videoSourcesIncludeSimRtspAndMjpegAdapters() {
        StreamDescriptor simDescriptor = new StreamDescriptor("sim", URI.create("sim://demo"), Map.of());
        StreamDescriptor rtspDescriptor =
                new StreamDescriptor("rtsp", URI.create("rtsp://camera.local:554/stream"), Map.of());
        StreamDescriptor mjpegDescriptor =
                new StreamDescriptor("mjpeg", URI.create("http://camera.local/stream"), Map.of());

        assertTrue(videoSources.stream().anyMatch(source -> source.supports(simDescriptor)),
                "expected a registered VideoSourcePort supporting the sim descriptor");
        assertTrue(videoSources.stream().anyMatch(source -> source.supports(rtspDescriptor)),
                "expected a registered VideoSourcePort supporting the rtsp descriptor");
        assertTrue(videoSources.stream().anyMatch(source -> source.supports(mjpegDescriptor)),
                "expected a registered VideoSourcePort supporting the mjpeg descriptor (docs/main/CYCLES-PLAN.md §5)");
    }

    /**
     * docs/plans/done/MVP2-PLAN.md X-b: the descriptor shape here is deliberately {@code
     * adapter-discovery}'s real {@code V4l2Scanner} emission ({@code protocol="v4l2"}, {@code
     * uri=file:/dev/videoN}), not the plan's originally-proposed {@code "usb"}/{@code v4l2://}
     * shape — see adapter-v4l2/MODULE.md for the full deviation writeup.
     */
    @Test
    void videoSourcesIncludeTheV4l2AdapterUsingDiscoverysActualDescriptorShape() {
        StreamDescriptor v4l2Descriptor = new StreamDescriptor("v4l2", URI.create("file:/dev/video0"), Map.of());

        assertTrue(videoSources.stream().anyMatch(source -> source.supports(v4l2Descriptor)),
                "expected a registered VideoSourcePort supporting the v4l2 descriptor "
                        + "(docs/plans/done/MVP2-PLAN.md X-b, matching adapter-discovery's V4l2Scanner emission)");
    }

    @Test
    void hlsProxyControllerBeanExists() {
        assertNotNull(hlsProxyController, "expected HlsProxyController to be wired so /hls/** is actually served");
    }

    @Test
    void viewUrlIsAppRelativeNotMediamtxsOwnAddress() {
        StreamId streamId = StreamId.random();

        Optional<URI> viewUrl = streamPublisherPort.viewUrl(streamId);

        assertTrue(viewUrl.isPresent());
        assertEquals("/hls/" + streamId.value() + "/index.m3u8", viewUrl.get().toString(),
                "viewUrl must be this app's own /hls/** route, per VisionPublishProperties#viewBase's default "
                        + "-- mediamtx's own hls-base must never reach a viewer");
    }

    /**
     * docs/plans/done/MVP2-PLAN.md §L: unlike {@code viewUrl}, {@code whepUrl} is deliberately mediamtx's own
     * address, not app-relative (see {@code VisionPublishProperties}'s "WHEP has no third base"
     * javadoc section) — the default is the host-mode {@code whep-base} in {@code
     * application.yaml} (port 18889, matching {@code docker-compose.yml}'s host mapping).
     */
    @Test
    void whepUrlIsMediamtxsOwnAddressPerHostModeDefault() {
        StreamId streamId = StreamId.random();

        Optional<URI> whepUrl = streamPublisherPort.whepUrl(streamId);

        assertTrue(whepUrl.isPresent());
        assertEquals("http://localhost:18889/" + streamId.value() + "/whep", whepUrl.get().toString(),
                "whepUrl must be VisionPublishProperties.Mediamtx#whepBase's default, handed to the "
                        + "viewer verbatim -- WHEP has no app-relative proxy the way HLS does");
    }

    /**
     * docs/plans/done/CV-TRAINING-V2-PLAN.md §7/§I: {@code streamPublisherPort} now takes the 4-arg {@code
     * MediamtxStreamPublisher} constructor with an explicit {@code
     * VisionPublishProperties.Mediamtx#playbackBase()} rather than the 3-arg overload's old guess
     * derived from {@code whepBase}'s host — asserting {@code playbackUrl}'s host:port here proves
     * the property's own default ({@code http://localhost:19996}) reproduces that guess exactly, so
     * the default deployment's behavior is unchanged.
     */
    @Test
    void playbackUrlUsesThePlaybackBasePropertysDefaultHostAndPort() {
        StreamId streamId = StreamId.random();
        Instant start = Instant.parse("2026-08-01T10:00:00Z");

        Optional<URI> playbackUrl = streamPublisherPort.playbackUrl(streamId, start, Duration.ofSeconds(5));

        assertTrue(playbackUrl.isPresent());
        assertTrue(playbackUrl.get().toString().startsWith("http://localhost:19996/get?path=" + streamId.value()),
                "playbackUrl must be built against VisionPublishProperties.Mediamtx#playbackBase's default host:port, "
                        + "got " + playbackUrl.get());
    }

    /**
     * docs/plans/done/CV-TRAINING-V2-PLAN.md §7: the replay-capture frame extractor is wired the same
     * {@code vision.publish.enabled} on/off split as {@code streamPublisherPort} itself, since a
     * replay frame can only ever come from a recording mediamtx publishing produced.
     */
    @Test
    void defaultConfigurationSelectsTheMediamtxReplayFrameExtractor() {
        assertInstanceOf(MediamtxReplayFrameExtractor.class, replayFrameExtractionPort);
    }
}
