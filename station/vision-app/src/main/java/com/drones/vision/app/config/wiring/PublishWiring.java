package com.drones.vision.app.config.wiring;

import com.drones.vision.adapter.overlay.Java2DOverlayRenderer;
import com.drones.vision.adapter.overlay.OverlaySettings;
import com.drones.vision.adapter.publishhls.MediamtxLiveFrameGrabber;
import com.drones.vision.adapter.publishhls.MediamtxProxyPublisher;
import com.drones.vision.adapter.publishhls.MediamtxProxySettings;
import com.drones.vision.adapter.publishhls.MediamtxReplayFrameExtractor;
import com.drones.vision.adapter.publishhls.MediamtxStreamPublisher;
import com.drones.vision.adapter.publishhls.PublishSettings;
import com.drones.vision.adapter.publishhls.PublisherRouter;
import com.drones.vision.api.live.LiveUpdateRegistry;
import com.drones.vision.api.proxy.HlsProxyController;
import com.drones.vision.api.support.SnapshotJpegEncoder;
import com.drones.vision.app.config.properties.VisionApiProperties;
import com.drones.vision.app.config.properties.VisionCvProperties;
import com.drones.vision.app.config.properties.VisionOverlayProperties;
import com.drones.vision.app.config.properties.VisionPublishProperties;
import com.drones.vision.app.devsupport.NoopReplayFrameExtractor;
import com.drones.vision.app.devsupport.NoopStreamPublisher;
import com.drones.vision.perception.domain.port.OverlayPort;
import com.drones.vision.events.domain.port.ReplayFrameExtractionPort;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/**
 * Wires stream egress (mediamtx publish/replay), detection/OSD overlay burn-in, and the snapshot
 * JPEG encoder — the publish slice of what used to be one 825-line {@code WiringConfiguration}
 * (docs/plans/active/LAYERING-REFACTOR-PLAN.md wave D). Config extraction (wave F3): {@link #overlayRenderer}
 * now takes an {@code OverlaySettings} built from {@code vision.overlay.*}; {@link
 * #streamPublisherPort} now also threads {@code vision.publish.encoder.*}/{@code .resilience.*}/
 * {@code .cadence.*} through a {@code PublishSettings}; {@link #replayFrameExtractionPort} threads
 * {@code vision.publish.replay.*} — every mapped default is byte-identical to the literal it
 * replaced. {@link #snapshotJpegEncoder} (wave D/B closeout) makes {@code
 * com.drones.vision.api.support.SnapshotJpegEncoder} a real, property-bound bean instead of {@code
 * StreamController}/{@code DeviceProbeController} each self-constructing one from {@code
 * com.drones.vision.api.support.VisionApiProperties.defaults()}. {@link #hlsProxySettings}/{@link
 * #liveSettings} (docs/plans/active/SCALE-100-PLAN.md §5 S7) finish that same extraction for {@link
 * HlsProxyController}/{@link LiveUpdateRegistry} — the two classes whose settings the S1/S2 waves
 * deliberately left as local constants, reserving this file's bridge for this wave.
 *
 * <h2>Two {@code VisionApiProperties} types — deliberately, see {@link VisionApiProperties}'s own
 * javadoc</h2>
 * This class's {@link #snapshotJpegEncoder} is the one place in the codebase that needs both: this
 * package's Spring-bound {@link VisionApiProperties} (imported plain) and {@code vision-api}'s own
 * plain mirror {@code com.drones.vision.api.support.VisionApiProperties} (always referenced fully
 * qualified below, never imported, to avoid a simple-name collision).
 */
@Configuration
@EnableConfigurationProperties({VisionOverlayProperties.class, VisionPublishProperties.class,
        VisionApiProperties.class, VisionCvProperties.class})
public class PublishWiring {

