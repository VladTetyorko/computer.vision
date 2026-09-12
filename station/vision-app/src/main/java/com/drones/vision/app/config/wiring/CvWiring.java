package com.drones.vision.app.config.wiring;

import com.drones.vision.adapter.cvgrpc.CvChannelSupervisor;
import com.drones.vision.adapter.cvgrpc.CvChannels;
import com.drones.vision.adapter.cvgrpc.GrpcCvInspectClient;
import com.drones.vision.adapter.cvgrpc.GrpcCvSettings;
import com.drones.vision.adapter.cvgrpc.WireFormat;
import com.drones.vision.adapter.cvgrpc.GrpcDetectionPort;
import com.drones.vision.adapter.cvgrpc.GrpcPulledDetectionPort;
import com.drones.vision.api.dto.CvModelResponse;
import com.drones.vision.api.live.LiveAndPollDetectionDemand;
import com.drones.vision.api.live.LiveAndPollTraceDemand;
import com.drones.vision.api.live.LiveUpdateRegistry;
import com.drones.vision.api.support.StreamDetectionSupport;
import com.drones.vision.app.config.properties.VisionCvProperties;
import com.drones.vision.app.cv.DetectionPolicyCache;
import com.drones.vision.app.devsupport.NoopDetectionPort;
import com.drones.vision.app.geo.TrackProjectionRunner;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.learning.application.ConfigModelCatalog;
import com.drones.vision.learning.application.ModelRegistryService;
import com.drones.vision.learning.domain.model.CvModelRecord;
import com.drones.vision.learning.domain.model.ModelProvenance;
import com.drones.vision.learning.domain.model.ModelRuntime;
import com.drones.vision.learning.domain.model.ModelStatus;
import com.drones.vision.learning.domain.model.ModelTaskType;
import com.drones.vision.perception.application.profile.CvProfileService;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.perception.domain.port.DetectionPolicyPort;
import com.drones.vision.perception.domain.port.DetectionPort;
import com.drones.vision.perception.domain.port.PulledDetectionPort;
import com.drones.vision.perception.domain.port.TraceDemandPort;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import com.drones.vision.api.security.CurrentUser;
import io.grpc.ManagedChannel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;

/**
 * Wires the CV inference gRPC connection to cv-service — the CV slice of what used to be one
 * 825-line {@code WiringConfiguration} (docs/plans/active/LAYERING-REFACTOR-PLAN.md wave D). Config extraction
 * (wave F4): {@link #detectionPort} now builds a {@code GrpcCvSettings} from {@link
 * VisionCvProperties} instead of passing just {@code detectWidth}/{@code jpegQuality} — every
 * mapped default is byte-identical to the literal it replaced, and this deletes the {@code
 * CV_KEEPALIVE_TIME_SECONDS}/{@code CV_KEEPALIVE_TIMEOUT_SECONDS} constants this class's
 * predecessor used to duplicate from {@code GrpcCvSettings}'s own (formerly package-private,
 * unreachable-from-here) constants.
 *
 * <p>{@code TrainingWiringConfiguration}'s {@code datasetUploadPort}/{@code modelRegistryPort}
 * beans reuse {@link #toGrpcCvSettings} (datasetUploadPort) and {@link
 * VisionCvProperties.Registry#callTimeout()} (modelRegistryPort) — the same shared channel and
 * settings mapping this class builds.
 *
 * <p>Bounded reconnect (docs/plans/active/CV-RECONNECT-PLAN.md, wave R2): {@link #cvChannelSupervisor}
 * watches {@link #cvGrpcChannel} and gates {@link #detectionPort} through it — see both beans' own
 * javadoc for the conditional/lifecycle reasoning and the {@code vision.cv.reconnect.enabled=false}
 * escape hatch. The same wave also fixed two knobs {@link #cvGrpcChannel} silently ignored (plaintext,
 * sub-second keepalive) — see that bean's own javadoc.
 */
@Configuration
@EnableConfigurationProperties(VisionCvProperties.class)
public class CvWiring {

