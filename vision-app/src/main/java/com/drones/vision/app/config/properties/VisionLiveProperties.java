package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
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
 * @param enabled whether the live-update SSE endpoint/registry is active; default {@code true}
 */
@ConfigurationProperties(prefix = "vision.live")
public record VisionLiveProperties(@DefaultValue("true") boolean enabled) {
}
