package com.drones.vision.app.config.wiring;

import com.drones.vision.adapter.cvgrpc.CvChannelSupervisor;
import com.drones.vision.adapter.cvgrpc.GrpcCvSettings;
import com.drones.vision.adapter.cvgrpc.WireFormat;
import com.drones.vision.adapter.cvgrpc.GrpcDetectionPort;
import com.drones.vision.adapter.cvgrpc.GrpcPulledDetectionPort;
import com.drones.vision.api.dto.CvModelResponse;
import com.drones.vision.api.live.LiveAndPollDetectionDemand;
import com.drones.vision.api.live.LiveUpdateRegistry;
import com.drones.vision.api.support.StreamDetectionSupport;
import com.drones.vision.app.config.properties.VisionCvProperties;
import com.drones.vision.app.devsupport.NoopDetectionPort;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.perception.domain.port.DetectionPort;
import com.drones.vision.perception.domain.port.PulledDetectionPort;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;
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
     * <em>and</em> training port), <strong>or</strong> {@link VisionCvProperties#pullEnabled()}
     * (docs/plans/active/MEDIA-SOT-PLAN.md wave M7, switch B — {@link #pulledDetectionPort} needs the
     * same channel {@code DetectPulled} rides on). With every flag off (the default), no channel is
     * built at all.
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
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnExpression("${vision.cv.enabled:false} or ${vision.training.enabled:false} "
            + "or '${vision.cv.frame-transport:push}' == 'pull'")
    public ManagedChannel cvGrpcChannel(VisionCvProperties cvProperties) {
        GrpcCvSettings settings = toGrpcCvSettings(cvProperties);
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress(cvProperties.host(), cvProperties.port());
        if (settings.plaintext()) {
            builder.usePlaintext();
        }
        return builder
                .keepAliveTime(settings.keepAliveTime().toMillis(), TimeUnit.MILLISECONDS)
                .keepAliveTimeout(settings.keepAliveTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .keepAliveWithoutCalls(settings.keepAliveWithoutCalls())
                .build();
    }

    /**
     * Maps every {@code GrpcCvSettings} field off {@link VisionCvProperties} (docs/plans/active/LAYERING-REFACTOR-PLAN.md
     * wave F4, extended by docs/plans/active/MEDIA-SOT-PLAN.md wave M7 with the three {@code pull*}
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
            + "or '${vision.cv.frame-transport:push}' == 'pull') and ${vision.cv.reconnect.enabled:true}")
    public CvChannelSupervisor cvChannelSupervisor(VisionCvProperties cvProperties,
                                                    ObjectProvider<ManagedChannel> cvGrpcChannel) {
        return new CvChannelSupervisor(cvGrpcChannel.getObject(), toGrpcCvSettings(cvProperties));
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
    public DetectionPort detectionPort(VisionCvProperties cvProperties, ObjectProvider<ManagedChannel> cvGrpcChannel,
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
     * Present only when {@link VisionCvProperties#pullEnabled()} — switch B, docs/plans/active/MEDIA-SOT-PLAN.md
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
                                                    ObjectProvider<ManagedChannel> cvGrpcChannel) {
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
                new CvModelResponse("yolo26n.pt", "General (people & vehicles, fast)", "general", false, List.of()),
                new CvModelResponse("orion12l.pt", "Military vehicles", "specialized", false, List.of()),
                new CvModelResponse("yoloe-26s-seg-pf.pt", "Everything (incl. buildings, slower)", "open-vocab",
                        true, List.of()));
    }

    /**
     * The system-derived half of the two-gate detection demand model (docs/plans/active/CV-DEMAND-PLAN.md
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
     */
    @Bean
    @ConditionalOnExpression("${vision.cv.demand.enabled:true}")
    public LiveAndPollDetectionDemand detectionDemandPort(VisionCvProperties cvProperties,
            @Qualifier("liveUpdateRegistry") ObjectProvider<LiveUpdateRegistry> liveUpdateRegistry) {
        LiveUpdateRegistry registry = liveUpdateRegistry.getIfAvailable();
        Predicate<AssetId> watchingDetections = registry == null ? assetId -> false : registry::watchingDetections;
        return new LiveAndPollDetectionDemand(watchingDetections, cvProperties.demand().pollTtl());
    }

    /**
     * The deployment's default {@link PipelineConfig} for a newly started stream
     * (docs/plans/active/CV-DEMAND-PLAN.md &sect;3.7/&sect;3.8) — {@link PipelineConfig#defaults()}
     * with {@code detectionEnabled} replaced by {@link VisionCvProperties#detectionDefaultEnabled()}
     * (default {@code false}). {@code StreamController}/{@code AssetStreamController}/{@code
     * DemoFleet} merge request overrides onto this instead of calling the static factory directly,
     * so this one property key reaches every start path.
     */
    @Bean
    public PipelineConfig streamDefaultConfig(VisionCvProperties cvProperties) {
        PipelineConfig defaults = PipelineConfig.defaults();
        return new PipelineConfig(defaults.model(), defaults.confidenceThreshold(), defaults.inferenceFps(),
                defaults.maxInFlightInferences(), defaults.overlayTelemetry(), defaults.labelFilter(),
                defaults.eventRule(), defaults.overlayBurnIn(), cvProperties.detectionDefaultEnabled(),
                defaults.tracking());
    }

    /**
     * Bundles {@link #streamDefaultConfig} and {@link #detectionDemandPort}'s poll-touch seam behind
     * one bean for {@code StreamController} (docs/plans/active/CV-DEMAND-PLAN.md &sect;3.8) — see
     * {@link StreamDetectionSupport}'s own javadoc for why. {@code detectionDemandPort} resolves to
     * {@code null} exactly when {@link #detectionDemandPort} itself was not created (demand gate
     * disabled), which {@link StreamDetectionSupport} already treats as "nothing to touch."
     */
    @Bean
    public StreamDetectionSupport streamDetectionSupport(PipelineConfig streamDefaultConfig,
            ObjectProvider<LiveAndPollDetectionDemand> detectionDemandPort) {
        return new StreamDetectionSupport(streamDefaultConfig, detectionDemandPort.getIfAvailable());
    }
}