    /**
     * Burns detection boxes/labels (and, once a telemetry input reaches {@code
     * com.drones.vision.perception.application.pipeline.StreamPipeline}, a telemetry OSD) onto published frames
     * (docs/plans/done/MVP1-PLAN.md §C8 bullets 1-2). Threaded into {@code
     * ApplicationServiceWiring#streamService} below.
     */
    @Bean
    public OverlayPort overlayRenderer(VisionOverlayProperties properties) {
        return new Java2DOverlayRenderer(new OverlaySettings(properties.jpegQuality(), properties.minStrokeWidth(),
                properties.strokeDivisor(), properties.minFontSize(), properties.fontDivisor(),
                properties.osdBackgroundAlpha(), properties.osdMargin()));
    }

    /**
     * Selects the {@link StreamPublisherPort} implementation per {@link
     * VisionPublishProperties#enabled()}: {@code false} falls back to the no-op publisher (e.g.
     * running or testing without mediamtx), exactly as before. {@code true} (the default) now builds a
     * {@link PublisherRouter} (docs/plans/active/MEDIA-SOT-PLAN.md D3) wrapping {@code
     * MediamtxStreamPublisher} (today's publisher, unchanged — {@code PublishSettings},
     * docs/plans/active/LAYERING-REFACTOR-PLAN.md wave F3, still carries the encoder/resilience/cadence
     * tunables that used to be {@code H264RecorderFactory}/{@code PublishBackoff}/{@code
     * CadenceEstimator}'s own hardcoded constants) and {@link MediamtxProxyPublisher} — the router
     * itself decides, per device, which one actually handles a stream (see its own javadoc), so this
     * stays the <em>one</em> {@link StreamPublisherPort} bean regardless of {@link
     * VisionPublishProperties.SourceProxy#enabled()}'s value. With that flag at its default {@code
     * false} (D1), the router never routes anywhere but the direct publisher, so runtime behaviour is
     * byte-identical to the plain {@code MediamtxStreamPublisher} bean this method used to return.
     *
     * <p>Fails fast (docs/plans/active/MEDIA-SOT-PLAN.md §3's legal-combination table) on the one
     * rejected row — see {@link #rejectProxyWithPushTransport}.
     */
    @Bean
    public StreamPublisherPort streamPublisherPort(VisionPublishProperties properties,
                                                    VisionCvProperties cvProperties) {
        rejectProxyWithPushTransport(properties, cvProperties);
        if (!properties.enabled()) {
            return new NoopStreamPublisher();
        }
        VisionPublishProperties.Mediamtx mediamtx = properties.mediamtx();
        StreamPublisherPort directPublisher = new MediamtxStreamPublisher(mediamtx.rtspBase(), properties.viewBase(),
                mediamtx.whepBase(), mediamtx.playbackBase(), toPublishSettings(properties));
        StreamPublisherPort proxyPublisher = new MediamtxProxyPublisher(mediamtx.apiBase(), properties.viewBase(),
                mediamtx.whepBase(), mediamtx.playbackBase(), toProxySettings(properties));
        return new PublisherRouter(directPublisher, proxyPublisher, properties.sourceProxy().enabled());
    }

    /**
     * docs/plans/active/MEDIA-SOT-PLAN.md §3's rejected legal-combination row (A=proxy, B=push):
     * checked once here, at wiring time, rather than per-stream-start inside {@code
     * DefaultStreamService} — that class lives in {@code vision-application}, a module this wave does
     * not touch. {@code vision.publish.source-proxy.enabled=true} declares this deployment's intent to
     * let mediamtx dial at least one RTSP camera directly (D3); with {@code
     * vision.cv.frame-transport} left at {@code push}, such a stream's video never reaches this JVM at
     * all (D4), so there would be nothing for push-mode detection to send cv-service — a stream that
     * can never detect. Both flags default to the legal (A={@code false}, B={@code push}) row, so this
     * never fires in the default configuration (D1).
     */
    private static void rejectProxyWithPushTransport(VisionPublishProperties properties,
                                                      VisionCvProperties cvProperties) {
        if (properties.sourceProxy().enabled() && !cvProperties.pullEnabled()) {
            throw new IllegalStateException(
                    "vision.publish.source-proxy.enabled=true (mediamtx dials the camera itself) requires "
                            + "vision.cv.frame-transport=pull -- a proxied source means this JVM never holds a "
                            + "video frame (docs/plans/active/MEDIA-SOT-PLAN.md D4), so leaving "
                            + "vision.cv.frame-transport=push (its current value) would start a stream that can "
                            + "never detect: there would be nothing for push mode to send cv-service. Set "
                            + "vision.cv.frame-transport=pull, or leave vision.publish.source-proxy.enabled=false.");
        }
    }

