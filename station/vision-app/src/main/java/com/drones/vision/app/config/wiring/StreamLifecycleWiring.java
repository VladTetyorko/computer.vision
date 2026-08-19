package com.drones.vision.app.config.wiring;

import com.drones.vision.adapter.publishhls.MediamtxReaderProbe;
import com.drones.vision.api.live.LiveHlsAndReaderVideoDemand;
import com.drones.vision.api.live.LiveUpdateRegistry;
import com.drones.vision.app.config.properties.VisionPublishProperties;
import com.drones.vision.app.config.properties.VisionStreamsProperties;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.application.pipeline.UsageTracker;
import com.drones.vision.perception.application.stream.IdleStreamReaper;
import com.drones.vision.perception.application.stream.StreamService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Wires the idle-stream policy (docs/plans/active/STREAM-STATE-PLAN.md &sect;3.2) &mdash; the answer to
 * "who ends a stream nobody is watching", which before this wave was nobody.
 *
 * <p>Every bean here is deliberately assembled from narrow seams rather than whole collaborators, so
 * the domain-side classes ({@code IdleStreamReaper}, {@code LiveHlsAndReaderVideoDemand}) stay
 * testable with plain lambdas &mdash; the same idiom {@link CvWiring#detectionDemandPort} already
 * uses for its own registry/pose predicates.
 *
 * @see IdleStreamReaper
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VisionStreamsProperties.class)
public class StreamLifecycleWiring {

    /**
     * Present under exactly the condition {@code PublishWiring#mediamtxStreamPublisher} uses: no
     * mediamtx in this deployment means no reader count to ask about. Its absence is not a
     * degradation of the policy so much as a smaller world &mdash; with no mediamtx there are also no
     * WHEP/RTSP viewers for it to have missed.
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.publish", name = "enabled", havingValue = "true", matchIfMissing = true)
    public MediamtxReaderProbe mediamtxReaderProbe(VisionPublishProperties publishProperties) {
        VisionPublishProperties.Mediamtx mediamtx = publishProperties.mediamtx();
        return new MediamtxReaderProbe(mediamtx.apiBase(), mediamtx.apiUser(), mediamtx.apiPassword());
    }

    /**
     * The union of every video-demand signal this deployment can observe.
     *
     * <p>Always created, even when the idle policy is off: {@code HlsProxyController} takes it as an
     * optional collaborator and stamping demand costs a map write, while making the bean conditional
     * would mean the policy could not be turned on at runtime without also having been wired at
     * startup.
     *
     * <p>{@code liveUpdateRegistry} resolves eagerly here (it does not depend back on {@code
     * StreamService}); the reader probe resolves eagerly too and simply contributes {@code false}
     * when absent.
     */
    @Bean
    public LiveHlsAndReaderVideoDemand videoDemandPort(VisionStreamsProperties streamsProperties,
            @Qualifier("liveUpdateRegistry") ObjectProvider<LiveUpdateRegistry> liveUpdateRegistry,
            ObjectProvider<MediamtxReaderProbe> mediamtxReaderProbe) {
        LiveUpdateRegistry registry = liveUpdateRegistry.getIfAvailable();
        Predicate<AssetId> watchingAsset = registry == null ? assetId -> false : registry::watchingAsset;
        MediamtxReaderProbe probe = mediamtxReaderProbe.getIfAvailable();
        Predicate<StreamId> hasReaders = probe == null ? streamId -> false : probe::hasReaders;
        return new LiveHlsAndReaderVideoDemand(watchingAsset, streamsProperties.idle().demandTtl(), hasReaders);
    }

    /**
     * The reaper itself. {@code initMethod = "start"} because {@link IdleStreamReaper#start()} is
     * where the "disabled" branch lives — the policy is expressed by the property, never by omitting
     * the call. Spring calls {@link IdleStreamReaper#close()} on shutdown via {@code AutoCloseable}.
     *
     * <p>{@code usageTracker} is resolved lazily inside the resolver lambda, not eagerly here: {@code
     * UsageTracker} sits on {@code StreamService}'s own construction path, and forcing it while this
     * bean's {@code StreamService} dependency is still being built is the exact circular reference
     * {@code CvWiring#detectionDemandPort}'s own javadoc records having tripped over.
     */
    @Bean(initMethod = "start")
    public IdleStreamReaper idleStreamReaper(StreamService streamService,
            LiveHlsAndReaderVideoDemand videoDemandPort, VisionStreamsProperties streamsProperties,
            ObjectProvider<UsageTracker> usageTracker) {
        Function<DeviceId, AssetId> assetResolver = deviceId -> {
            UsageTracker tracker = usageTracker.getIfAvailable();
            return tracker == null ? null : tracker.resolveAsset(deviceId).orElse(null);
        };
        return new IdleStreamReaper(streamService, videoDemandPort, streamsProperties.idle().toPolicy(),
                assetResolver);
    }
}