    /**
     * The shared gRPC connection to cv-service (docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2 T9): one
     * {@link ManagedChannel} for both {@link #detectionPort}'s {@code GrpcDetectionPort} ({@code
     * Inference/DetectStream}, the per-frame hot path) and {@code TrainingWiringConfiguration}'s
     * {@code modelRegistryPort}/{@code trainingPort} beans ({@code Training/*}, control-plane) — so
     * the platform holds one connection to cv-service, not two independently configured ones.
     *
     * <p>Present whenever any property enables a consumer: {@link VisionCvProperties#enabled()}
     * (live push-mode detection), {@code VisionTrainingProperties#enabled()} (the model registry
     * <em>and</em> training port), {@link VisionCvProperties#pullEnabled()}
     * (docs/plans/done/MEDIA-SOT-PLAN.md wave M7, switch B — {@link #pulledDetectionPort} needs the
     * same channel {@code DetectPulled} rides on), <strong>or</strong> {@code vision.geo.visual.enabled}
     * (docs/plans/done/VISUAL-GEO-V2-PLAN.md D3 — visual geolocation's {@code GeoLocate}/{@code
     * BuildReferenceIndex} calls reuse this same channel and {@link #cvChannelSupervisor} rather than
     * opening a second one; see {@code VisualGeoWiringConfiguration} in {@code station/vision-app}).
     * With every flag off (the default), no channel is built at all.
     *
     * <h2>Shutdown ownership</h2>
     * This bean — not either port — owns the channel's lifecycle ({@code destroyMethod =
     * "shutdown"}). {@link #detectionPort} disables {@code GrpcDetectionPort}'s own inferred {@code
     * close()} destroy call via an explicit empty {@code destroyMethod}.
     *
     * <h2>Two fixed knobs (docs/plans/active/CV-RECONNECT-PLAN.md §3.3a)</h2>
     * <ul>
     *   <li><b>{@code .usePlaintext()} is now conditional on {@link VisionCvProperties#plaintext()}</b>
     *   instead of being called unconditionally — previously {@code vision.cv.plaintext=false} left the
     *   connection unencrypted while the operator believed TLS was enabled. Default stays {@code true},
     *   so the default path is byte-identical. <b>Honest limit</b>: {@code plaintext: false} yields the
     *   JDK default trust chain and will <b>not</b> validate a self-signed cv-service certificate —
     *   custom trust material is a separate concern, not built here.</li>
     *   <li><b>Keepalive durations now use {@code .toMillis()}</b> instead of {@code .toSeconds()},
     *   matching {@code GrpcDetectionPort#buildChannel} — the seconds form silently truncated any
     *   sub-second value (e.g. {@code keepalive-time: 500ms} became {@code 0}).</li>
     * </ul>
     *
     * <h2>Failover target list (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R6)</h2>
     * Now the <b>inference</b> channel specifically: built from {@link VisionCvProperties#inferenceTargets()}
     * via {@code CvChannels#forTargets} rather than a hand-rolled {@code ManagedChannelBuilder.forAddress}
     * call. {@code CvChannels#forTargets}'s own {@code applyCommonSettings} helper (verified by reading
     * that class before this bean was rewritten) applies exactly the same plaintext-conditional-on-{@link
     * VisionCvProperties#plaintext()} and three keepalive settings in {@code .toMillis()} this bean used
     * to apply inline — so a deployment that leaves {@code vision.cv.inference.targets} unset (the
     * default) gets a single-target channel byte-identical to before this rewrite: {@link
     * VisionCvProperties#inferenceTargets()} falls back to {@link VisionCvProperties#host()}/{@link
     * VisionCvProperties#port()}, and {@code CvChannels#forTargets} routes a one-element list straight
     * through {@code forTarget} with no custom resolver in the path at all. Marked {@link Primary} —
     * every pre-existing unqualified {@code ManagedChannel} injection point in this class and its
     * siblings keeps resolving this bean even after {@link #cvTrainingChannel} exists too.
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnExpression("${vision.cv.enabled:false} or ${vision.training.enabled:false} "
            + "or '${vision.cv.frame-transport:push}' == 'pull' or ${vision.geo.visual.enabled:false} "
            + "or ${vision.cv.registry.enabled:false}")
    @Primary
    public ManagedChannel cvGrpcChannel(VisionCvProperties cvProperties) {
        GrpcCvSettings settings = toGrpcCvSettings(cvProperties);
        return CvChannels.forTargets(cvProperties.inferenceTargets(), settings);
    }

    /**
     * The <b>training/geolocation</b> channel (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R6) —
     * present only when {@code vision.cv.training.target} is actually set ({@link
     * VisionCvProperties#trainingTargetConfigured()}), so a deployment that never sets it builds no
     * second channel at all and every {@code Training/*}/{@code Geolocation/*} consumer keeps resolving
     * {@link #cvGrpcChannel} exactly as before this bean existed — see {@link #controlPlaneChannel} for
     * how consumers pick between the two.
     *
     * <p><b>Honest limit</b>: setting {@code vision.cv.training.target} to the same {@code host:port} as
     * the inference target (or as {@link VisionCvProperties#endpoint()}) opens a <em>second</em>,
     * independently-configured TCP connection to the same cv-service process — this key is meant for a
     * genuinely split deployment (the {@code cv-split} Compose profile's {@code cv-service-training} on
     * a different port/host than {@code cv-service-inference}), not a way to get two channels to one
     * process for free.
     *
     * <p>Owns its own shutdown ({@code destroyMethod = "shutdown"}), independent of {@link
     * #cvGrpcChannel}'s lifecycle — the two channels never share a shutdown path since they may not even
     * both exist.
     */
    @Bean(name = "cvTrainingChannel", destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "vision.cv", name = "training.target")
    public ManagedChannel cvTrainingChannel(VisionCvProperties cvProperties) {
        GrpcCvSettings settings = toGrpcCvSettings(cvProperties);
        return CvChannels.forTarget(cvProperties.trainingTarget(), settings);
    }

