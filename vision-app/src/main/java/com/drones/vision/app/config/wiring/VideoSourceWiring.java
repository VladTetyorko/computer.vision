package com.drones.vision.app.config.wiring;

import com.drones.vision.adapter.mjpeg.MjpegSettings;
import com.drones.vision.adapter.mjpeg.MjpegVideoSource;
import com.drones.vision.adapter.rtsp.FfmpegSettings;
import com.drones.vision.adapter.rtsp.FfmpegVideoSource;
import com.drones.vision.adapter.simulation.SimulatedVideoSource;
import com.drones.vision.adapter.simulation.VideoSettings;
import com.drones.vision.adapter.v4l2.V4l2VideoSource;
import com.drones.vision.app.config.properties.VisionMjpegProperties;
import com.drones.vision.app.config.properties.VisionRtspProperties;
import com.drones.vision.app.config.properties.VisionSimulationProperties;
import com.drones.vision.app.config.properties.VisionV4l2Properties;
import com.drones.vision.application.pipeline.VideoSourceRegistry;
import com.drones.vision.domain.port.out.VideoSourcePort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.OptionalInt;

/**
 * Wires every {@link VideoSourcePort} adapter (RX video ingest) — the video-source slice of what
 * used to be one 825-line {@code WiringConfiguration} (docs/plans/active/LAYERING-REFACTOR-PLAN.md wave D).
 * Config extraction (wave F1): {@link #ffmpegVideoSource}/{@link #mjpegVideoSource}/{@link
 * #v4l2VideoSource}/{@link #simulatedVideoSource} each now build their adapter's plain settings
 * record from a {@code vision.<protocol>.*} properties record instead of the adapter's own
 * no-arg/hardcoded-default constructor — every mapped default is byte-identical to the literal it
 * replaces.
 */
@Configuration
@EnableConfigurationProperties({VisionSimulationProperties.class, VisionRtspProperties.class,
        VisionMjpegProperties.class, VisionV4l2Properties.class})
public class VideoSourceWiring {

    @Bean
    public SimulatedVideoSource simulatedVideoSource(VisionSimulationProperties properties) {
        VisionSimulationProperties.Video video = properties.video();
        return new SimulatedVideoSource(new VideoSettings(video.width(), video.height(), video.fps()));
    }

    @Bean
    public FfmpegVideoSource ffmpegVideoSource(VisionRtspProperties properties) {
        return new FfmpegVideoSource(toFfmpegSettings(properties));
    }

    /** Shared with {@code FeedTransmitterWiring#rtspFeedTransmitter}, which needs the identical mapping. */
    static FfmpegSettings toFfmpegSettings(VisionRtspProperties properties) {
        VisionRtspProperties.Transmit transmit = properties.transmit();
        return new FfmpegSettings(properties.transport(), properties.openTimeout(), properties.probesizeBytes(),
                properties.analyzeDuration(), properties.reorderQueueSize(), properties.maxDelay(),
                properties.udpTimeout(), properties.udpFifoSizePackets(), properties.srtLatency(),
                properties.publisherBufferCapacity(), properties.closeJoinTimeout(),
                new FfmpegSettings.Transmit(transmit.gopSeconds(), transmit.preset(), transmit.fallbackFps(),
                        transmit.connectTimeout(), transmit.tune()));
    }

    /**
     * RX half of the mjpeg TX/RX pair (docs/main/CYCLES-PLAN.md §5): ingests the {@code
     * multipart/x-mixed-replace} HTTP stream served by {@code FeedTransmitterWiring#mjpegFeedTransmitter}
     * (or any real MJPEG camera, e.g. an ESP32-CAM) for {@code "mjpeg"}-protocol video devices.
     */
    @Bean
    public MjpegVideoSource mjpegVideoSource(VisionMjpegProperties properties) {
        return new MjpegVideoSource(toMjpegSettings(properties));
    }

    /** Shared with {@code FeedTransmitterWiring#mjpegFeedTransmitter}, which needs the identical mapping. */
    static MjpegSettings toMjpegSettings(VisionMjpegProperties properties) {
        VisionMjpegProperties.Transmit transmit = properties.transmit();
        OptionalInt maxViewerThreads = transmit.maxViewerThreads() == null
                ? OptionalInt.empty() : OptionalInt.of(transmit.maxViewerThreads());
        return new MjpegSettings(properties.readTimeout(), properties.publisherBufferCapacity(),
                properties.closeJoinTimeout(),
                new MjpegSettings.Transmit(transmit.bindHost(), maxViewerThreads, transmit.jpegQuality(),
                        transmit.loop(), transmit.viewerJoinTimeout()));
    }

    /**
     * USB/V4L2 local camera ingest (docs/plans/done/MVP2-PLAN.md X-b), RX only (a local capture device has
     * no wire to transmit to -- same as {@code sim}/{@code file}). Supports protocol {@code
     * "v4l2"} with a {@code file:} URI naming a {@code /dev/videoN} node -- the exact shape
     * {@code adapter-discovery}'s {@code V4l2Scanner} emits, not the docs/plans/done/MVP2-PLAN.md brief's
     * originally-proposed {@code "usb"}/{@code v4l2://} shape; see adapter-v4l2/MODULE.md.
     */
    @Bean
    public V4l2VideoSource v4l2VideoSource(VisionV4l2Properties properties) {
        return new V4l2VideoSource(properties.publisherBufferCapacity(), properties.closeJoinTimeout().toMillis());
    }

    @Bean
    public VideoSourceRegistry videoSourceRegistry(List<VideoSourcePort> videoSources) {
        return new VideoSourceRegistry(videoSources);
    }
}
