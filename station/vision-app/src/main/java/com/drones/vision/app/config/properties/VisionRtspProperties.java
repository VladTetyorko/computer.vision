package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuration for {@code adapter-rtsp}'s FFmpeg-backed RX/TX ({@code vision.rtsp.*}),
 * docs/plans/active/LAYERING-REFACTOR-PLAN.md &sect;2.2, wave F1.
 *
 * <p>Mapped by {@code wiring.VideoSourceWiring}/{@code wiring.FeedTransmitterWiring} onto {@code
 * com.drones.vision.adapter.rtsp.FfmpegSettings} — the plain, framework-free settings record {@code
 * FfmpegVideoSource}/{@code RtspFeedTransmitter} actually take (one constructor argument, per
 * &sect;1.3 rule 3). Every {@code @DefaultValue} below is byte-identical to {@code
 * FfmpegSettings.defaults()}'s own literal.
 *
 * @param transport               RTSP RX {@code rtsp_transport} AVOption default; also the TX push
 *                                transport; default {@value #DEFAULT_TRANSPORT}
 * @param openTimeout             RTSP RX open/connect timeout; default 10s
 * @param probesizeBytes          RTSP RX {@code probesize} AVOption default, bytes; default {@value
 *                                #DEFAULT_PROBESIZE_BYTES}
 * @param analyzeDuration         RTSP RX {@code analyzeduration} AVOption default; default 1s
 * @param reorderQueueSize        RTSP RX {@code reorder_queue_size} AVOption default, packets;
 *                                default {@value #DEFAULT_REORDER_QUEUE_SIZE}
 * @param maxDelay                RTSP RX {@code max_delay}; default 100ms
 * @param udpTimeout              UDP RX read-timeout safety bound; default 5s
 * @param udpFifoSizePackets      UDP RX {@code fifo_size} AVOption default, 188-byte packets;
 *                                default {@value #DEFAULT_UDP_FIFO_SIZE_PACKETS}
 * @param srtLatency              SRT RX {@code latency} AVOption default; default 120ms
 * @param publisherBufferCapacity RX per-stream buffer capacity, frames; default {@value
 *                                #DEFAULT_PUBLISHER_BUFFER_CAPACITY}
 * @param closeJoinTimeout        bound on {@code close()}'s grab/transmit thread join; default 20s
 * @param transmit                TX-only encoder tunables; defaulted as a whole when absent
 */
@ConfigurationProperties(prefix = "vision.rtsp")
public record VisionRtspProperties(
        @DefaultValue(VisionRtspProperties.DEFAULT_TRANSPORT) String transport,
        @DefaultValue("10s") Duration openTimeout,
        @DefaultValue(VisionRtspProperties.DEFAULT_PROBESIZE_BYTES) int probesizeBytes,
        @DefaultValue("1s") Duration analyzeDuration,
        @DefaultValue(VisionRtspProperties.DEFAULT_REORDER_QUEUE_SIZE) int reorderQueueSize,
        @DefaultValue("100ms") Duration maxDelay,
        @DefaultValue("5s") Duration udpTimeout,
        @DefaultValue(VisionRtspProperties.DEFAULT_UDP_FIFO_SIZE_PACKETS) int udpFifoSizePackets,
        @DefaultValue("120ms") Duration srtLatency,
        @DefaultValue(VisionRtspProperties.DEFAULT_PUBLISHER_BUFFER_CAPACITY) int publisherBufferCapacity,
        @DefaultValue("20s") Duration closeJoinTimeout,
        Transmit transmit) {

    static final String DEFAULT_TRANSPORT = "tcp";
    static final String DEFAULT_PROBESIZE_BYTES = "32768";
    static final String DEFAULT_REORDER_QUEUE_SIZE = "0";
    static final String DEFAULT_UDP_FIFO_SIZE_PACKETS = "512";
    static final String DEFAULT_PUBLISHER_BUFFER_CAPACITY = "4";

    public VisionRtspProperties {
        if (transmit == null) {
            transmit = new Transmit(Transmit.DEFAULT_GOP_SECONDS_INT, Transmit.DEFAULT_PRESET,
                    Transmit.DEFAULT_FALLBACK_FPS_DOUBLE, Transmit.DEFAULT_CONNECT_TIMEOUT_DURATION,
                    Transmit.DEFAULT_TUNE);
        }
    }

    /**
     * @param gopSeconds     keyframe interval, seconds; default {@value Transmit#DEFAULT_GOP_SECONDS}
     * @param preset         libx264 preset; default {@value Transmit#DEFAULT_PRESET}
     * @param fallbackFps    frame rate assumed when the source reports none usable; default {@value
     *                       Transmit#DEFAULT_FALLBACK_FPS}
     * @param connectTimeout RTSP push connect timeout; default 5s
     * @param tune           libx264 tune option; default {@value Transmit#DEFAULT_TUNE}
     */
    public record Transmit(@DefaultValue(Transmit.DEFAULT_GOP_SECONDS) int gopSeconds,
                            @DefaultValue(Transmit.DEFAULT_PRESET) String preset,
                            @DefaultValue(Transmit.DEFAULT_FALLBACK_FPS) double fallbackFps,
                            @DefaultValue("5s") Duration connectTimeout,
                            @DefaultValue(Transmit.DEFAULT_TUNE) String tune) {

        static final String DEFAULT_GOP_SECONDS = "2";
        static final String DEFAULT_PRESET = "ultrafast";
        static final String DEFAULT_FALLBACK_FPS = "15.0";
        static final String DEFAULT_TUNE = "zerolatency";
        static final int DEFAULT_GOP_SECONDS_INT = 2;
        static final double DEFAULT_FALLBACK_FPS_DOUBLE = 15.0;
        static final Duration DEFAULT_CONNECT_TIMEOUT_DURATION = Duration.ofSeconds(5);
    }
}
