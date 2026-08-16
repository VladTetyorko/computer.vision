package com.drones.vision.app.config.wiring;

import com.drones.vision.adapter.cvgrpc.CvChannelSupervisor;
import com.drones.vision.adapter.cvgrpc.CvStatusProvider;
import com.drones.vision.adapter.mavlink.MavlinkLinkStatusProvider;
import com.drones.vision.adapter.mavlink.MavlinkTelemetrySource;
import com.drones.vision.adapter.publishhls.MediamtxStreamPublisher;
import com.drones.vision.adapter.publishhls.PublishStatusProvider;
import com.drones.vision.platform.Health;
import com.drones.vision.platform.SubsystemStatus;
import com.drones.vision.platform.SubsystemStatusPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@code List<SubsystemStatusPort>} {@code SystemStatusController} (vision-api,
 * component-scanned) collects into {@code GET /api/system/status} (docs/plans/active/
 * SYSTEM-STATUS-PLAN.md wave S2, §4.2). Three of the four providers are wired here; the fourth
 * ({@code live-updates}) is a pair of component-scanned {@code @Component} beans in vision-api's own
 * {@code com.drones.vision.api.live} package ({@code LiveUpdateStatusProvider}/{@code
 * LiveUpdateStatusDisabledProvider}), mirroring {@link com.drones.vision.api.live.LiveUpdateRegistry
 * LiveUpdateRegistry}'s own gating — see that pair's javadoc.
 *
 * <h2>Two mutually-exclusive beans per gated subsystem, not one + a null check</h2>
 * {@code cv-service} and {@code video-publish} each have an "off by config" state {@code
 * SystemStatusController} must report honestly as {@link Health#DISABLED} (excluded from the overall
 * rollup) rather than silently omit. Rather than one provider bean that reads a boolean flag at
 * request time, this class wires two mutually-exclusive {@code @Bean}s per subsystem — an
 * always-{@link Health#DISABLED} lambda and the real provider — each gated on the <em>exact same</em>
 * property expression the underlying resource's own condition uses ({@link CvWiring#cvGrpcChannel},
 * {@link PublishWiring#mediamtxStreamPublisher}). This repeats the property expression rather than
 * using {@code @ConditionalOnBean} for the same bean-definition-order reason CV-RECONNECT-PLAN.md
 * §3.3 already establishes for {@link CvWiring#cvChannelSupervisor}: {@code @ConditionalOnBean}
 * evaluates against bean <em>definitions</em> already processed at the point a configuration class is
 * parsed, which is sensitive to declaration order across configuration classes and can silently
 * differ between a narrow test slice and full production wiring.
 *
 * <h2>{@code mavlink-link} is unconditional</h2>
 * {@link MavlinkTelemetrySource} (docs/plans/active/DRONE-INFRA-PLAN.md I-a) is always wired as a
 * {@code TelemetrySourcePort} by {@code TelemetryWiring#mavlinkTelemetrySource}, regardless of
 * whether any device actually uses the {@code mavlink} protocol — so its status provider is
 * unconditional too. No claimed vehicle reports {@link Health#UNKNOWN}, not {@link
 * Health#DISABLED}: this deployment may simply have no MAVLink vehicle configured, which is not the
 * same thing as a feature switched off (see {@link MavlinkLinkStatusProvider}'s own javadoc).
 */
@Configuration
public class SystemStatusWiring {

    /** {@code vision.cv.*} left at its defaults — CV never wired at all. */
    @Bean
    @ConditionalOnExpression("!(${vision.cv.enabled:false} or ${vision.training.enabled:false} "
            + "or '${vision.cv.frame-transport:push}' == 'pull')")
    public SubsystemStatusPort cvServiceStatusDisabled() {
        return () -> new SubsystemStatus("cv-service", "CV inference", Health.DISABLED,
                "CV detection is disabled (vision.cv.enabled=false)", null,
                "Set vision.cv.enabled=true to enable detection");
    }

    /** Mirrors {@link CvWiring#cvGrpcChannel}'s own condition exactly — present whenever that channel is. */
    @Bean
    @ConditionalOnExpression("${vision.cv.enabled:false} or ${vision.training.enabled:false} "
            + "or '${vision.cv.frame-transport:push}' == 'pull'")
    public SubsystemStatusPort cvServiceStatus(ObjectProvider<CvChannelSupervisor> cvChannelSupervisor) {
        return new CvStatusProvider(cvChannelSupervisor::getIfAvailable);
    }

    /** See this class's own "{@code mavlink-link} is unconditional" javadoc section. */
    @Bean
    public SubsystemStatusPort mavlinkLinkStatus(MavlinkTelemetrySource mavlinkTelemetrySource) {
        return new MavlinkLinkStatusProvider(mavlinkTelemetrySource::claimedVehicleHealth);
    }

    /** {@code vision.publish.enabled=false} — the {@code NoopStreamPublisher} case. */
    @Bean
    @ConditionalOnProperty(prefix = "vision.publish", name = "enabled", havingValue = "false")
    public SubsystemStatusPort videoPublishStatusDisabled() {
        return () -> new SubsystemStatus("video-publish", "Video publish (mediamtx)", Health.DISABLED,
                "Video publish is disabled (vision.publish.enabled=false)", null,
                "Set vision.publish.enabled=true to enable HLS/WHEP egress");
    }

    /** Mirrors {@link PublishWiring#mediamtxStreamPublisher}'s own condition exactly. */
    @Bean
    @ConditionalOnProperty(prefix = "vision.publish", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SubsystemStatusPort videoPublishStatus(MediamtxStreamPublisher mediamtxStreamPublisher) {
        return new PublishStatusProvider(mediamtxStreamPublisher);
    }
}