    /**
     * Picks the control-plane ({@code Training/*}/{@code Geolocation/*}) channel: {@link
     * #cvTrainingChannel} when it exists (a split deployment), else {@link #cvGrpcChannel} (the default,
     * one-process case). Package-private — {@code TrainingWiringConfiguration}/{@code
     * VisualGeoWiringConfiguration} both call this rather than re-deriving the same fallback, so the
     * "training beats inference, inference is the fallback" decision has exactly one home.
     */
    static ManagedChannel controlPlaneChannel(ObjectProvider<ManagedChannel> cvTrainingChannel,
                                               ObjectProvider<ManagedChannel> cvGrpcChannel) {
        return cvTrainingChannel.getIfAvailable(cvGrpcChannel::getObject);
    }

    /**
     * Maps every {@code GrpcCvSettings} field off {@link VisionCvProperties} (docs/plans/active/LAYERING-REFACTOR-PLAN.md
     * wave F4, extended by docs/plans/done/MEDIA-SOT-PLAN.md wave M7 with the three {@code pull*}
     * fields and docs/plans/active/CV-RECONNECT-PLAN.md wave R2 with the three push/channel {@code
     * reconnect*}/{@code outageLogInterval} fields) — shared by {@link #cvGrpcChannel}/{@link
     * #detectionPort}/{@link #cvChannelSupervisor}/{@link #pulledDetectionPort} here and {@code
     * TrainingWiringConfiguration#datasetUploadPort}.
     */
    static GrpcCvSettings toGrpcCvSettings(VisionCvProperties properties) {
        VisionCvProperties.Upload upload = properties.upload();
        VisionCvProperties.Pull pull = properties.pull();
        VisionCvProperties.Reconnect reconnect = properties.reconnect();
        return new GrpcCvSettings(properties.responseTimeout(), properties.keepAliveTime(),
                properties.keepAliveTimeout(), properties.keepAliveWithoutCalls(), properties.channelShutdownTimeout(),
                properties.plaintext(), upload.timeout(), upload.chunkBytes(), properties.detectWidth(),
                properties.jpegQuality(), WireFormat.parse(properties.wireFormat()), pull.rtspBase(),
                pull.reconnectInitialBackoff(), pull.reconnectMaxBackoff(), reconnect.initialBackoff(),
                reconnect.maxBackoff(), reconnect.outageLogInterval());
    }

