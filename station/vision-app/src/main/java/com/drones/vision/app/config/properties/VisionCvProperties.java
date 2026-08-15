package com.drones.vision.app.config.properties;

import com.drones.vision.adapter.cvgrpc.WireFormat;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

/**
 * Configuration for the gRPC connection to the Python CV service ({@code vision.cv.*}),
 * per docs/plans/done/MVP1-PLAN.md §C7 bullet 4 and docs/plans/done/REMOTE-CV-PLAN.md P1 item 5, extended by
 * docs/plans/active/LAYERING-REFACTOR-PLAN.md §2.2 (wave F4) with every remaining {@code
 * com.drones.vision.adapter.cvgrpc.GrpcCvSettings}/{@code GrpcModelRegistryPort} tunable.
 *
 * <p>Selected by {@code wiring.CvWiring#detectionPort}: {@link #enabled()} {@code false}
 * (the default, today's behavior) keeps {@code DetectionPort} wired to the devsupport
 * {@code NoopDetectionPort}; {@code true} wires {@code
 * com.drones.vision.adapter.cvgrpc.GrpcDetectionPort} against {@link #endpoint()} and a {@code
 * GrpcCvSettings} built from {@link #responseTimeout()}/{@link #keepAliveTime()}/{@link
 * #keepAliveTimeout()}/{@link #keepAliveWithoutCalls()}/{@link #channelShutdownTimeout()}/{@link
 * #plaintext()}/{@link #detectWidth()}/{@link #jpegQuality()}/{@link Upload#timeout()}/{@link
 * Upload#chunkBytes()}. {@link Registry#callTimeout()} is deliberately <b>not</b> part of the
 * mapped {@code GrpcCvSettings} — it maps straight onto {@code GrpcModelRegistryPort}'s own
 * {@code (ManagedChannel, Duration)} constructor instead (that class takes no settings object).
 *
 * @param enabled                whether to wire {@code GrpcDetectionPort} instead of the no-op fallback;
 *                               default {@code false}
 * @param endpoint               {@code host:port} of the cv-service's {@code DetectStream} gRPC endpoint
 *                               (an optional {@code scheme://} prefix, e.g. {@code dns://}, is tolerated
 *                               and stripped); only read when {@link #enabled()} is {@code true}; default
 *                               {@value #DEFAULT_ENDPOINT}
 * @param detectWidth            widest a {@code BGR24} frame may be before {@code GrpcDetectionPort}
 *                               downscales+JPEG-encodes it before sending; must be {@code >= 64}; default
 *                               {@value #DEFAULT_DETECT_WIDTH}
 * @param jpegQuality            JPEG encoder quality {@code GrpcDetectionPort} uses for that same
 *                               downscale path; must be in {@code (0, 1]}; default
 *                               {@value #DEFAULT_JPEG_QUALITY}
 * @param wireFormat             {@code auto} | {@code jpeg} | {@code bgr24} — how a downscaled frame
 *                               reaches cv-service; default {@value #DEFAULT_WIRE_FORMAT}, which
 *                               sends raw {@code BGR24} to a loopback endpoint (no encode here, no
 *                               decode there) and JPEG to anything further away
 * @param frameTransport         {@code push} | {@code pull} (docs/plans/active/MEDIA-SOT-PLAN.md §3 switch
 *                               B, §5.5) — {@code push} (default, {@value #DEFAULT_FRAME_TRANSPORT}) is
 *                               today's behavior unchanged: the JVM samples frames and sends them to
 *                               cv-service over {@code DetectStream}. {@code pull} switches every stream
 *                               this deployment starts (D5/D12: one deployment-wide choice, not a
 *                               per-device one) to {@code DetectPulled} instead — cv-service dials
 *                               mediamtx itself and decodes/infers on its own schedule; see {@link #pull()}
 *                               for where it dials. Validated in the compact constructor so a typo fails
 *                               at context startup, the same treatment {@link #wireFormat} already gets
 * @param responseTimeout        per-pending-future response timeout on the detection bidi stream; default 2s
 * @param keepAliveTime          HTTP/2 keepalive PING interval; default 20s
 * @param keepAliveTimeout       keepalive PING ack deadline; default 5s
 * @param keepAliveWithoutCalls  whether to send keepalive PINGs on an otherwise idle channel; default {@code true}
 * @param channelShutdownTimeout how long {@code GrpcDetectionPort#close()} awaits graceful channel
 *                               termination; default 5s
 * @param plaintext              whether the host/port channel skips TLS; default {@code true}
 * @param upload                 {@code GrpcDatasetUploadPort}'s per-call deadline/chunk-framing size;
 *                               defaulted as a whole when absent
 * @param registry               {@code GrpcModelRegistryPort}'s per-call deadline; defaulted as a whole
 *                               when absent
 * @param pull                   worker-pull config surface (docs/plans/active/MEDIA-SOT-PLAN.md §5.5);
 *                               defaulted as a whole when absent; only read when {@link #frameTransport()}
 *                               is {@code pull}
 * @param reconnect              bounded reconnect-cadence config for the shared push/channel path
 *                               (docs/plans/active/CV-RECONNECT-PLAN.md §3.3) — {@code
 *                               CvChannelSupervisor}'s own knobs; defaulted as a whole when absent
 */
