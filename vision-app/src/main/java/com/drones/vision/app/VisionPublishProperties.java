package com.drones.vision.app;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;

/**
 * Configuration for stream egress ({@code vision.publish.*}): whether
 * {@link WiringConfiguration} wires published frames to a <a
 * href="https://github.com/bluenviron/mediamtx">mediamtx</a> sidecar for
 * HLS viewing, where that sidecar is reachable, and the app-facing URL base
 * viewers are actually given.
 *
 * <p>Selected by {@code WiringConfiguration#streamPublisherPort}: {@link
 * #enabled()} {@code true} (the default) wires {@code
 * com.drones.vision.adapter.publishhls.MediamtxStreamPublisher} using
 * {@link Mediamtx#rtspBase()} to push and {@link #viewBase()} (not {@link
 * Mediamtx#hlsBase()}!) as the base viewers are given; {@code false} falls
 * back to {@code com.drones.vision.app.devsupport.NoopStreamPublisher} (e.g.
 * running or testing without mediamtx).
 *
 * <h2>Why two HLS bases</h2>
 * Historically {@link Mediamtx#hlsBase()} served double duty as both "where
 * this app reaches mediamtx" and "the URL browsers are given" — but
 * mediamtx's default HLS port ({@code 8888}) routinely collides with other
 * services already bound to that well-known port on a user's machine,
 * forcing per-machine {@code hls-base} configuration just to view a stream.
 * As of this property, {@link Mediamtx#hlsBase()} is purely the <b>internal
 * upstream</b> address {@code WiringConfiguration} wires {@code
 * HlsProxyController} (in {@code vision-api}) to forward to — viewers never
 * see it, so it can point at whatever port mediamtx actually ended up on.
 * {@link #viewBase()} is the URL base actually handed to viewers (by default
 * the app-relative {@code /hls}, i.e. this app's own origin under {@code
 * HlsProxyController}'s {@code /hls/**} mapping), which {@code
 * WiringConfiguration} passes as {@code MediamtxStreamPublisher}'s {@code
 * hlsViewBase} constructor argument instead of {@link Mediamtx#hlsBase()}.
 *
 * @param enabled  whether to publish to mediamtx; default {@code true}
 * @param viewBase URL base handed to viewers for HLS playback, e.g. via
 *                 {@code StreamPublisherPort#viewUrl}; app-relative by
 *                 default so the mediamtx port is never exposed to
 *                 browsers; default {@value #DEFAULT_VIEW_BASE}
 * @param mediamtx mediamtx sidecar endpoints; defaulted as a whole when absent
 */
@ConfigurationProperties(prefix = "vision.publish")
public record VisionPublishProperties(@DefaultValue("true") boolean enabled,
                                       @DefaultValue(VisionPublishProperties.DEFAULT_VIEW_BASE) URI viewBase,
                                       Mediamtx mediamtx) {

    static final String DEFAULT_VIEW_BASE = "/hls";

    public VisionPublishProperties {
        if (mediamtx == null) {
            mediamtx = new Mediamtx(URI.create(Mediamtx.DEFAULT_RTSP_BASE), URI.create(Mediamtx.DEFAULT_HLS_BASE));
        }
        if (viewBase == null) {
            viewBase = URI.create(DEFAULT_VIEW_BASE);
        }
    }

    /**
     * @param rtspBase base RTSP URL of the mediamtx sidecar to push published frames to,
     *                 e.g. {@code rtsp://localhost:8554}; default {@value Mediamtx#DEFAULT_RTSP_BASE}
     * @param hlsBase  internal address where mediamtx serves HLS, used only as the upstream
     *                 {@code HlsProxyController} forwards to — viewers never see it directly;
     *                 e.g. {@code http://localhost:8888}; default {@value Mediamtx#DEFAULT_HLS_BASE}
     */
    public record Mediamtx(@DefaultValue(Mediamtx.DEFAULT_RTSP_BASE) URI rtspBase,
                            @DefaultValue(Mediamtx.DEFAULT_HLS_BASE) URI hlsBase) {

        static final String DEFAULT_RTSP_BASE = "rtsp://localhost:8554";
        static final String DEFAULT_HLS_BASE = "http://localhost:8888";
    }
}