    /**
     * The single owner of "is the shared cv-service channel reachable, and if not, keep trying at a
     * bounded cadence" (docs/plans/active/CV-RECONNECT-PLAN.md, wave R2) — watches {@link #cvGrpcChannel}
     * and is handed to {@link #detectionPort} below, which gates {@code GrpcDetectionPort#detect}
     * through it once a supervisor is present.
     *
     * <h2>Why {@code @ConditionalOnExpression}, extending {@link #cvGrpcChannel}'s own condition,
     * and deliberately NOT {@code @ConditionalOnBean(ManagedChannel.class)}</h2>
     * {@code @ConditionalOnBean} evaluates against bean *definitions* already processed at the point
     * this configuration class is parsed — inside one {@code @Configuration} class, that makes it
     * sensitive to declaration order between {@code @Bean} methods, a fragile property that can pass
     * in a narrow test slice and silently fail to match in full production wiring (or vice versa)
     * depending on classpath/bean-scanning order. Repeating the same property expression {@link
     * #cvGrpcChannel} already uses, ANDed with {@code vision.cv.reconnect.enabled}, is order-independent
     * and states the actual intent directly: "whenever the channel exists AND reconnect is turned on."
     *
     * <h2>Lifecycle</h2>
     * {@code initMethod = "start"} arms the watch loop as soon as this bean is constructed;
     * {@code destroyMethod = "close"} stops only this bean's own watch loop and {@code
     * cv-channel-supervisor} scheduler thread — it <b>never</b> shuts the channel down (see {@link
     * #cvGrpcChannel}'s own {@code destroyMethod = "shutdown"}, which stays the sole owner of that).
     *
     * <p>{@code vision.cv.reconnect.enabled=false} (the escape hatch, docs/plans/active/CV-RECONNECT-PLAN.md
     * §3.3/§5 item 3) means this bean does not exist at all — {@link #detectionPort} then falls back to
     * the two-arg {@code GrpcDetectionPort} constructor (no gate), reproducing today's exact behaviour.
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnExpression("(${vision.cv.enabled:false} or ${vision.training.enabled:false} "
            + "or '${vision.cv.frame-transport:push}' == 'pull' or ${vision.geo.visual.enabled:false} "
            + "or ${vision.cv.registry.enabled:false}) "
            + "and ${vision.cv.reconnect.enabled:true}")
    public CvChannelSupervisor cvChannelSupervisor(VisionCvProperties cvProperties,
            @Qualifier("cvGrpcChannel") ObjectProvider<ManagedChannel> cvGrpcChannel) {
        return new CvChannelSupervisor(cvGrpcChannel.getObject(), toGrpcCvSettings(cvProperties));
    }

    /**
     * {@code Inference/Inspect} client for {@link SystemStatusWiring#cvServiceStatus}'s capacity
     * fields (docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.9, wave W2) — rides the same shared
     * {@link #cvGrpcChannel} {@link #detectionPort} streams frames over, so this bean's own condition
     * mirrors {@link #cvGrpcChannel}'s exactly (same order-independence reasoning as {@link
     * #cvChannelSupervisor}'s own javadoc): whenever the channel exists, Inspect can be asked over it.
     * Independent of {@code vision.cv.reconnect.enabled} — Inspect needs no reconnect supervision of
     * its own, it either answers or {@link CvStatusProvider} catches the failure.
     */
    @Bean
    @ConditionalOnExpression("${vision.cv.enabled:false} or ${vision.training.enabled:false} "
            + "or '${vision.cv.frame-transport:push}' == 'pull' or ${vision.geo.visual.enabled:false} "
            + "or ${vision.cv.registry.enabled:false}")
    public GrpcCvInspectClient cvInspectClient(@Qualifier("cvGrpcChannel") ObjectProvider<ManagedChannel> cvGrpcChannel) {
        return new GrpcCvInspectClient(cvGrpcChannel.getObject());
    }

    /**
     * Selects the {@link DetectionPort} implementation per {@link VisionCvProperties#enabled()}
     * (docs/plans/done/MVP1-PLAN.md §C7 bullet 4): {@code true} wires {@code GrpcDetectionPort}
     * (adapter-cv-grpc) against the shared {@link #cvGrpcChannel}; {@code false} (the default)
     * keeps today's {@link NoopDetectionPort}.
     *
     * <p><strong>{@code destroyMethod = ""}, deliberately</strong> — disables {@code @Bean}'s
     * default destroy-method inference (which would otherwise call {@code GrpcDetectionPort#close()}
     * at context shutdown, unconditionally shutting the <em>shared</em> channel down).
     *
     * <h2>Reconnect gate (docs/plans/active/CV-RECONNECT-PLAN.md, wave R2)</h2>
     * When {@link #cvChannelSupervisor} is present (i.e. {@code vision.cv.reconnect.enabled=true}, the
     * default), this uses {@code GrpcDetectionPort}'s three-arg constructor so {@code detect(...)}
     * checks the supervisor's gate before doing any work. When it is absent — either {@code
     * vision.cv.reconnect.enabled=false} or neither of {@link #cvGrpcChannel}'s own enabling properties
     * matched (impossible while {@link VisionCvProperties#enabled()} is {@code true}, since that alone
     * satisfies {@link #cvGrpcChannel}'s condition) — this falls back to the pre-R2 two-arg constructor,
     * which has no gate and behaves byte-identically to before this wave.
     */
    @Bean(destroyMethod = "")
    public DetectionPort detectionPort(VisionCvProperties cvProperties,
            @Qualifier("cvGrpcChannel") ObjectProvider<ManagedChannel> cvGrpcChannel,
            ObjectProvider<CvChannelSupervisor> cvChannelSupervisor) {
        if (cvProperties.enabled()) {
            GrpcCvSettings settings = toGrpcCvSettings(cvProperties);
            CvChannelSupervisor supervisor = cvChannelSupervisor.getIfAvailable();
            if (supervisor != null) {
                return new GrpcDetectionPort(cvGrpcChannel.getObject(), settings, supervisor);
            }
            return new GrpcDetectionPort(cvGrpcChannel.getObject(), settings);
        }
        return new NoopDetectionPort();
    }

