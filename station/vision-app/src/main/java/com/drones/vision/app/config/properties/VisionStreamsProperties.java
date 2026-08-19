package com.drones.vision.app.config.properties;

import com.drones.vision.perception.application.stream.IdleStreamPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Stream lifecycle policy ({@code vision.streams.*}, docs/plans/active/STREAM-STATE-PLAN.md &sect;2.7) —
 * today only the idle policy that stops streams nobody is watching.
 *
 * <p><b>Its own root, not {@code vision.pipeline.*}.</b> Those keys tune how one pipeline processes
 * frames; these decide whether a stream should exist at all. The distinction earned itself during S2,
 * when {@code video-stale-after} was moved the other way for the same reason.
 *
 * @param idle when to stop a stream nobody is watching
 */
@ConfigurationProperties(prefix = "vision.streams")
public record VisionStreamsProperties(@DefaultValue Idle idle) {

    public VisionStreamsProperties {
        idle = idle == null ? new Idle(true, null, null, null) : idle;
    }

    /**
     * @param enabled      whether idle streams are stopped at all. <b>Defaults on</b>: the measured
     *                     cost of the omission it fixes was ~2.9 cores burning indefinitely for
     *                     nobody, and every uncertainty inside the policy already resolves toward
     *                     keeping a stream alive
     * @param timeout      how long with no observed video demand before a stream is stopped; default
     *                     {@value #DEFAULT_TIMEOUT}. Generous on purpose — an operator who steps away
     *                     from a cockpit for a few minutes must find the stream still there
     * @param checkInterval how often idleness is re-evaluated; default {@value #DEFAULT_CHECK_INTERVAL}.
     *                     Each tick costs one demand lookup per running stream, and the mediamtx term
     *                     of that lookup is an HTTP call
     * @param demandTtl    how long a request through the app's own HLS proxy keeps counting as demand;
     *                     default {@value #DEFAULT_DEMAND_TTL}. Must comfortably exceed the HLS segment
     *                     duration, or a viewer would stop counting between two segment fetches
     */
    public record Idle(@DefaultValue("true") boolean enabled,
                        @DefaultValue(Idle.DEFAULT_TIMEOUT) Duration timeout,
                        @DefaultValue(Idle.DEFAULT_CHECK_INTERVAL) Duration checkInterval,
                        @DefaultValue(Idle.DEFAULT_DEMAND_TTL) Duration demandTtl) {

        static final String DEFAULT_TIMEOUT = "10m";
        static final String DEFAULT_CHECK_INTERVAL = "30s";
        static final String DEFAULT_DEMAND_TTL = "30s";

        public Idle {
            timeout = timeout == null ? Duration.parse("PT10M") : timeout;
            checkInterval = checkInterval == null ? Duration.parse("PT30S") : checkInterval;
            demandTtl = demandTtl == null ? Duration.parse("PT30S") : demandTtl;
        }

        /** The domain-facing shape; validation (positive, interval &le; timeout) lives in that record. */
        public IdleStreamPolicy toPolicy() {
            return new IdleStreamPolicy(enabled, timeout, checkInterval);
        }
    }
}
