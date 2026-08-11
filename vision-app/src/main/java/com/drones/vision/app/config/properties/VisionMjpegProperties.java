package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuration for {@code adapter-mjpeg}'s RX/TX ({@code vision.mjpeg.*}), docs/plans/active/LAYERING-REFACTOR-PLAN.md
 * &sect;2.2, wave F1.
 *
 * <p>Mapped by {@code wiring.VideoSourceWiring}/{@code wiring.FeedTransmitterWiring} onto {@code
 * com.drones.vision.adapter.mjpeg.MjpegSettings} — every {@code @DefaultValue} below is
 * byte-identical to {@code MjpegSettings.defaults()}'s own literal.
 *
 * @param readTimeout             RX connect-timeout fallback; default 5s
 * @param publisherBufferCapacity RX buffer capacity; default {@value #DEFAULT_PUBLISHER_BUFFER_CAPACITY}
 * @param closeJoinTimeout        RX {@code close()}'s bounded join; default 20s
 * @param transmit                TX-side tunables; defaulted as a whole when absent
 */
@ConfigurationProperties(prefix = "vision.mjpeg")
public record VisionMjpegProperties(
        @DefaultValue("5s") Duration readTimeout,
        @DefaultValue(VisionMjpegProperties.DEFAULT_PUBLISHER_BUFFER_CAPACITY) int publisherBufferCapacity,
        @DefaultValue("20s") Duration closeJoinTimeout,
        Transmit transmit) {

    static final String DEFAULT_PUBLISHER_BUFFER_CAPACITY = "4";

    public VisionMjpegProperties {
        if (transmit == null) {
            transmit = new Transmit(Transmit.DEFAULT_BIND_HOST, null, Transmit.DEFAULT_JPEG_QUALITY_FLOAT,
                    Transmit.DEFAULT_LOOP_BOOLEAN, Transmit.DEFAULT_VIEWER_JOIN_TIMEOUT_DURATION);
        }
    }

    /**
     * @param bindHost          loopback host the shared HTTP server binds to; default {@value
     *                          Transmit#DEFAULT_BIND_HOST}
     * @param maxViewerThreads  dispatch-pool cap for concurrent viewer connections; {@code null}
     *                          (absent, the default) reproduces the unbounded cached thread pool —
     *                          commented out in {@code application.properties}, documenting the
     *                          "no cap" default rather than setting one
     * @param jpegQuality       JPEG compression quality in {@code (0,1]}; default {@value
     *                          Transmit#DEFAULT_JPEG_QUALITY}
     * @param loop              default for a feed's {@code loop} option when absent; default {@code
     *                          true}
     * @param viewerJoinTimeout bounded join per viewer thread on stop/close; default 5s
     */
    public record Transmit(@DefaultValue(Transmit.DEFAULT_BIND_HOST) String bindHost,
                            Integer maxViewerThreads,
                            @DefaultValue(Transmit.DEFAULT_JPEG_QUALITY) float jpegQuality,
                            @DefaultValue("true") boolean loop,
                            @DefaultValue("5s") Duration viewerJoinTimeout) {

        static final String DEFAULT_BIND_HOST = "127.0.0.1";
        static final String DEFAULT_JPEG_QUALITY = "0.75";
        static final float DEFAULT_JPEG_QUALITY_FLOAT = 0.75f;
        static final boolean DEFAULT_LOOP_BOOLEAN = true;
        static final Duration DEFAULT_VIEWER_JOIN_TIMEOUT_DURATION = Duration.ofSeconds(5);
    }
}