    private static PublishSettings toPublishSettings(VisionPublishProperties properties) {
        VisionPublishProperties.Encoder encoder = properties.encoder();
        VisionPublishProperties.Resilience resilience = properties.resilience();
        VisionPublishProperties.Cadence cadence = properties.cadence();
        return new PublishSettings(
                new PublishSettings.Encoder(encoder.crf(), encoder.maxrateBps(), encoder.bufsizeBits(),
                        encoder.preset(), encoder.gopSeconds(), encoder.scenecutThreshold()),
                new PublishSettings.Resilience(resilience.initialBackoff(), resilience.maxBackoff()),
                new PublishSettings.Cadence(cadence.measurementFrames(), cadence.minMeasuredFps(),
                        cadence.maxMeasuredFps(), cadence.driftRatioHigh(), cadence.driftEwmaAlpha(),
                        cadence.sustainedDriftWindow(), cadence.defaultFrameRateFps()));
    }

    /**
     * Maps {@link VisionPublishProperties.SourceProxy} + the API-credential pair off {@link
     * VisionPublishProperties.Mediamtx} onto {@link MediamtxProxySettings} — the same "this record
     * maps onto that adapter settings object" shape {@link #toPublishSettings} already has.
     */
    private static MediamtxProxySettings toProxySettings(VisionPublishProperties properties) {
        VisionPublishProperties.SourceProxy sourceProxy = properties.sourceProxy();
        VisionPublishProperties.Mediamtx mediamtx = properties.mediamtx();
        return new MediamtxProxySettings(sourceProxy.rtspTransport(), sourceProxy.readyTimeout(),
                sourceProxy.onDemand(), mediamtx.apiUser(), mediamtx.apiPassword());
    }

    /**
     * On-demand live-frame grab against mediamtx's own RTSP output (docs/plans/active/MEDIA-SOT-PLAN.md
     * waves M6/M7) — the collaborator {@code ApplicationServiceWiring#streamService} wraps {@code
     * DefaultStreamService} with ({@code com.drones.vision.app.stream.LiveFrameFallbackStreamService})
     * when {@link VisionPublishProperties.SourceProxy#enabled()} is {@code true}, so the snapshot
     * endpoint and training-sample capture still return a real frame for a proxied stream, where the
     * pipeline's own cache is permanently empty (D4 — no {@code VideoSourcePort} was ever opened).
     *
     * <p>Unconditional (unlike {@link #streamPublisherPort}/{@link #replayFrameExtractionPort}):
     * building one is cheap (no I/O — {@link MediamtxLiveFrameGrabber#grab} is what actually dials
     * mediamtx, lazily, per call), so there is no reason to make its presence track {@link
     * VisionPublishProperties#enabled()} the way an actual publisher/extractor implementation must.
     */
    @Bean
    public MediamtxLiveFrameGrabber mediamtxLiveFrameGrabber(VisionPublishProperties properties) {
        return new MediamtxLiveFrameGrabber(properties.mediamtx().rtspBase());
    }

    /**
     * Selects the {@link ReplayFrameExtractionPort} implementation per {@link
     * VisionPublishProperties#enabled()} — the same if/else split {@link #streamPublisherPort}
     * makes. {@code replay.window}/{@code replay.read-timeout} (docs/plans/active/LAYERING-REFACTOR-PLAN.md
     * wave F3) replace {@code MediamtxReplayFrameExtractor}'s own hardcoded 1s/15s constants.
     */
    @Bean
    public ReplayFrameExtractionPort replayFrameExtractionPort(VisionPublishProperties properties) {
        if (properties.enabled()) {
            VisionPublishProperties.Replay replay = properties.replay();
            return new MediamtxReplayFrameExtractor(properties.mediamtx().playbackBase(), replay.window(),
                    replay.readTimeout());
        }
        return new NoopReplayFrameExtractor();
    }

