package com.drones.vision.adapter.publishhls;

import java.time.Duration;
import java.util.Objects;

/**
 * Plain, framework-free settings for {@link MediamtxProxyPublisher} (docs/plans/done/MEDIA-SOT-PLAN.md
 * &sect;5.5) — mirrors {@link PublishSettings}'s own "vision-app maps application.yaml onto this
 * record" shape; this class must never be constructed from a {@code @ConfigurationProperties} type
 * directly (that would point this module at {@code vision-app}, breaking the hexagon).
 *
 * @param rtspTransport  {@code rtspTransport} sent to mediamtx's Control API when creating/patching
 *                       a path — i.e. how <i>mediamtx</i> dials the camera, not how this JVM talks
 *                       to mediamtx. {@code vision.publish.source-proxy.rtsp-transport}, default
 *                       {@code "automatic"}
 * @param readyTimeout   how long {@link MediamtxProxyPublisher#streamStarted} polls readiness before
 *                       failing the start call — {@code vision.publish.source-proxy.ready-timeout},
 *                       default 10s. Ignored when {@code sourceOnDemand} is {@code true} (see that
 *                       parameter's own doc)
 * @param sourceOnDemand mediamtx {@code sourceOnDemand} for the created path — {@code
 *                       vision.publish.source-proxy.on-demand}, default {@code false} (D10:
 *                       {@code MTX_PATHDEFAULTS_RECORD=yes} records every path, so on-demand would
 *                       make recording depend on somebody watching). When {@code true}, mediamtx
 *                       does not dial the camera until a reader connects, so "ready" has no meaning
 *                       at start time — {@link MediamtxProxyPublisher#streamStarted} skips the
 *                       readiness poll entirely in that case rather than failing every start call
 *                       against a deliberately idle path
 * @param apiUser        optional mediamtx Control API Basic-auth username — {@code
 *                       vision.publish.mediamtx.api-user}, unset by default; {@code null}/blank
 *                       means no {@code Authorization} header is sent (today's default, which 401s
 *                       against mediamtx's own default config, docs/conclusions/CV-PULL-SPIKE.md
 *                       &sect;5 — wave M7 is expected to either mount a widened {@code mediamtx.yml}
 *                       or set this pair)
 * @param apiPassword    password paired with {@code apiUser} — {@code
 *                       vision.publish.mediamtx.api-password}; required (non-blank) whenever {@code
 *                       apiUser} is set, so a half-configured credential pair fails fast at wiring
 *                       time rather than 401ing at the first stream start
 */
public record MediamtxProxySettings(String rtspTransport, Duration readyTimeout, boolean sourceOnDemand,
                                     String apiUser, String apiPassword) {

    private static final String DEFAULT_RTSP_TRANSPORT = "automatic";
    private static final Duration DEFAULT_READY_TIMEOUT = Duration.ofSeconds(10);
    private static final boolean DEFAULT_SOURCE_ON_DEMAND = false;

    public MediamtxProxySettings {
        Objects.requireNonNull(rtspTransport, "rtspTransport must not be null");
        if (rtspTransport.isBlank()) {
            throw new IllegalArgumentException("rtspTransport must not be blank");
        }
        Objects.requireNonNull(readyTimeout, "readyTimeout must not be null");
        if (readyTimeout.isNegative() || readyTimeout.isZero()) {
            throw new IllegalArgumentException("readyTimeout must be positive: " + readyTimeout);
        }
        if (apiUser != null && !apiUser.isBlank() && (apiPassword == null || apiPassword.isBlank())) {
            throw new IllegalArgumentException("apiPassword must be set when apiUser is set");
        }
    }

    /** {@code ("automatic", 10s, false, null, null)} — D1: reproduces today's (proxy-disabled) behaviour exactly. */
    public static MediamtxProxySettings defaults() {
        return new MediamtxProxySettings(
                DEFAULT_RTSP_TRANSPORT, DEFAULT_READY_TIMEOUT, DEFAULT_SOURCE_ON_DEMAND, null, null);
    }
}
