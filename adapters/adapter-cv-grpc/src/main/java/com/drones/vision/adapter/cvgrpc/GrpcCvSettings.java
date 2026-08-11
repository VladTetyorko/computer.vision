package com.drones.vision.adapter.cvgrpc;

import java.time.Duration;
import java.util.Objects;

/**
 * Plain, framework-free settings for this module's gRPC transport to cv-service — the single source
 * for every {@code vision.cv} tunable {@link GrpcDetectionPort} and {@link GrpcDatasetUploadPort}
 * need (docs/plans/active/LAYERING-REFACTOR-PLAN.md &sect;2.2/&sect;2.3). No Spring annotations here: {@code
 * vision-app} owns a {@code VisionCvProperties} record bound to {@code application.yaml} and
 * maps it to one of these before handing it to either adapter's constructor — this class must never
 * be constructed from a {@code @ConfigurationProperties} type directly (that would point this module
 * at {@code vision-app}, breaking the hexagon).
 *
 * <p>Every field mirrors a {@code vision.cv.*} key one-to-one:
 * <ul>
 *   <li>{@link #responseTimeout()} &rarr; {@code vision.cv.response-timeout} — {@link
 *   GrpcDetectionPort}'s per-pending-future response timeout.</li>
 *   <li>{@link #keepAliveTime()}/{@link #keepAliveTimeout()}/{@link #keepAliveWithoutCalls()}
 *   &rarr; {@code vision.cv.keepalive-time}/{@code .keepalive-timeout}/{@code
 *   .keepalive-without-calls} — HTTP/2 keepalive on the real-TCP channel {@link GrpcDetectionPort}
 *   builds from a host/port pair.</li>
 *   <li>{@link #channelShutdownTimeout()} &rarr; {@code vision.cv.channel-shutdown-timeout} — how
 *   long {@link GrpcDetectionPort#close()} awaits graceful channel termination before forcing it.</li>
 *   <li>{@link #plaintext()} &rarr; {@code vision.cv.plaintext} — whether the host/port channel skips
 *   TLS; {@code true} today because cv-service is only ever reached over a private/internal network.</li>
 *   <li>{@link #uploadTimeout()}/{@link #uploadChunkBytes()} &rarr; {@code vision.cv.upload.timeout}/
 *   {@code .upload.chunk-bytes} — {@link GrpcDatasetUploadPort}'s per-call deadline and zip-chunk
 *   framing size.</li>
 *   <li>{@link #detectWidth()}/{@link #jpegQuality()} &rarr; {@code vision.cv.detect-width}/{@code
 *   .jpeg-quality} — {@link GrpcDetectionPort}'s wide-{@code BGR24} downscale threshold/target width
 *   and JPEG re-encode quality (see {@link DetectionFrameCodec}).</li>
 * </ul>
 *
 * <p>{@code vision.cv.registry.call-timeout} ({@code GrpcModelRegistryPort.CALL_TIMEOUT_SECONDS}) is
 * deliberately not a field here — that port takes no settings object today (see its own javadoc); a
 * later wiring wave can add it if that constant ever needs to move too.
 *
 * @param responseTimeout        per-pending-future response timeout on the detection bidi stream;
 *                                must be positive
 * @param keepAliveTime          HTTP/2 keepalive PING interval; must be positive
 * @param keepAliveTimeout       how long a keepalive PING may go unacknowledged before the channel
 *                                considers the connection dead; must be positive
 * @param keepAliveWithoutCalls  whether to send keepalive PINGs on an otherwise idle channel
 * @param channelShutdownTimeout how long {@link GrpcDetectionPort#close()} awaits graceful channel
 *                                termination before forcing it; must be positive
 * @param plaintext              whether the host/port channel constructor skips TLS
 * @param uploadTimeout          per-call deadline covering dataset archive framing plus the whole
 *                                upload round trip; must be positive
 * @param uploadChunkBytes       target size of each streamed dataset zip chunk; must be positive
 * @param detectWidth            widest a {@code BGR24} frame may be before it is downscaled and
 *                                JPEG-encoded instead of sent raw; must be {@code >=} {@value
 *                                #MIN_DETECT_WIDTH}
 * @param jpegQuality            JPEG encoder quality for the downscale path; must be in {@code (0,1]}
 */
