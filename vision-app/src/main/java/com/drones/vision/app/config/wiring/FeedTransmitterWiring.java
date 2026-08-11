package com.drones.vision.app.config.wiring;

import com.drones.vision.adapter.mavlink.MavlinkFeedTransmitter;
import com.drones.vision.adapter.mjpeg.MjpegFeedTransmitter;
import com.drones.vision.adapter.rtsp.RtspFeedTransmitter;
import com.drones.vision.app.config.properties.VisionMavlinkProperties;
import com.drones.vision.app.config.properties.VisionMjpegProperties;
import com.drones.vision.app.config.properties.VisionPublishProperties;
import com.drones.vision.app.config.properties.VisionRcProperties;
import com.drones.vision.app.config.properties.VisionRtspProperties;
import com.drones.vision.application.pipeline.FeedTransmitterRegistry;
import com.drones.vision.perception.domain.port.FeedTransmitterPort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Wires every {@link FeedTransmitterPort} TX simulator (docs/main/CYCLES-PLAN.md §3/§5, docs/plans/done/MVP2-PLAN.md
 * X-a) — the TX slice of what used to be one 825-line {@code WiringConfiguration}
 * (docs/plans/active/LAYERING-REFACTOR-PLAN.md wave D). Config extraction (waves F1/F2): {@link
 * #rtspFeedTransmitter}/{@link #mjpegFeedTransmitter}/{@link #mavlinkFeedTransmitter} each now
 * build their adapter's plain settings record from a {@code vision.<protocol>.*} properties
 * record — every mapped default is byte-identical to the literal it replaced.
 */
@Configuration
@EnableConfigurationProperties({VisionPublishProperties.class, VisionRtspProperties.class,
        VisionMjpegProperties.class, VisionMavlinkProperties.class, VisionRcProperties.class})
public class FeedTransmitterWiring {

    /**
     * TX (transmit) half of docs/main/CYCLES-PLAN.md §3's RX/TX doctrine: pushes a {@code
     * transport=rtsp} simulation's video file to the same mediamtx sidecar {@code
     * PublishWiring#streamPublisherPort} pushes viewer egress to.
     */
    @Bean
    public RtspFeedTransmitter rtspFeedTransmitter(VisionPublishProperties publishProperties,
                                                    VisionRtspProperties rtspProperties) {
        return new RtspFeedTransmitter(publishProperties.mediamtx().rtspBase(),
                VideoSourceWiring.toFfmpegSettings(rtspProperties));
    }

    /**
     * TX half of the mjpeg TX/RX pair (docs/main/CYCLES-PLAN.md §5): serves a {@code transport=mjpeg}
     * simulation's video file as an HTTP {@code multipart/x-mixed-replace} stream on its own
     * ephemeral port. {@code destroyMethod = "close"} so Spring shuts its shared {@code
     * HttpServer}/dispatch pool down on context close.
     */
    @Bean(destroyMethod = "close")
    public MjpegFeedTransmitter mjpegFeedTransmitter(VisionMjpegProperties properties) {
        return new MjpegFeedTransmitter(VideoSourceWiring.toMjpegSettings(properties));
    }

    /**
     * TX half of the MAVLink TX/RX pair (docs/plans/done/MVP2-PLAN.md X-a): emits a synthetic MAVLink 2
     * telemetry stream (HEARTBEAT/SYS_STATUS/GLOBAL_POSITION_INT) driven by a flight route, for
     * zero-hardware rehearsal of {@code TelemetryWiring#mavlinkTelemetrySource} (or any real
     * MAVLink ground station).
     */
    @Bean
    public MavlinkFeedTransmitter mavlinkFeedTransmitter(VisionMavlinkProperties mavlinkProperties,
                                                          VisionRcProperties rcProperties) {
        return new MavlinkFeedTransmitter(TelemetryWiring.toMavlinkSettings(mavlinkProperties, rcProperties));
    }

    /**
     * Selects the {@link FeedTransmitterPort} adapter for a simulation's {@code transport}
     * (docs/main/CYCLES-PLAN.md §5) — the TX-side mirror of {@code VideoSourceWiring#videoSourceRegistry}.
     */
    @Bean
    public FeedTransmitterRegistry feedTransmitterRegistry(List<FeedTransmitterPort> feedTransmitters) {
        return new FeedTransmitterRegistry(feedTransmitters);
    }
}
