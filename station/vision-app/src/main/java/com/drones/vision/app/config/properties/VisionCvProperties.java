package com.drones.vision.app.config.properties;

import com.drones.vision.adapter.cvgrpc.CvTarget;
import com.drones.vision.adapter.cvgrpc.WireFormat;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.List;

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
 * @param frameTransport         {@code push} | {@code pull} (docs/plans/done/MEDIA-SOT-PLAN.md §3 switch
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
 * @param pull                   worker-pull config surface (docs/plans/done/MEDIA-SOT-PLAN.md §5.5);
 *                               defaulted as a whole when absent; only read when {@link #frameTransport()}
 *                               is {@code pull}
 * @param detectionDefaultEnabled what a <b>new</b> stream's {@code PipelineConfig#detectionEnabled}
 *                               starts at (docs/plans/done/CV-DEMAND-PLAN.md §3.7/§3.8) — a
 *                               deployment default that beats {@code PipelineConfig.defaults()} but
 *                               loses to an explicit per-request override; default {@code false}
 * @param demand                 the system-derived detection-demand gate's own tunables
 *                               (docs/plans/done/CV-DEMAND-PLAN.md §2-3.7); defaulted as a whole
 *                               when absent
 * @param reconnect              bounded reconnect-cadence config for the shared push/channel path
 *                               (docs/plans/active/CV-RECONNECT-PLAN.md §3.3) — {@code
 *                               CvChannelSupervisor}'s own knobs; defaulted as a whole when absent
 * @param inference              the inference channel's failover target list (docs/plans/active/
 *                               ARCHITECTURE-AUDIT-2026-08-26.md R6, split deployment); defaulted as
 *                               a whole when absent — see {@link #inferenceTargets()}
 * @param training               the training/geolocation channel's single target (R6, split
 *                               deployment); defaulted as a whole when absent — see
 *                               {@link #trainingTarget()}/{@link #trainingTargetConfigured()}
 * @param profiles               {@code CvProfileCache}'s lazy-reload TTL (docs/plans/active/CV-SETTINGS-PLAN.md
 *                               §3.1, docs/plans/active/CV-SETTINGS-CONTEXT.md's W2 &rarr; W5 handoff);
 *                               defaulted as a whole when absent — see {@link Profiles#cacheTtl()}
 * @param policy                 {@code DetectionPolicyCache}'s own refresh cadence
 *                               (docs/plans/active/ALWAYS-ON-FLOW-PLAN.md wave D1); defaulted as a
 *                               whole when absent — see {@link Policy#refreshInterval()}
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
                                  Demand demand,
                                  Reconnect reconnect,
                                  Inference inference,
                                  Training training,
                                  Profiles profiles,
                                  Policy policy) {

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
            // Java-construction fallback (bypassing Spring's Environment entirely -- direct `new
            // VisionCvProperties(...)` callers, e.g. tests): mirrors the YAML placeholder
            // `vision.cv.registry.enabled: ${vision.cv.enabled:false}` binds by reusing this
            // record's own top-level `enabled` (detection's switch) as the registry's default.
            registry = new Registry(enabled, Registry.DEFAULT_CALL_TIMEOUT_DURATION);
        }
        if (pull == null) {
            pull = new Pull(Pull.DEFAULT_RTSP_BASE_URI, Pull.DEFAULT_RECONNECT_INITIAL_BACKOFF,
                    Pull.DEFAULT_RECONNECT_MAX_BACKOFF);
        }
        if (demand == null) {
            demand = new Demand(Demand.DEFAULT_ENABLED, Demand.DEFAULT_POLL_INTERVAL, Demand.DEFAULT_GRACE,
                    Demand.DEFAULT_POLL_TTL);
        }
        if (reconnect == null) {
            reconnect = new Reconnect(true, Reconnect.DEFAULT_INITIAL_BACKOFF, Reconnect.DEFAULT_MAX_BACKOFF,
                    Reconnect.DEFAULT_OUTAGE_LOG_INTERVAL);
        }
        if (inference == null) {
            inference = new Inference(Inference.DEFAULT_TARGETS);
        }
        if (training == null) {
            training = new Training(Training.DEFAULT_TARGET);
        }
        if (profiles == null) {
            profiles = new Profiles(Profiles.DEFAULT_CACHE_TTL);
        }
        if (policy == null) {
            policy = new Policy(Policy.DEFAULT_REFRESH_INTERVAL);
        }
        if (!inference.targets().isEmpty()) {
            try {
                CvTarget.parseAll(inference.targets());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "vision.cv.inference.targets entries must each be host:port, was " + inference.targets(), e);
            }
        }
        if (!training.target().isBlank()) {
            try {
                CvTarget.parse(training.target());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "vision.cv.training.target must be host:port, was " + training.target(), e);
            }
        }
    }

    /**
     * The canonical constructor before docs/plans/done/CV-DEMAND-PLAN.md wave D2 added {@code
     * detectionDefaultEnabled}/{@code demand} and docs/plans/active/CV-RECONNECT-PLAN.md added
     * {@code reconnect}, kept as a convenience constructor defaulting all three — the first to
     * {@code false} (that plan's own pinned default), the other two to their record defaults via the
     * compact constructor's {@code null} handling — so every pre-existing call site compiles
     * unchanged. Same "N-1-arg convenience ctor" idiom as every other addition to this record.
     *
     * <p><b>Legacy, slated for removal.</b> The "N-1-arg convenience ctor" idiom this javadoc
     * describes is withdrawn (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R1, {@code
     * .claude/skills/java-clean-code/SKILL.md} §3) — {@link #inference}/{@link #training} were added
     * without a new overload here; this constructor is kept only so its existing callers keep
     * compiling, not extended further.
     */
    public VisionCvProperties(boolean enabled, String endpoint, int detectWidth, float jpegQuality,
                               String wireFormat, String frameTransport, Duration responseTimeout,
                               Duration keepAliveTime, Duration keepAliveTimeout, boolean keepAliveWithoutCalls,
                               Duration channelShutdownTimeout, boolean plaintext, Upload upload, Registry registry,
                               Pull pull) {
        this(enabled, endpoint, detectWidth, jpegQuality, wireFormat, frameTransport, responseTimeout,
                keepAliveTime, keepAliveTimeout, keepAliveWithoutCalls, channelShutdownTimeout, plaintext, upload,
                registry, pull, false, null, null, null, null, null, null);
    }

    /**
     * Convenience constructor covering just the four original {@code vision.cv.*} fields (docs/plans/done/MVP1-PLAN.md
     * §C7/docs/plans/done/REMOTE-CV-PLAN.md P1 item 5, predating wave F4's extension) — every field wave F4 (and
     * docs/plans/done/MEDIA-SOT-PLAN.md wave M7) added defaults to {@code GrpcCvSettings}'s own literal, so
     * behavior constructing an instance this way is unchanged.
     *
     * <p><b>Legacy, slated for removal</b> — same R1 withdrawal note as the 15-arg constructor above;
     * kept only so its existing callers keep compiling, not extended further.
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
     * @return the inference channel's failover target list (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md
     *         R6) — {@link Inference#targets()} parsed via {@code CvTarget#parseAll} when non-empty,
     *         else a single-element list built from {@link #host()}/{@link #port()}. Deliberately
     *         {@link #host()}/{@link #port()}, not {@code CvTarget.parse(endpoint())} — {@link
     *         #endpoint()} tolerates an optional {@code scheme://} prefix that {@link #hostAndPort()}
     *         already strips and {@code CvTarget.parse} does not.
     */
    public List<CvTarget> inferenceTargets() {
        if (!inference.targets().isEmpty()) {
            return CvTarget.parseAll(inference.targets());
        }
        return List.of(new CvTarget(host(), port()));
    }

    /**
     * @return the training/geolocation channel's single target (R6) — {@link Training#target()}
     *         parsed via {@code CvTarget#parse} when non-blank, else {@link #host()}/{@link #port()},
     *         the same one-process fallback {@link #inferenceTargets()} uses
     */
    public CvTarget trainingTarget() {
        if (!training.target().isBlank()) {
            return CvTarget.parse(training.target());
        }
        return new CvTarget(host(), port());
    }

    /**
     * @return whether {@code vision.cv.training.target} was actually set — {@code CvWiring} reads
     *         this (via {@code @ConditionalOnProperty}) to decide whether the {@code
     *         cvTrainingChannel} bean exists at all, distinct from what {@link #trainingTarget()}
     *         would resolve to if asked regardless
     */
    public boolean trainingTargetConfigured() {
        return !training.target().isBlank();
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
     * @param enabled     whether {@code ModelRegistryPort}/{@code ModelRegistryService} (and the
     *                    {@code /api/cv/registry/*} endpoints they back) are wired at all
     *                    (docs/plans/active/CV-SETTINGS-PLAN.md §5, CV-SETTINGS-CONTEXT.md's W4-app
     *                    &rarr; W5 handoff). No {@code @DefaultValue} here deliberately: bound from
     *                    the YAML placeholder {@code vision.cv.registry.enabled:
     *                    ${vision.cv.enabled:false}} in application.yaml, so leaving both keys unset
     *                    keeps the registry following {@link #enabled()} (detection's own switch) —
     *                    a Spring {@code Environment} default, not a record default. The compact
     *                    constructor's {@code registry == null} fallback (a direct-Java {@code new
     *                    VisionCvProperties(...)} call, bypassing Spring's {@code Environment}
     *                    entirely) mirrors the same derivation by reusing this record's own
     *                    top-level {@link #enabled()}
     * @param callTimeout per-call deadline for {@code GrpcModelRegistryPort}'s {@code
     *                    ListModels}/{@code PromoteModel} RPCs; default 10s
     */
    public record Registry(boolean enabled, @DefaultValue("10s") Duration callTimeout) {
        static final Duration DEFAULT_CALL_TIMEOUT_DURATION = Duration.ofSeconds(10);
    }

    /**
     * Worker-pull config surface (docs/plans/done/MEDIA-SOT-PLAN.md §5.5) — mirrors {@code
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
     * The system-derived detection-demand gate's own tunables (docs/plans/done/CV-DEMAND-PLAN.md
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

    /**
     * The inference channel's failover target list (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md
     * R6, split deployment) — an ordered list consumed by {@code CvChannels#forTargets} via {@code
     * CvTarget#parseAll}, tried in order by grpc-java's {@code pick_first} policy on connection
     * failure. Empty (the default) means "no split list configured": {@link #inferenceTargets()}
     * falls back to {@link #host()}/{@link #port()} instead, so a one-process deployment stays
     * byte-identical to before this key existed.
     *
     * @param targets ordered {@code host:port} entries; default empty list
     */
    public record Inference(List<String> targets) {
        static final List<String> DEFAULT_TARGETS = List.of();

        public Inference {
            if (targets == null) {
                targets = DEFAULT_TARGETS;
            }
        }
    }

    /**
     * The training/geolocation channel's single target (R6, split deployment) — consumed by {@code
     * CvChannels#forTarget} via {@code CvTarget#parse}. Blank (the default) means "no separate
     * training channel": {@link #trainingTarget()} falls back to {@link #host()}/{@link #port()} and
     * {@code CvWiring} never builds the {@code cvTrainingChannel} bean at all — byte-identical to
     * before this key existed.
     *
     * @param target a single {@code host:port} entry; default {@code ""}
     */
    public record Training(String target) {
        static final String DEFAULT_TARGET = "";

        public Training {
            if (target == null) {
                target = DEFAULT_TARGET;
            }
        }
    }

    /**
     * {@code CvProfileCache}'s lazy-reload TTL (docs/plans/active/CV-SETTINGS-PLAN.md §3.1,
     * CV-SETTINGS-CONTEXT.md's W2 &rarr; W5 handoff) — profiles ship unconditionally (no feature
     * flag; built-in profiles exist regardless of {@link #enabled()}/{@link Registry#enabled()}),
     * so this record only ever holds the one cache-freshness tunable.
     *
     * @param cacheTtl how long {@code CvProfileCache} serves a stale in-memory snapshot before
     *                 reloading from {@code CvProfileRepositoryPort} on next read; every write
     *                 (create/update/delete/bind/unbind) still refreshes the snapshot immediately
     *                 regardless of this TTL. Default 60s
     */
    public record Profiles(@DefaultValue("60s") Duration cacheTtl) {
        static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(60);
    }

    /**
     * {@code DetectionPolicyCache}'s own refresh cadence (docs/plans/active/ALWAYS-ON-FLOW-PLAN.md
     * wave D1) — this <em>record</em> is always present in {@link VisionCvProperties} regardless of
     * deployment (no feature flag gates the config shape itself; a per-asset {@code DetectionPolicy}
     * attribute is behaviourally inert until an operator sets it to {@code always}, so binding the
     * property costs nothing even when nobody ever touches it). The <em>bean</em> that actually
     * consumes it, {@code CvWiring#detectionPolicyCache}, is however conditional on CV being switched
     * on at all in this deployment (mirroring {@code cvChannelSupervisor}'s own precedent) — see that
     * bean's javadoc. So this record only ever holds the one freshness tunable, same shape as
     * {@link Profiles}.
     *
     * @param refreshInterval how often {@code DetectionPolicyCache} re-lists every asset from {@code
     *                        AssetService} to refresh which ones have {@code DetectionPolicy.ALWAYS}
     *                        set; bounds how stale a just-changed policy attribute can be before a
     *                        stream's next demand-poll tick sees it (see that class's own javadoc for
     *                        the full staleness accounting). Default 15s
     */
    public record Policy(@DefaultValue("15s") Duration refreshInterval) {
        static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofSeconds(15);
    }
}