public record GrpcCvSettings(
        Duration responseTimeout,
        Duration keepAliveTime,
        Duration keepAliveTimeout,
        boolean keepAliveWithoutCalls,
        Duration channelShutdownTimeout,
        boolean plaintext,
        Duration uploadTimeout,
        int uploadChunkBytes,
        int detectWidth,
        float jpegQuality) {

    /** Default {@link #responseTimeout()} — see {@code GrpcDetectionPort}'s class javadoc, "hung service" case. */
    static final long RESPONSE_TIMEOUT_SECONDS = 2;

    /** Default {@link #keepAliveTime()}. */
    static final long KEEPALIVE_TIME_SECONDS = 20;

    /** Default {@link #keepAliveTimeout()}. */
    static final long KEEPALIVE_TIMEOUT_SECONDS = 5;

    /** Default {@link #keepAliveWithoutCalls()}. */
    static final boolean KEEPALIVE_WITHOUT_CALLS = true;

    /** Default {@link #channelShutdownTimeout()}. */
    static final long CHANNEL_SHUTDOWN_TIMEOUT_SECONDS = 5;

    /** Default {@link #plaintext()} — cv-service is reached over a private/internal network. */
    static final boolean PLAINTEXT = true;

    /** Default {@link #uploadTimeout()}. */
    static final long UPLOAD_TIMEOUT_SECONDS = 300;

    /** Default {@link #uploadChunkBytes()} — 256 KiB. */
    static final int CHUNK_BYTES = 262_144;

    /**
     * Default {@link #detectWidth()} — the full-resolution raw frames the RTSP/file RX path
     * produces (e.g. 1280&times;720) are wider than this, so they get downscaled+JPEG-encoded
     * before being sent; see {@link DetectionFrameCodec}.
     */
    static final int MAX_DETECT_WIDTH = 640;

    /** Smallest {@link #detectWidth()} this record accepts — below this, detection quality degrades sharply. */
    static final int MIN_DETECT_WIDTH = 64;

    /** Default {@link #jpegQuality()} — balances size vs. detail. */
    static final float JPEG_QUALITY = 0.8f;

    public GrpcCvSettings {
        Objects.requireNonNull(responseTimeout, "responseTimeout must not be null");
        Objects.requireNonNull(keepAliveTime, "keepAliveTime must not be null");
        Objects.requireNonNull(keepAliveTimeout, "keepAliveTimeout must not be null");
        Objects.requireNonNull(channelShutdownTimeout, "channelShutdownTimeout must not be null");
        Objects.requireNonNull(uploadTimeout, "uploadTimeout must not be null");
        requirePositive(responseTimeout, "responseTimeout");
        requirePositive(keepAliveTime, "keepAliveTime");
        requirePositive(keepAliveTimeout, "keepAliveTimeout");
        requirePositive(channelShutdownTimeout, "channelShutdownTimeout");
        requirePositive(uploadTimeout, "uploadTimeout");
        if (uploadChunkBytes <= 0) {
            throw new IllegalArgumentException("uploadChunkBytes must be positive, was " + uploadChunkBytes);
        }
        if (detectWidth < MIN_DETECT_WIDTH) {
            throw new IllegalArgumentException(
                    "detectWidth must be >= " + MIN_DETECT_WIDTH + ", was " + detectWidth);
        }
        if (jpegQuality <= 0f || jpegQuality > 1f) {
            throw new IllegalArgumentException("jpegQuality must be in (0,1], was " + jpegQuality);
        }
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(name + " must be positive, was " + duration);
        }
    }

    /** Every default equals the literal each field used to be hardcoded as, before this record existed. */
    public static GrpcCvSettings defaults() {
        return new GrpcCvSettings(
                Duration.ofSeconds(RESPONSE_TIMEOUT_SECONDS),
                Duration.ofSeconds(KEEPALIVE_TIME_SECONDS),
                Duration.ofSeconds(KEEPALIVE_TIMEOUT_SECONDS),
                KEEPALIVE_WITHOUT_CALLS,
                Duration.ofSeconds(CHANNEL_SHUTDOWN_TIMEOUT_SECONDS),
                PLAINTEXT,
                Duration.ofSeconds(UPLOAD_TIMEOUT_SECONDS),
                CHUNK_BYTES,
                MAX_DETECT_WIDTH,
                JPEG_QUALITY);
    }

    /** Copy of this settings object with just {@link #detectWidth()} replaced — a test/tuning convenience. */
    public GrpcCvSettings withDetectWidth(int newDetectWidth) {
        return new GrpcCvSettings(responseTimeout, keepAliveTime, keepAliveTimeout, keepAliveWithoutCalls,
                channelShutdownTimeout, plaintext, uploadTimeout, uploadChunkBytes, newDetectWidth, jpegQuality);
    }

    /** Copy of this settings object with just {@link #jpegQuality()} replaced — a test/tuning convenience. */
    public GrpcCvSettings withJpegQuality(float newJpegQuality) {
        return new GrpcCvSettings(responseTimeout, keepAliveTime, keepAliveTimeout, keepAliveWithoutCalls,
                channelShutdownTimeout, plaintext, uploadTimeout, uploadChunkBytes, detectWidth, newJpegQuality);
    }
}