    /**
     * Present only when {@link VisionCvProperties#pullEnabled()} — switch B, docs/plans/done/MEDIA-SOT-PLAN.md
     * §3/§5.5 — is {@code pull}: {@link ApplicationServiceWiring#streamService} then wraps this bean in
     * a {@code PullDetectionSettings} and every stream this deployment starts subscribes to {@code
     * DetectPulled} instead of pushing frames over {@link #detectionPort}. Absent (the default,
     * {@code frame-transport=push}, D1) means {@code ApplicationServiceWiring} never builds a {@code
     * PullDetectionSettings} at all — no behaviour change from before this port existed.
     *
     * <p>Shares {@link #cvGrpcChannel} with {@link #detectionPort}, the same "one connection to
     * cv-service" contract every gRPC-backed bean in this class already follows — see that bean's own
     * javadoc for why its condition includes {@code pullEnabled()} too.
     */
    @Bean
    @ConditionalOnExpression("'${vision.cv.frame-transport:push}' == 'pull'")
    public PulledDetectionPort pulledDetectionPort(VisionCvProperties cvProperties,
            @Qualifier("cvGrpcChannel") ObjectProvider<ManagedChannel> cvGrpcChannel) {
        return new GrpcPulledDetectionPort(cvGrpcChannel.getObject(), toGrpcCvSettings(cvProperties));
    }

    /**
     * The detection-model roster {@code CvModelsController} (component-scanned from {@code
     * vision-api}) serves at {@code GET /api/cv/models} (docs/plans/done/CV-CONTROL-PLAN.md §4's frozen wire
     * contract) — the Fly cockpit's model picker builds its dropdown from exactly this list.
     *
     * <p>Deliberately a static, in-source constant, not the dormant {@code ModelRegistryPort}
     * (docs/plans/done/CV-CONTROL-PLAN.md §D): that port models versioned promote/rollback (a Phase-3
     * training-studio concern) and has no implementation — wiring it now for a picker that only
     * needs a display list would be over-building.
     */
    @Bean
    public List<CvModelResponse> cvModelRoster() {
        return List.of(
                new CvModelResponse("yolo26n.pt", "General (people & vehicles, fast)", "general", false, List.of(),
                        null, null, null, null, null, null, null, null, null),
                new CvModelResponse("orion12l.pt", "Military vehicles", "specialized", false, List.of(), null, null,
                        null, null, null, null, null, null, null),
                new CvModelResponse("yoloe-26s-seg-pf.pt", "Everything (incl. buildings, slower)", "open-vocab",
                        true, List.of(), null, null, null, null, null, null, null, null, null));
    }

    /**
     * {@link ModelRegistryService#models()}'s worker-unreachable fallback, and {@code
     * CvModelsController}'s own fallback when {@code ModelRegistryService} is not wired at all
     * (docs/plans/active/CV-SETTINGS-PLAN.md §8 OQ5, CV-SETTINGS-CONTEXT.md's W4-app → W5 handoff) —
     * maps {@link #cvModelRoster} (unchanged, still the picker's original three-entry list) onto the
     * {@code CvModelRecord} shape the registry's own catalogue deals in. Every field this static
     * roster cannot supply falls back to the same stand-in {@code DefaultModelRegistryService} itself
     * uses when it has to synthesize a row from scratch (see {@code CvModelView#synthesize}): {@code
     * DRAFT} status, {@link ModelProvenance#none()}, no metrics, never promoted, version {@code
     * "latest"}, task type {@code DETECT}, runtime {@code PYTORCH}, an empty closed class set, and a
     * fixed {@link Instant#EPOCH} {@code createdAt} sentinel — deterministic across restarts rather
     * than "whenever this bean happens to run."
     */
    @Bean
    public ConfigModelCatalog configModelCatalog(List<CvModelResponse> cvModelRoster) {
        List<CvModelRecord> records = cvModelRoster.stream()
                .map(model -> new CvModelRecord(model.id(), "latest", model.displayName(), model.kind(),
                        model.openVocab(), model.defaultLabelFilter(), ModelTaskType.DETECT, ModelRuntime.PYTORCH,
                        List.of(), ModelStatus.DRAFT, null, ModelProvenance.none(), null, null, Instant.EPOCH))
                .toList();
        return new ConfigModelCatalog(records);
    }

