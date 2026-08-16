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
 * @param detectionDefaultEnabled what a <b>new</b> stream's {@code PipelineConfig#detectionEnabled}
 *                               starts at (docs/plans/active/CV-DEMAND-PLAN.md §3.7/§3.8) — a
 *                               deployment default that beats {@code PipelineConfig.defaults()} but
 *                               loses to an explicit per-request override; default {@code false}
 * @param demand                 the system-derived detection-demand gate's own tunables
 *                               (docs/plans/active/CV-DEMAND-PLAN.md §2-3.7); defaulted as a whole
 *                               when absent
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
                                  @DefaultValue("false") boolean detectionDefaultEnabled,
                                  Demand demand) {

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
        if (demand == null) {
            demand = new Demand(Demand.DEFAULT_ENABLED, Demand.DEFAULT_POLL_INTERVAL, Demand.DEFAULT_GRACE,
                    Demand.DEFAULT_POLL_TTL);
        }
    }

    /**
     * The canonical constructor before docs/plans/active/CV-DEMAND-PLAN.md wave D2 added {@code
     * detectionDefaultEnabled}/{@code demand}, kept as a convenience constructor defaulting both —
     * the first to {@code false} (the plan's own pinned default), the second to {@link
     * Demand#DEFAULT_ENABLED}/{@link Demand#DEFAULT_POLL_INTERVAL}/{@link Demand#DEFAULT_GRACE}/
     * {@link Demand#DEFAULT_POLL_TTL} — so every pre-existing call site compiles unchanged. Same
     * "N-1-arg convenience ctor" idiom as every other addition to this record.
     */
    public VisionCvProperties(boolean enabled, String endpoint, int detectWidth, float jpegQuality,
                               String wireFormat, String frameTransport, Duration responseTimeout,
                               Duration keepAliveTime, Duration keepAliveTimeout, boolean keepAliveWithoutCalls,
                               Duration channelShutdownTimeout, boolean plaintext, Upload upload, Registry registry,
                               Pull pull) {
        this(enabled, endpoint, detectWidth, jpegQuality, wireFormat, frameTransport, responseTimeout,
                keepAliveTime, keepAliveTimeout, keepAliveWithoutCalls, channelShutdownTimeout, plaintext, upload,
                registry, pull, false, null);
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
                true, null, null, null);
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
     * The system-derived detection-demand gate's own tunables (docs/plans/active/CV-DEMAND-PLAN.md
     * §2-3.7) — mapped by {@code CvWiring#detectionDemandPort} onto a {@code
     * LiveAndPollDetectionDemand} bean, and by {@code ApplicationServiceWiring#streamPipelineSettings}
     * onto {@code StreamPipelineSettings#detectionDemandPollInterval}/{@code #detectionDemandGrace}.
     *
     * @param enabled      whether the demand gate is wired at all. {@code false} means {@code
     *                     CvWiring} never builds the {@code DetectionDemandPort} bean, so {@code
     *                     DefaultStreamService}'s demand-poll task is never scheduled and every
     *                     stream's {@code detectionDemand} stays fail-open {@code true} forever —
     *                     today's un-gated behavior, and the escape hatch for a deployment that
     *                     needs unattended detection to keep running with nobody watching. Default
     *                     {@code true}
     * @param pollInterval how often {@code DefaultStreamService}'s demand-poll task re-evaluates
     *                     every running stream's demand; default 2s
     * @param grace        how long a stream keeps detecting after its last observed demand, so
     *                     navigating between pages does not thrash the detector on and off; default
     *                     30s
     * @param pollTtl      how long one {@code GET /api/streams/{id}/detections} poll counts as
     *                     demand; default 10s
     */
    public record Demand(@DefaultValue("true") boolean enabled,
                          @DefaultValue("2s") Duration pollInterval,
                          @DefaultValue("30s") Duration grace,
                          @DefaultValue("10s") Duration pollTtl) {
        static final boolean DEFAULT_ENABLED = true;
        static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(2);
        static final Duration DEFAULT_GRACE = Duration.ofSeconds(30);
        static final Duration DEFAULT_POLL_TTL = Duration.ofSeconds(10);
    }
}