@ConfigurationProperties(prefix = "vision.cv")
public record VisionCvProperties(@DefaultValue("false") boolean enabled,
                                  @DefaultValue(VisionCvProperties.DEFAULT_ENDPOINT) String endpoint,
                                  @DefaultValue(VisionCvProperties.DEFAULT_DETECT_WIDTH) int detectWidth,
                                  @DefaultValue(VisionCvProperties.DEFAULT_JPEG_QUALITY) float jpegQuality,
                                  @DefaultValue(VisionCvProperties.DEFAULT_WIRE_FORMAT) String wireFormat,
                                  @DefaultValue(VisionCvProperties.DEFAULT_FRAME_TRANSPORT) String frameTransport,
                                  @DefaultValue("2s") Duration responseTimeout,
                                  @DefaultValue("20s") Duration keepAliveTime,
                                  @DefaultValue("5s") Duration keepAliveTimeout,
                                  @DefaultValue("true") boolean keepAliveWithoutCalls,
                                  @DefaultValue("5s") Duration channelShutdownTimeout,
                                  @DefaultValue("true") boolean plaintext,
                                  Upload upload,
                                  Registry registry,
                                  Pull pull,
                                  Reconnect reconnect) {

    static final String DEFAULT_ENDPOINT = "localhost:50051";
    static final String DEFAULT_DETECT_WIDTH = "640";
    static final String DEFAULT_JPEG_QUALITY = "0.8";
    static final String DEFAULT_WIRE_FORMAT = "auto";
    static final String DEFAULT_FRAME_TRANSPORT = "push";
    private static final String PULL_FRAME_TRANSPORT = "pull";
    private static final int MIN_DETECT_WIDTH = 64;

    @ConstructorBinding
    public VisionCvProperties {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("vision.cv.endpoint must not be blank");
        }
        if (detectWidth < MIN_DETECT_WIDTH) {
            throw new IllegalArgumentException(
                    "vision.cv.detect-width must be >= " + MIN_DETECT_WIDTH + ", was " + detectWidth);
        }
        if (jpegQuality <= 0f || jpegQuality > 1f) {
            throw new IllegalArgumentException("vision.cv.jpeg-quality must be in (0,1], was " + jpegQuality);
        }
        try {
            WireFormat.parse(wireFormat);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("vision.cv.wire-format must be auto|jpeg|bgr24, was " + wireFormat, e);
        }
        if (!DEFAULT_FRAME_TRANSPORT.equals(frameTransport) && !PULL_FRAME_TRANSPORT.equals(frameTransport)) {
            throw new IllegalArgumentException(
                    "vision.cv.frame-transport must be push|pull, was " + frameTransport);
        }
        if (upload == null) {
            upload = new Upload(Upload.DEFAULT_TIMEOUT_DURATION, Upload.DEFAULT_CHUNK_BYTES_INT);
        }
        if (registry == null) {
            registry = new Registry(Registry.DEFAULT_CALL_TIMEOUT_DURATION);
        }
        if (pull == null) {
            pull = new Pull(Pull.DEFAULT_RTSP_BASE_URI, Pull.DEFAULT_RECONNECT_INITIAL_BACKOFF,
                    Pull.DEFAULT_RECONNECT_MAX_BACKOFF);
        }
        if (reconnect == null) {
            reconnect = new Reconnect(true, Reconnect.DEFAULT_INITIAL_BACKOFF, Reconnect.DEFAULT_MAX_BACKOFF,
                    Reconnect.DEFAULT_OUTAGE_LOG_INTERVAL);
        }
    }

    /**
     * Convenience constructor covering just the four original {@code vision.cv.*} fields (docs/plans/done/MVP1-PLAN.md
     * §C7/docs/plans/done/REMOTE-CV-PLAN.md P1 item 5, predating wave F4's extension) — every field wave F4 (and
     * docs/plans/active/MEDIA-SOT-PLAN.md wave M7) added defaults to {@code GrpcCvSettings}'s own literal, so
     * behavior constructing an instance this way is unchanged.
     */
    public VisionCvProperties(boolean enabled, String endpoint, int detectWidth, float jpegQuality) {
        this(enabled, endpoint, detectWidth, jpegQuality, DEFAULT_WIRE_FORMAT, DEFAULT_FRAME_TRANSPORT,
                Duration.ofSeconds(2), Duration.ofSeconds(20), Duration.ofSeconds(5), true, Duration.ofSeconds(5),
                true, null, null, null, null);
    }

    /**
     * @return {@code true} when {@link #frameTransport()} is {@code pull} — the switch-B check every
     *         wiring decision in this deployment reads instead of comparing the raw string a second time
     */
    public boolean pullEnabled() {
        return PULL_FRAME_TRANSPORT.equals(frameTransport);
    }

    /**
     * @return the host component of {@link #endpoint()}, e.g. {@code localhost}
     */
    public String host() {
        return hostAndPort()[0];
    }

    /**
     * @return the port component of {@link #endpoint()}, e.g. {@code 50051}
     */
    public int port() {
        return Integer.parseInt(hostAndPort()[1]);
    }

    private String[] hostAndPort() {
        String value = endpoint;
        int schemeIdx = value.indexOf("://");
        if (schemeIdx >= 0) {
            value = value.substring(schemeIdx + 3);
        }
        int colonIdx = value.lastIndexOf(':');
        if (colonIdx <= 0 || colonIdx == value.length() - 1) {
            throw new IllegalArgumentException("vision.cv.endpoint must be host:port, got: " + endpoint);
        }
        return new String[] {value.substring(0, colonIdx), value.substring(colonIdx + 1)};
    }

    /**
     * @param timeout    per-call deadline covering dataset archive framing plus the whole upload
     *                   round trip; default 300s
     * @param chunkBytes target size of each streamed dataset zip chunk; default {@value #DEFAULT_CHUNK_BYTES}
     */
    public record Upload(@DefaultValue("300s") Duration timeout,
                          @DefaultValue(Upload.DEFAULT_CHUNK_BYTES) int chunkBytes) {
        static final String DEFAULT_CHUNK_BYTES = "262144";
        static final Duration DEFAULT_TIMEOUT_DURATION = Duration.ofSeconds(300);
        static final int DEFAULT_CHUNK_BYTES_INT = 262_144;
    }

    /**
     * @param callTimeout per-call deadline for {@code GrpcModelRegistryPort}'s {@code
     *                    ListModels}/{@code PromoteModel} RPCs; default 10s
     */
    public record Registry(@DefaultValue("10s") Duration callTimeout) {
        static final Duration DEFAULT_CALL_TIMEOUT_DURATION = Duration.ofSeconds(10);
    }

    /**
     * Worker-pull config surface (docs/plans/active/MEDIA-SOT-PLAN.md §5.5) — mirrors {@code
     * GrpcCvSettings}'s own {@code pullRtspBase}/{@code pullReconnectInitialBackoff}/{@code
     * pullReconnectMaxBackoff} fields one-to-one, the same "this record maps onto that adapter
     * settings object" shape {@link Upload}/{@link Registry} already have.
     *
     * @param rtspBase                base RTSP URL the <b>worker</b> (cv-service) dials for a pulled
     *                                stream — deliberately separate from {@code
     *                                vision.publish.mediamtx.rtsp-base}: a remote worker (the GB4005
     *                                box) must dial the host's LAN address, not {@code localhost}, even
     *                                though both properties often point at the same mediamtx instance
     *                                in a single-box deployment. Default {@value #DEFAULT_RTSP_BASE},
     *                                the local compose mediamtx
     * @param reconnectInitialBackoff how long a pulled stream's reopen waits before its first retry
     *                                after a failure; mirrors {@code vision.publish.resilience
     *                                .initial-backoff}'s shape and default (500ms)
     * @param reconnectMaxBackoff     cap the doubling reconnect backoff never exceeds; mirrors {@code
     *                                vision.publish.resilience.max-backoff}'s shape and default (10s)
     */
    public record Pull(@DefaultValue(Pull.DEFAULT_RTSP_BASE) URI rtspBase,
                        @DefaultValue("500ms") Duration reconnectInitialBackoff,
                        @DefaultValue("10s") Duration reconnectMaxBackoff) {
        static final String DEFAULT_RTSP_BASE = "rtsp://localhost:8554";
        static final URI DEFAULT_RTSP_BASE_URI = URI.create(DEFAULT_RTSP_BASE);
        static final Duration DEFAULT_RECONNECT_INITIAL_BACKOFF = Duration.ofMillis(500);
        static final Duration DEFAULT_RECONNECT_MAX_BACKOFF = Duration.ofSeconds(10);
    }

    /**
     * Bounded reconnect-cadence config for the shared push/channel path (docs/plans/active/CV-RECONNECT-PLAN.md
     * §2.1/§3.3) — {@code CvChannelSupervisor}'s own knobs, mapped onto {@code GrpcCvSettings}'
     * {@code reconnectInitialBackoff}/{@code reconnectMaxBackoff}/{@code outageLogInterval} one-to-one
     * (the same "this record maps onto that adapter settings object" shape {@link Upload}/{@link
     * Registry}/{@link Pull} already have) — see {@code CvWiring#toGrpcCvSettings}. {@link #enabled()}
     * is not one of those three: it is a {@code vision-app}-only wiring decision (whether {@code
     * CvWiring#cvChannelSupervisor} exists at all and which {@code GrpcDetectionPort} constructor
     * {@code CvWiring#detectionPort} uses), never read by {@code adapter-cv-grpc}.
     *
     * @param enabled           whether {@code CvWiring} wires a {@code CvChannelSupervisor} onto the
     *                          shared channel and gates {@code GrpcDetectionPort} through it; default
     *                          {@code true}. {@code false} is the escape hatch (docs/plans/active/CV-RECONNECT-PLAN.md
     *                          §3.3/§5 item 3) restoring today's exact (pre-R2) behaviour: no gate, no
     *                          forced reconnect, the channel's own escalating backoff only
     * @param initialBackoff    how long the supervisor waits, while the channel is in {@code
     *                          TRANSIENT_FAILURE}, before its first forced {@code
     *                          resetConnectBackoff()} call; default 1s
     * @param maxBackoff        cap the doubling forced-reconnect backoff never exceeds; default 10s
     * @param outageLogInterval how often the supervisor logs an INFO heartbeat while an outage
     *                          continues, instead of a WARN-with-stack-trace flood; default 60s
     */
    public record Reconnect(@DefaultValue("true") boolean enabled,
                             @DefaultValue("1s") Duration initialBackoff,
                             @DefaultValue("10s") Duration maxBackoff,
                             @DefaultValue("60s") Duration outageLogInterval) {
        static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofSeconds(1);
        static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(10);
        static final Duration DEFAULT_OUTAGE_LOG_INTERVAL = Duration.ofSeconds(60);
    }
}