    /**
     * The system-derived half of the two-gate detection demand model (docs/plans/done/CV-DEMAND-PLAN.md
     * &sect;2-3.5) — present only when {@link VisionCvProperties.Demand#enabled()} is {@code true}
     * (the default). {@code false} means this bean is never created at all, so {@code
     * ApplicationServiceWiring#streamService}'s {@code ObjectProvider<DetectionDemandPort>} resolves
     * to nothing and {@code DefaultStreamService} never schedules its demand-poll task — every
     * stream stays fail-open on demand, exactly as before this plan existed.
     *
     * <p>Returns the <b>concrete</b> type, not {@link com.drones.vision.perception.domain.port.DetectionDemandPort}:
     * {@link #streamDetectionSupport} needs the concrete class to reach {@link
     * LiveAndPollDetectionDemand#touched}, and {@code ApplicationServiceWiring#streamService}'s
     * {@code ObjectProvider<DetectionDemandPort>} still resolves this same singleton by
     * assignability — there is only ever one bean of this type, so unlike the five {@code
     * LiveUpdateRegistry} selector beans in {@code ApplicationServiceWiring} (see that class's own
     * javadoc), no {@code @Qualifier} is needed on either consumer.
     *
     * <p>{@code watchingDetections} is a {@link Predicate}, resolved once here from {@link
     * LiveUpdateRegistry#watchingDetections(AssetId)} — {@code null} when {@code
     * VisionLiveProperties#enabled()} is {@code false} too (the SSE registry bean itself is
     * conditionally absent), in which case this deployment's demand can only ever come from the
     * poll half.
     *
     * <p>{@code hasCameraPose} is the D9 third OR-term (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md,
     * hazard 2) — resolved from {@link TrackProjectionRunner#hasCameraPose}, itself an {@link
     * ObjectProvider} because that bean is conditional on {@code vision.geo.fixed-camera.enabled}
     * (default {@code false}, see {@code FixedCameraGeoWiringConfiguration}). Absent means {@code
     * assetId -> false}, contributing nothing to the OR — a deployment with the geo feature off (or
     * not yet on this build) sees byte-identical demand behaviour to before this OR-term existed.
     *
     * <p><b>{@code trackProjectionRunner.getIfAvailable()} is deliberately called inside the
     * predicate, not once here at bean-creation time</b> — unlike {@code liveUpdateRegistry} above.
     * {@code TrackProjectionRunner}'s own constructor takes {@code AssetService}, and this bean sits
     * on {@code AssetService}'s own (indirect) construction path via {@code
     * ApplicationServiceWiring#streamService}; resolving the {@code ObjectProvider} eagerly here
     * would force Spring to construct {@code TrackProjectionRunner} — and therefore {@code
     * AssetService} — while {@code AssetService} is still being constructed, an unresolvable circular
     * reference (caught by {@code FixedCameraGeoEnabledWiringTest} the first time this wave enabled
     * the flag in a real context — {@code ObjectProvider}-typed constructor parameters elsewhere in
     * this class never trip this because none of their beans depend back on {@code AssetService}).
     * Deferring the lookup into the predicate costs nothing at call time (every poll tick already
     * calls through an {@code ObjectProvider} for {@code detectionDemandPort} itself, see {@link
     * #streamDetectionSupport}) and means the runner is only ever resolved once the whole context —
     * {@code AssetService} included — has finished starting.
     */
    @Bean
    @ConditionalOnExpression("${vision.cv.demand.enabled:true}")
    public LiveAndPollDetectionDemand detectionDemandPort(VisionCvProperties cvProperties,
            @Qualifier("liveUpdateRegistry") ObjectProvider<LiveUpdateRegistry> liveUpdateRegistry,
            ObjectProvider<TrackProjectionRunner> trackProjectionRunner) {
        LiveUpdateRegistry registry = liveUpdateRegistry.getIfAvailable();
        Predicate<AssetId> watchingDetections = registry == null ? assetId -> false : registry::watchingDetections;
        Predicate<AssetId> hasCameraPose = assetId -> {
            TrackProjectionRunner runner = trackProjectionRunner.getIfAvailable();
            return runner != null && runner.hasCameraPose(assetId);
        };
        return new LiveAndPollDetectionDemand(watchingDetections, cvProperties.demand().pollTtl(), hasCameraPose);
    }

