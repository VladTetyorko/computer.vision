package com.drones.vision.adapter.rtsp;

import java.time.Duration;
import java.util.Objects;

/**
 * Framework-free tunables for this module's FFmpeg-backed RX ({@link FfmpegVideoSource}) and TX
 * ({@link RtspFeedTransmitter}) halves — docs/plans/active/LAYERING-REFACTOR-PLAN.md §1.3/§2.2's frozen {@code
 * vision.rtsp} property-key contract, extracted structurally out of what were previously scattered
 * {@code private static final} constants on those two classes. {@link #defaults()} reproduces every
 * one of those old literals exactly — this is a structural move, not a retune; see each field's own
 * javadoc below and {@code FfmpegGrabberOptions}'/{@code RtspFeedTransmitter}'s own javadoc for the
 * verified-fact provenance of each number (FFmpeg source citations, measured behavior, etc.), which
 * is unchanged by this extraction.
 *
 * <p>A later wave (docs/plans/active/LAYERING-REFACTOR-PLAN.md §7, F1) adds a Spring {@code VisionRtspProperties}
 * record in {@code vision-app} and binds it to this one — this module stays entirely framework-free
 * and has no idea that binding exists; it only ever sees a plain {@code FfmpegSettings} instance,
 * constructor-injected exactly like every other plain value this module already takes.
 *
 * <p>Two module tunables were deliberately <b>not</b> pulled in here, on a judgment call: {@code
 * FfmpegGrabberOptions.DEFAULT_UDP_OVERRUN_NONFATAL} (a fixed "survive rather than abort" posture,
 * not a scalar the frozen key table names) and the SRT {@code pbkeylen} pin (living documentation of
 * libsrt's own already-built-in default, not a deployment choice — same "protocol/spec constant"
 * category docs/plans/active/LAYERING-REFACTOR-PLAN.md §1.3 keeps static). Both stay as named constants next to
 * the option they configure; see their own javadoc there.
 *
 * @param transport               RTSP RX {@code rtsp_transport} AVOption default (e.g. {@code
 *                                "tcp"}); also reused as the TX side's push transport (previously a
 *                                second, separately-hardcoded {@code "tcp"} literal in {@link
 *                                RtspFeedTransmitter} — collapsed into this one field since both
 *                                already agreed on the same value)
 * @param openTimeout             RTSP RX {@code timeout}/{@code rw_timeout} AVOption default
 *                                (microsecond AVOption resolution; converted via {@link
 *                                #microsOption})
 * @param probesizeBytes          RTSP RX {@code probesize} AVOption default, bytes
 * @param analyzeDuration         RTSP RX {@code analyzeduration} AVOption default
 * @param reorderQueueSize        RTSP RX {@code reorder_queue_size} AVOption default, packets
 * @param maxDelay                RTSP RX {@code max_delay}, applied via {@code
 *                                FFmpegFrameGrabber#setMaxDelay(int)} — never {@code setOption}, see
 *                                {@code FfmpegGrabberOptions}'s javadoc for why
 * @param udpTimeout              UDP RX internal, non-overridable ({@code StreamDescriptor} options
 *                                cannot change it) read timeout safety bound — see {@code
 *                                FfmpegGrabberOptions}'s javadoc for the production-robustness
 *                                finding that motivated it
 * @param udpFifoSizePackets      UDP RX {@code fifo_size} AVOption default, 188-byte packets
 * @param srtLatency              SRT RX {@code latency} AVOption default — this module's own
 *                                contract unit is milliseconds; the FFmpeg AVOption itself is
 *                                microseconds, converted at the call site
 * @param publisherBufferCapacity RX per-stream {@code SubmissionPublisher} buffer capacity, frames
 * @param closeJoinTimeout        bound on {@code close()}'s grab/transmit thread join, shared by
 *                                both the RX and TX halves (both independently used the identical
 *                                20s literal before this extraction)
 * @param transmit                {@link RtspFeedTransmitter}-only (TX) encoder tunables
 */
public record FfmpegSettings(
        String transport,
        Duration openTimeout,
        int probesizeBytes,
        Duration analyzeDuration,
        int reorderQueueSize,
        Duration maxDelay,
        Duration udpTimeout,
        int udpFifoSizePackets,
        Duration srtLatency,
        int publisherBufferCapacity,
        Duration closeJoinTimeout,
        Transmit transmit) {

    public FfmpegSettings {
        Objects.requireNonNull(transport, "transport must not be null");
        if (transport.isBlank()) {
            throw new IllegalArgumentException("transport must not be blank");
        }
        requirePositive(openTimeout, "openTimeout");
        requirePositive(probesizeBytes, "probesizeBytes");
        requirePositive(analyzeDuration, "analyzeDuration");
        if (reorderQueueSize < 0) {
            throw new IllegalArgumentException("reorderQueueSize must not be negative");
        }
        requirePositive(maxDelay, "maxDelay");
        requirePositive(udpTimeout, "udpTimeout");
        requirePositive(udpFifoSizePackets, "udpFifoSizePackets");
        requirePositive(srtLatency, "srtLatency");
        requirePositive(publisherBufferCapacity, "publisherBufferCapacity");
        requirePositive(closeJoinTimeout, "closeJoinTimeout");
        Objects.requireNonNull(transmit, "transmit must not be null");
    }

    /**
     * Reproduces every literal this record replaces, exactly as it stood before this extraction —
     * see each field's own javadoc above and this module's MODULE.md for provenance.
     */
    public static FfmpegSettings defaults() {
        return new FfmpegSettings(
                "tcp",
                Duration.ofSeconds(10),
                32_768,
                Duration.ofSeconds(1),
                0,
                Duration.ofMillis(100),
                Duration.ofSeconds(5),
                512,
                Duration.ofMillis(120),
                4,
                Duration.ofSeconds(20),
                new Transmit(2, "ultrafast", 15.0, Duration.ofSeconds(5), "zerolatency"));
    }

    /**
     * Converts a duration to a whole-microsecond decimal string, the string form every
     * microsecond-resolution FFmpeg AVOption in this module expects via {@code setOption}.
     */
    static String microsOption(Duration duration) {
        return String.valueOf(duration.toNanos() / 1_000L);
    }

    private static void requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name + " must not be null");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requirePositive(double value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /**
     * {@link RtspFeedTransmitter}-only (TX) encoder tunables — deliberately duplicated from
     * adapter-publish-hls's {@code MediamtxStreamPublisher} rather than shared (adapters must not
     * depend on each other), matching that class's own constructor-injected settings shape.
     *
     * @param gopSeconds     keyframe interval, in seconds of the source's own (or {@link
     *                       #fallbackFps}) frame rate
     * @param preset         libx264 {@code preset} encoder option
     * @param fallbackFps    frame rate assumed when the source file reports none usable ({@code
     *                       getFrameRate() <= 0})
     * @param connectTimeout RTSP push {@code timeout} AVOption (microsecond AVOption resolution)
     * @param tune           libx264 {@code tune} encoder option
     */
    public record Transmit(int gopSeconds, String preset, double fallbackFps, Duration connectTimeout, String tune) {

        public Transmit {
            requirePositive(gopSeconds, "gopSeconds");
            Objects.requireNonNull(preset, "preset must not be null");
            if (preset.isBlank()) {
                throw new IllegalArgumentException("preset must not be blank");
            }
            requirePositive(fallbackFps, "fallbackFps");
            requirePositive(connectTimeout, "connectTimeout");
            Objects.requireNonNull(tune, "tune must not be null");
            if (tune.isBlank()) {
                throw new IllegalArgumentException("tune must not be blank");
            }
        }
    }
}
