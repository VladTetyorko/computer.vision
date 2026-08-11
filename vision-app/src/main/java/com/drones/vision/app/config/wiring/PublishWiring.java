package com.drones.vision.app.config.wiring;

import com.drones.vision.adapter.overlay.Java2DOverlayRenderer;
import com.drones.vision.adapter.overlay.OverlaySettings;
import com.drones.vision.adapter.publishhls.MediamtxReplayFrameExtractor;
import com.drones.vision.adapter.publishhls.MediamtxStreamPublisher;
import com.drones.vision.adapter.publishhls.PublishSettings;
import com.drones.vision.api.support.SnapshotJpegEncoder;
import com.drones.vision.app.config.properties.VisionApiProperties;
import com.drones.vision.app.config.properties.VisionOverlayProperties;
import com.drones.vision.app.config.properties.VisionPublishProperties;
import com.drones.vision.app.devsupport.NoopReplayFrameExtractor;
import com.drones.vision.app.devsupport.NoopStreamPublisher;
import com.drones.vision.domain.port.out.OverlayPort;
import com.drones.vision.domain.port.out.ReplayFrameExtractionPort;
import com.drones.vision.domain.port.out.StreamPublisherPort;
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
 * com.drones.vision.api.support.VisionApiProperties.defaults()}.
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
        VisionApiProperties.class})
public class PublishWiring {

    /**
     * Burns detection boxes/labels (and, once a telemetry input reaches {@code
     * com.drones.vision.application.pipeline.StreamPipeline}, a telemetry OSD) onto published frames
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
     * VisionPublishProperties#enabled()}: the mediamtx-backed publisher (default) pushes RTSP to
     * the mediamtx sidecar; disabling it falls back to the no-op publisher. {@code
     * PublishSettings} (docs/plans/active/LAYERING-REFACTOR-PLAN.md wave F3) carries the encoder/resilience/
     * cadence tunables that used to be {@code H264RecorderFactory}/{@code PublishBackoff}/{@code
     * CadenceEstimator}'s own hardcoded constants.
     */
    @Bean
    public StreamPublisherPort streamPublisherPort(VisionPublishProperties properties) {
        if (properties.enabled()) {
            VisionPublishProperties.Mediamtx mediamtx = properties.mediamtx();
            return new MediamtxStreamPublisher(mediamtx.rtspBase(), properties.viewBase(), mediamtx.whepBase(),
                    mediamtx.playbackBase(), toPublishSettings(properties));
        }
        return new NoopStreamPublisher();
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
                        hlsProxy.requestTimeout()),
                new com.drones.vision.api.support.VisionApiProperties.Live(live.coalesce(), live.heartbeat(),
                        live.telemetryBuffer(), live.eventBuffer(), live.detectionBuffer(), live.marksBuffer()),
                new com.drones.vision.api.support.VisionApiProperties.Paging(paging.defaultLimit(), paging.maxLimit()),
                new com.drones.vision.api.support.VisionApiProperties.Upload(upload.maxImageBytes()));
    }
}
