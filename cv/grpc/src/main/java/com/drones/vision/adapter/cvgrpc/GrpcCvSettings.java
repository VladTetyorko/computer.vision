package com.drones.vision.adapter.cvgrpc;

import java.net.URI;
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
 *   and JPEG re-encode quality (see {@link DetectionFrameCodec}). {@link #detectWidth()} doubles as
 *   {@link GrpcPulledDetectionPort}'s {@code PullControl.detect_width} (docs/plans/done/MEDIA-SOT-PLAN.md
 *   &sect;5.1, wave M4) — the same "how wide should the CV side work with" knob, whether the JVM
 *   downscales before sending (push) or tells the worker to downscale locally (pull).</li>
 *   <li>{@link #pullRtspBase()}/{@link #pullReconnectInitialBackoff()}/{@link
 *   #pullReconnectMaxBackoff()} &rarr; {@code vision.cv.pull.rtsp-base}/{@code
 *   .pull.reconnect-backoff.*} (docs/plans/done/MEDIA-SOT-PLAN.md &sect;5.5, wave M4) — see their own
 *   javadoc below for why these are config-surface only in this module today, not consumed by any
 *   class here.</li>
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
 * @param wireFormat             how a downscaled frame reaches cv-service; must not be {@code null},
 *                                defaults to {@link WireFormat#AUTO} which picks raw {@code BGR24}
 *                                for a loopback endpoint and JPEG for anything else
 * @param pullRtspBase            base RTSP URL the worker dials for a pulled stream (docs/plans/done/MEDIA-SOT-PLAN.md
 *                                &sect;5.5), e.g. {@code rtsp://localhost:8554} — <b>deliberately
 *                                separate</b> from {@code vision.publish.mediamtx.rtsp-base}: a remote
 *                                worker (the GB4005 box) must dial the host's LAN address, not {@code
 *                                localhost}, even though both properties often point at the same
 *                                mediamtx instance. Not read by any class in this module today —
 *                                {@link com.drones.vision.perception.domain.port.PulledDetectionPort#open}
 *                                already takes a fully-formed {@code sourceUrl}, built by whichever
 *                                caller owns that decision (a later wave); carried here so the
 *                                {@code vision.cv.pull.*} config surface is pinned in one place ahead
 *                                of that wiring. Must not be {@code null}
 * @param pullReconnectInitialBackoff how long a pulled stream's reopen waits before its first retry
 *                                after a failure; mirrors {@code vision.publish.resilience.initial-backoff}'s
 *                                shape. <b>Not applied by this module</b> — docs/plans/done/MEDIA-SOT-PLAN.md
 *                                decision D5 is explicit that {@link GrpcPulledDetectionPort} must not
 *                                build reconnect/backoff itself; the existing generic {@code
 *                                SupervisedPublisher<DetectionResult>} (vision-application) applies it
 *                                instead. Carried here for the same config-surface-pinning reason as
 *                                {@link #pullRtspBase()}. Must be positive
 * @param pullReconnectMaxBackoff cap the doubling reconnect backoff never exceeds; must be {@code >=}
 *                                {@link #pullReconnectInitialBackoff()}
 * @param reconnectInitialBackoff how long {@link CvChannelSupervisor} waits, while the channel is in
 *                                {@code TRANSIENT_FAILURE}, before its first forced {@code
 *                                resetConnectBackoff()} call (docs/plans/active/CV-RECONNECT-PLAN.md
 *                                &sect;2.1) — the <b>push/channel</b> path's own reconnect cadence,
 *                                separate from {@link #pullReconnectInitialBackoff()} (the pull
 *                                path's, applied by a different class entirely — see that field's own
 *                                javadoc). Must be positive
 * @param reconnectMaxBackoff     cap the doubling forced-reconnect backoff never exceeds. Unlike
 *                                {@link #pullReconnectMaxBackoff()}, this field is <b>not</b> validated
 *                                against {@link #reconnectInitialBackoff()} — only non-null/positive,
 *                                same as every other plain {@code Duration} field here
 * @param outageLogInterval       how often {@link CvChannelSupervisor} logs an INFO heartbeat
 *                                ("still unreachable") while an outage continues, replacing what would
 *                                otherwise be a WARN-with-stack-trace flood (one per failed probe)
 *                                with one predictable line per interval; must be positive
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
        float jpegQuality,
        WireFormat wireFormat,
        URI pullRtspBase,
        Duration pullReconnectInitialBackoff,
        Duration pullReconnectMaxBackoff,
        Duration reconnectInitialBackoff,
        Duration reconnectMaxBackoff,
        Duration outageLogInterval) {

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

    /** Default {@link #wireFormat()} — see {@link WireFormat#AUTO} for why the default is a rule. */
    static final WireFormat WIRE_FORMAT = WireFormat.AUTO;

    /** Default {@link #pullRtspBase()} — the local compose mediamtx; see this field's own javadoc. */
    static final String PULL_RTSP_BASE = "rtsp://localhost:8554";

    /** Default {@link #pullReconnectInitialBackoff()} — mirrors {@code PublishSettings.Resilience}'s default. */
    static final long PULL_RECONNECT_INITIAL_BACKOFF_MILLIS = 500;

    /** Default {@link #pullReconnectMaxBackoff()} — mirrors {@code PublishSettings.Resilience}'s default. */
    static final long PULL_RECONNECT_MAX_BACKOFF_SECONDS = 10;

    /** Default {@link #reconnectInitialBackoff()} (docs/plans/active/CV-RECONNECT-PLAN.md &sect;3.2). */
    static final long RECONNECT_INITIAL_BACKOFF_SECONDS = 1;

    /** Default {@link #reconnectMaxBackoff()} (docs/plans/active/CV-RECONNECT-PLAN.md &sect;3.2). */
    static final long RECONNECT_MAX_BACKOFF_SECONDS = 10;

    /** Default {@link #outageLogInterval()} (docs/plans/active/CV-RECONNECT-PLAN.md &sect;3.2). */
    static final long OUTAGE_LOG_INTERVAL_SECONDS = 60;

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
        if (wireFormat == null) {
            throw new IllegalArgumentException("wireFormat must not be null; use WireFormat.AUTO");
        }
        if (jpegQuality <= 0f || jpegQuality > 1f) {
            throw new IllegalArgumentException("jpegQuality must be in (0,1], was " + jpegQuality);
        }
        Objects.requireNonNull(pullRtspBase, "pullRtspBase must not be null");
        Objects.requireNonNull(pullReconnectInitialBackoff, "pullReconnectInitialBackoff must not be null");
        Objects.requireNonNull(pullReconnectMaxBackoff, "pullReconnectMaxBackoff must not be null");
        requirePositive(pullReconnectInitialBackoff, "pullReconnectInitialBackoff");
        if (pullReconnectMaxBackoff.compareTo(pullReconnectInitialBackoff) < 0) {
            throw new IllegalArgumentException("pullReconnectMaxBackoff must be >= pullReconnectInitialBackoff: "
                    + pullReconnectMaxBackoff + " < " + pullReconnectInitialBackoff);
        }
        Objects.requireNonNull(reconnectInitialBackoff, "reconnectInitialBackoff must not be null");
        Objects.requireNonNull(reconnectMaxBackoff, "reconnectMaxBackoff must not be null");
        Objects.requireNonNull(outageLogInterval, "outageLogInterval must not be null");
        requirePositive(reconnectInitialBackoff, "reconnectInitialBackoff");
        requirePositive(reconnectMaxBackoff, "reconnectMaxBackoff");
        requirePositive(outageLogInterval, "outageLogInterval");
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
                JPEG_QUALITY,
                WIRE_FORMAT,
                URI.create(PULL_RTSP_BASE),
                Duration.ofMillis(PULL_RECONNECT_INITIAL_BACKOFF_MILLIS),
                Duration.ofSeconds(PULL_RECONNECT_MAX_BACKOFF_SECONDS),
                Duration.ofSeconds(RECONNECT_INITIAL_BACKOFF_SECONDS),
                Duration.ofSeconds(RECONNECT_MAX_BACKOFF_SECONDS),
                Duration.ofSeconds(OUTAGE_LOG_INTERVAL_SECONDS));
    }

    /** Copy of this settings object with just {@link #detectWidth()} replaced — a test/tuning convenience. */
    public GrpcCvSettings withDetectWidth(int newDetectWidth) {
        return new GrpcCvSettings(responseTimeout, keepAliveTime, keepAliveTimeout, keepAliveWithoutCalls,
                channelShutdownTimeout, plaintext, uploadTimeout, uploadChunkBytes, newDetectWidth, jpegQuality,
                wireFormat, pullRtspBase, pullReconnectInitialBackoff, pullReconnectMaxBackoff,
                reconnectInitialBackoff, reconnectMaxBackoff, outageLogInterval);
    }

    /** Copy of this settings object with just {@link #wireFormat()} replaced — a test/tuning convenience. */
    public GrpcCvSettings withWireFormat(WireFormat newWireFormat) {
        return new GrpcCvSettings(responseTimeout, keepAliveTime, keepAliveTimeout, keepAliveWithoutCalls,
                channelShutdownTimeout, plaintext, uploadTimeout, uploadChunkBytes, detectWidth, jpegQuality,
                newWireFormat, pullRtspBase, pullReconnectInitialBackoff, pullReconnectMaxBackoff,
                reconnectInitialBackoff, reconnectMaxBackoff, outageLogInterval);
    }

    /** Copy of this settings object with just {@link #jpegQuality()} replaced — a test/tuning convenience. */
    public GrpcCvSettings withJpegQuality(float newJpegQuality) {
        return new GrpcCvSettings(responseTimeout, keepAliveTime, keepAliveTimeout, keepAliveWithoutCalls,
                channelShutdownTimeout, plaintext, uploadTimeout, uploadChunkBytes, detectWidth, newJpegQuality,
                wireFormat, pullRtspBase, pullReconnectInitialBackoff, pullReconnectMaxBackoff,
                reconnectInitialBackoff, reconnectMaxBackoff, outageLogInterval);
    }

    /** Copy of this settings object with just {@link #pullRtspBase()} replaced — a test/tuning convenience. */
    public GrpcCvSettings withPullRtspBase(URI newPullRtspBase) {
        return new GrpcCvSettings(responseTimeout, keepAliveTime, keepAliveTimeout, keepAliveWithoutCalls,
                channelShutdownTimeout, plaintext, uploadTimeout, uploadChunkBytes, detectWidth, jpegQuality,
                wireFormat, newPullRtspBase, pullReconnectInitialBackoff, pullReconnectMaxBackoff,
                reconnectInitialBackoff, reconnectMaxBackoff, outageLogInterval);
    }

    /** Copy of this settings object with just {@link #reconnectInitialBackoff()} replaced — a test/tuning convenience. */
    public GrpcCvSettings withReconnectInitialBackoff(Duration newReconnectInitialBackoff) {
        return new GrpcCvSettings(responseTimeout, keepAliveTime, keepAliveTimeout, keepAliveWithoutCalls,
                channelShutdownTimeout, plaintext, uploadTimeout, uploadChunkBytes, detectWidth, jpegQuality,
                wireFormat, pullRtspBase, pullReconnectInitialBackoff, pullReconnectMaxBackoff,
                newReconnectInitialBackoff, reconnectMaxBackoff, outageLogInterval);
    }

    /** Copy of this settings object with just {@link #reconnectMaxBackoff()} replaced — a test/tuning convenience. */
    public GrpcCvSettings withReconnectMaxBackoff(Duration newReconnectMaxBackoff) {
        return new GrpcCvSettings(responseTimeout, keepAliveTime, keepAliveTimeout, keepAliveWithoutCalls,
                channelShutdownTimeout, plaintext, uploadTimeout, uploadChunkBytes, detectWidth, jpegQuality,
                wireFormat, pullRtspBase, pullReconnectInitialBackoff, pullReconnectMaxBackoff,
                reconnectInitialBackoff, newReconnectMaxBackoff, outageLogInterval);
    }

    /** Copy of this settings object with just {@link #outageLogInterval()} replaced — a test/tuning convenience. */
    public GrpcCvSettings withOutageLogInterval(Duration newOutageLogInterval) {
        return new GrpcCvSettings(responseTimeout, keepAliveTime, keepAliveTimeout, keepAliveWithoutCalls,
                channelShutdownTimeout, plaintext, uploadTimeout, uploadChunkBytes, detectWidth, jpegQuality,
                wireFormat, pullRtspBase, pullReconnectInitialBackoff, pullReconnectMaxBackoff,
                reconnectInitialBackoff, reconnectMaxBackoff, newOutageLogInterval);
    }
}