    /**
     * The concrete {@link TraceDemandPort} adapter (docs/plans/active/CV-ORCHESTRATION-PLAN.md
     * §4.4, wave W2) — {@link TraceDemandPort}'s own javadoc names this bean as its "later
     * station/vision-api step". Gated on the same {@code vision.cv.demand.enabled} flag as {@link
     * #detectionDemandPort}, not a dedicated property: an absent bean here reproduces {@link
     * TraceDemandPort}'s own documented "fail open, structurally" fallback ({@code
     * DefaultStreamService} simply never evaluates trace demand at all, so {@code
     * PipelineConfig#trace()} stays at whatever the stream started with), the same posture the
     * demand flag already gives {@link #detectionDemandPort}.
     *
     * <p>Reuses {@link VisionCvProperties.Demand#pollTtl()} rather than a dedicated {@code
     * vision.cv.trace.poll-ttl} property — a deliberate simplification (not asked for by the plan's
     * own frozen contract), since the two poll TTLs answer the same question ("how long does one
     * read count as demand") for two siblings of the same demand model; splitting the knob can
     * follow later if trace's usage pattern ever needs a different value.
     */
    @Bean
    @ConditionalOnExpression("${vision.cv.demand.enabled:true}")
    public LiveAndPollTraceDemand traceDemandPort(VisionCvProperties cvProperties,
            @Qualifier("liveUpdateRegistry") ObjectProvider<LiveUpdateRegistry> liveUpdateRegistry) {
        LiveUpdateRegistry registry = liveUpdateRegistry.getIfAvailable();
        Predicate<AssetId> watchingTrace = registry == null ? assetId -> false : registry::watchingTrace;
        return new LiveAndPollTraceDemand(watchingTrace, cvProperties.demand().pollTtl());
    }

    /**
     * D1's self-scheduled per-asset {@code DetectionPolicy} cache (docs/plans/active/ALWAYS-ON-FLOW-PLAN.md
     * wave D1) — see {@link DetectionPolicyCache}'s own javadoc for the staleness/fail-closed
     * contract. No dedicated {@code vision.cv.policy.enabled} escape hatch: this bean's own existence
     * is instead gated on the same "is CV switched on at all in this deployment" expression {@link
     * #cvChannelSupervisor} uses (a per-asset {@code DetectionPolicy} attribute is meaningless when
     * every stream is wired to {@code NoopDetectionPort} anyway), rather than a second flag whose only
     * job would be re-stating that same condition.
     *
     * <p><b>Found empirically, not by design</b>: an earlier, unconditional version of this bean
     * spun up one {@link DetectionPolicyCache} background thread (each polling {@link AssetService}
     * every {@link VisionCvProperties.Policy#refreshInterval()}) per Spring test context — and
     * Spring's test context cache keeps many contexts, and therefore many such threads, alive
     * simultaneously across a whole surefire run. That aggregate load intermittently starved {@code
     * TrackingAssociateE2ETest}'s tight detector-pass timing window (passed in isolation, failed
     * under the full {@code vision-app} suite) even though {@code TrackingAssociateE2ETest} itself
     * never touches {@code DetectionPolicy}. Gating this bean the same way {@link
     * #cvChannelSupervisor} already is removes it from the (large majority of) test contexts that
     * leave {@link VisionCvProperties#enabled()} at its {@code false} default, exactly as that bean's
     * own precedent already does for its own background thread.
     *
     * <p>Takes {@link AssetService} directly, not deferred behind an {@link ObjectProvider} — unlike
     * {@link #detectionPolicyPort} below. This bean does not sit on {@code AssetService}'s own
     * construction path (nothing upstream of {@code AssetService} needs a {@code
     * DetectionPolicyCache}), so there is no circular-construction hazard here; the hazard only
     * exists on the <em>consuming</em> side, exactly as {@link #detectionDemandPort}'s own {@code
     * trackProjectionRunner} parameter documents for {@code TrackProjectionRunner}.
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnExpression("${vision.cv.enabled:false} or ${vision.training.enabled:false} "
            + "or '${vision.cv.frame-transport:push}' == 'pull' or ${vision.geo.visual.enabled:false} "
            + "or ${vision.cv.registry.enabled:false}")
    public DetectionPolicyCache detectionPolicyCache(AssetService assetService, VisionCvProperties cvProperties) {
        return new DetectionPolicyCache(assetService, cvProperties.policy().refreshInterval());
    }

    /**
     * Resolves {@link StreamPipeline#updateDetectionPolicy}'s per-tick input from {@link
     * DetectionPolicyCache}, wired as a wholly separate port from {@link #detectionDemandPort} rather
     * than folded into {@code LiveAndPollDetectionDemand} as a fourth OR-term — see {@link
     * DetectionPolicyPort}'s own javadoc for why: {@code StreamPipeline}'s D2 live/inference split
     * needs a policy signal that widens only the inference+durable gate, never the live gate, and
     * {@code LiveAndPollDetectionDemand}'s single {@code detectionWanted} boolean feeds live directly
     * — merging the two would make an {@code ALWAYS} asset's live gate track policy too, exactly the
     * regression docs/plans/active/ALWAYS-ON-FLOW-PLAN.md §4 warns against.
     *
     * <p>{@code detectionPolicyCache.getIfAvailable()} is deliberately called inside the lambda, not
     * once here at bean-creation time — this bean sits on {@code AssetService}'s own (indirect)
     * construction path via {@code ApplicationServiceWiring#streamService}, same as {@link
     * #detectionDemandPort}'s {@code trackProjectionRunner} parameter; eager resolution here would
     * force {@code AssetService} to construct itself, the same {@code
     * BeanCurrentlyInCreationException} that parameter's javadoc explains.
     */
    @Bean
    public DetectionPolicyPort detectionPolicyPort(ObjectProvider<DetectionPolicyCache> detectionPolicyCache) {
        return assetId -> {
            DetectionPolicyCache cache = detectionPolicyCache.getIfAvailable();
            return cache != null && cache.alwaysOn(assetId);
        };
    }