    /**
     * The upstream {@code HlsProxyController} (component-scanned from {@code vision-api}) forwards
     * {@code /hls/**} requests to — mediamtx's actual HLS egress address. Supplied as a bean
     * (rather than {@code HlsProxyController} itself being constructed here) so the controller
     * stays a plain component-scanned bean, with only its {@link URI} collaborator wired here.
     */
    @Bean
    public URI hlsProxyUpstreamBase(VisionPublishProperties properties) {
        return properties.mediamtx().hlsBase();
    }

    /**
     * {@code GET /api/streams/{streamId}/snapshot} (docs/plans/done/MVP3-PLAN.md C-a) and {@code POST
     * /api/devices/probe}'s downscale/encode collaborator (docs/plans/active/LAYERING-REFACTOR-PLAN.md wave D) —
     * a real bean, mapped from {@link VisionApiProperties#snapshot()}, replacing {@code
     * StreamController}/{@code DeviceProbeController}'s own {@code
     * VisionApiProperties.defaults()} stopgap construction.
     */
    @Bean
    public SnapshotJpegEncoder snapshotJpegEncoder(VisionApiProperties properties) {
        return new SnapshotJpegEncoder(toApiSupportProperties(properties));
    }

    /**
     * {@link HlsProxyController}'s upstream {@code HttpClient} timeouts and buffer/redirect bounds
     * (docs/plans/active/SCALE-100-PLAN.md §5 S7) — mapped from {@link VisionApiProperties#hlsProxy()}
     * the same way {@link #snapshotJpegEncoder} maps {@code snapshot}. A plain nested-record bean
     * (not the whole bridged {@code com.drones.vision.api.support.VisionApiProperties}) since that is
     * all the controller's own {@code @Autowired} constructor declares.
     */
    @Bean
    public com.drones.vision.api.support.VisionApiProperties.HlsProxy hlsProxySettings(VisionApiProperties properties) {
        return toApiSupportProperties(properties).hlsProxy();
    }

    /**
     * {@link LiveUpdateRegistry}'s coalesce/heartbeat cadence, per-topic ring-buffer capacities, and
     * per-connection dispatch bounds (docs/plans/active/SCALE-100-PLAN.md §5 S7) — mapped from {@link
     * VisionApiProperties#live()}, same shape as {@link #hlsProxySettings}.
     */
    @Bean
    public com.drones.vision.api.support.VisionApiProperties.Live liveSettings(VisionApiProperties properties) {
        return toApiSupportProperties(properties).live();
    }

    private static com.drones.vision.api.support.VisionApiProperties toApiSupportProperties(
            VisionApiProperties properties) {
        VisionApiProperties.Snapshot snapshot = properties.snapshot();
        VisionApiProperties.HlsProxy hlsProxy = properties.hlsProxy();
        VisionApiProperties.Live live = properties.live();
        VisionApiProperties.Paging paging = properties.paging();
        VisionApiProperties.Upload upload = properties.upload();
        return new com.drones.vision.api.support.VisionApiProperties(
                new com.drones.vision.api.support.VisionApiProperties.Snapshot(snapshot.maxWidth(), snapshot.jpegQuality()),
                new com.drones.vision.api.support.VisionApiProperties.HlsProxy(hlsProxy.connectTimeout(),
                        hlsProxy.requestTimeout(), hlsProxy.errorBodyPreviewMaxChars(), hlsProxy.maxRedirectHops()),
                new com.drones.vision.api.support.VisionApiProperties.Live(live.coalesce(), live.heartbeat(),
                        live.telemetryBuffer(), live.eventBuffer(), live.detectionBuffer(), live.mapBuffer(),
                        live.sendTimeout(), live.bufferEviction()),
                new com.drones.vision.api.support.VisionApiProperties.Paging(paging.defaultLimit(), paging.maxLimit()),
                new com.drones.vision.api.support.VisionApiProperties.Upload(upload.maxImageBytes()));
    }
}
