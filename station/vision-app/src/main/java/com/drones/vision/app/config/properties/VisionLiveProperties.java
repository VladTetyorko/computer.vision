package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the server-push data plane ({@code vision.live.*}, docs/plans/done/REALTIME-PLAN.md §4,
 * item 4).
 *
 * <p>Selected by {@code wiring.ApplicationServiceWiring#liveUpdatePublisherPort}: {@link #enabled()}
 * {@code true} (the default) wires the real {@code com.drones.vision.api.live.LiveUpdateRegistry}
 * (also where {@code /api/live}'s own {@code @ConditionalOnProperty} reads the exact same {@code
 * vision.live.enabled} key directly, so the endpoint and the port bean are always gated together);
 * {@code false} wires the devsupport {@code NoopLiveUpdatePublisher} instead, and {@code
 * /api/live} 404s (its controller/registry beans are conditionally absent entirely).
 *
 * <p>{@link #streamStatePush()} (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md &sect;3.2 C6) gates
 * {@code wiring.ApplicationServiceWiring#streamStateObserver} independently of {@link #enabled()}
 * being on at all — both must be {@code true} for a computed {@code StreamState} transition to
 * republish a {@code devices} snapshot; either off falls back to {@code StreamStateObserver#NOOP}.
 *
 * @param enabled          whether the live-update SSE endpoint/registry is active; default {@code true}
 * @param streamStatePush  whether a computed stream-state transition republishes a {@code devices}
 *                         snapshot; defaulted as a whole when absent
 */
@ConfigurationProperties(prefix = "vision.live")
public record VisionLiveProperties(@DefaultValue("true") boolean enabled, StreamStatePush streamStatePush) {

    @ConstructorBinding
    public VisionLiveProperties {
        if (streamStatePush == null) {
            streamStatePush = new StreamStatePush(StreamStatePush.DEFAULT_ENABLED);
        }
    }

    /**
     * @param enabled whether {@code StreamStateObserver} republishes a {@code devices} snapshot on
     *                every computed {@code StreamState} transition; default {@code true}
     */
    public record StreamStatePush(@DefaultValue("true") boolean enabled) {
        static final boolean DEFAULT_ENABLED = true;
    }
}