    /**
     * The deployment's default {@link PipelineConfig} for a newly started stream
     * (docs/plans/done/CV-DEMAND-PLAN.md &sect;3.7/&sect;3.8) — {@link PipelineConfig#defaults()}
     * with {@code detectionEnabled} replaced by {@link VisionCvProperties#detectionDefaultEnabled()}
     * (default {@code false}). {@code StreamController}/{@code AssetStreamController}/{@code
     * DemoFleet} merge request overrides onto this instead of calling the static factory directly,
     * so this one property key reaches every start path.
     */
    @Bean
    public PipelineConfig streamDefaultConfig(VisionCvProperties cvProperties) {
        PipelineConfig defaults = PipelineConfig.defaults();
        return new PipelineConfig(defaults.model(), defaults.confidenceThreshold(), defaults.inferenceFps(),
                defaults.maxInFlightInferences(), defaults.labelFilter(), defaults.eventRule(),
                cvProperties.detectionDefaultEnabled(), defaults.tracking(), defaults.labelDenyFilter(),
                defaults.trace());
    }

    /**
     * Bundles {@link #streamDefaultConfig} and {@link #detectionDemandPort}'s poll-touch seam, plus
     * (docs/plans/active/CV-SETTINGS-PLAN.md §5.4, CV-SETTINGS-CONTEXT.md's W2 → W5 handoff) the
     * profile-resolution collaborators {@code StreamController#start} needs to fold a bound {@code
     * CvProfile} under an explicit request override, behind one bean for {@code StreamController}
     * (docs/plans/done/CV-DEMAND-PLAN.md &sect;3.8) — see {@link StreamDetectionSupport}'s own
     * javadoc for why. {@code detectionDemandPort} resolves to {@code null} exactly when {@link
     * #detectionDemandPort} itself was not created (demand gate disabled), which {@link
     * StreamDetectionSupport} already treats as "nothing to touch." {@code cvProfileService} is
     * unconditional (see {@code CvProfileWiringConfiguration}) — profiles ship regardless of {@link
     * VisionCvProperties#enabled()}/{@link VisionCvProperties.Registry#enabled()}.
     */
    @Bean
    public StreamDetectionSupport streamDetectionSupport(PipelineConfig streamDefaultConfig,
            ObjectProvider<LiveAndPollDetectionDemand> detectionDemandPort, CvProfileService cvProfileService,
            AssetRepositoryPort assetRepositoryPort, CurrentUser currentUser,
            ObjectProvider<LiveAndPollTraceDemand> traceDemandPort) {
        return new StreamDetectionSupport(streamDefaultConfig, detectionDemandPort.getIfAvailable(),
                cvProfileService, assetRepositoryPort, currentUser, traceDemandPort.getIfAvailable());
    }
}
