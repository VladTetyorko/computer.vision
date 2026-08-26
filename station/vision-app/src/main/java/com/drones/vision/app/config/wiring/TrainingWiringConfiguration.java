package com.drones.vision.app.config.wiring;

import com.drones.vision.perception.domain.port.DetectionRepositoryPort;
import com.drones.vision.events.domain.port.ReplayFrameExtractionPort;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.learning.domain.port.DatasetRepositoryPort;
import com.drones.vision.learning.domain.port.DatasetUploadPort;
import com.drones.vision.learning.domain.port.ModelRegistryPort;
import com.drones.vision.learning.domain.port.SampleImageStorePort;
import com.drones.vision.learning.domain.port.TrainingPort;
import com.drones.vision.learning.domain.port.TrainingSampleRepositoryPort;
import com.drones.vision.warehouse.application.directory.AssetDirectoryService;
import com.drones.vision.adapter.cvgrpc.GrpcCvSettings;
import com.drones.vision.adapter.cvgrpc.GrpcDatasetUploadPort;
import com.drones.vision.adapter.cvgrpc.GrpcModelRegistryPort;
import com.drones.vision.adapter.cvgrpc.GrpcTrainingPort;
import com.drones.vision.app.config.properties.VisionApplicationProperties;
import com.drones.vision.app.config.properties.VisionCvProperties;
import com.drones.vision.app.config.properties.VisionTrainingProperties;
import com.drones.vision.events.application.*;
import com.drones.vision.perception.application.stream.*;
import com.drones.vision.learning.application.*;

import io.grpc.ManagedChannel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the CV model-improvement training loop (docs/plans/done/CV-TRAINING-PLAN.md §3, Wave T4, as delta'd by
 * docs/plans/done/CV-TRAINING-V2-PLAN.md §7) — {@link DatasetService}/{@link LabelingService} plus their
 * gRPC-backed {@link DatasetUploadPort} (the replacement for the deleted filesystem export step) —
 * behind {@link VisionTrainingProperties#enabled()} (default {@code false}).
 *
 * <p>Also wires the model registry control plane (docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2 T9):
 * {@link #modelRegistryPort}/{@link #modelRegistryService} behind {@code ModelRegistryController}
 * (vision-api, component-scanned), gated by the same {@link VisionTrainingProperties#enabled()}
 * property.
 *
 * <p><b>Channel routing (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R6)</b>: {@code
 * Training/*} RPCs belong to the training role, so {@link #datasetUploadPort}/{@link
 * #modelRegistryPort}/{@link #trainingPort} all resolve their channel through {@code
 * CvWiring#controlPlaneChannel} — {@code cvTrainingChannel} when {@code vision.cv.training.target}
 * is set (a split deployment), else the same shared {@code cvGrpcChannel} {@link
 * com.drones.vision.adapter.cvgrpc.GrpcDetectionPort} uses, byte-identical to before this routing
 * existed. Each takes two {@code @Qualifier}-annotated {@code ObjectProvider<ManagedChannel>}
 * parameters rather than a plain {@code ManagedChannel cvGrpcChannel}: once {@code
 * CvWiring#cvGrpcChannel} is marked {@code @Primary} (needed so every <em>other</em> unqualified
 * injection point keeps compiling), a plain parameter named {@code cvGrpcChannel} would silently
 * keep resolving the primary bean regardless of its name — routing to the training channel needs an
 * explicit qualifier, not name-based autowiring.
 *
 * <p>{@code
 * ModelRegistryPort}'s history: dormant since docs/plans/done/CV-CONTROL-PLAN.md §D noted "not the dormant
 * {@code ModelRegistryPort}" for the detection-model roster ({@code CvModelsController}'s static
 * config-backed picker) — this task is the port's first real wiring.
 *
 * <p><strong>Training-job flow (docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2, last backend wave; upload
 * folded in by docs/plans/done/CV-TRAINING-V2-PLAN.md §4)</strong>: {@link #trainingPort}/{@link
 * #trainingJobService} behind {@code TrainingJobController} (vision-api, component-scanned) —
 * {@code POST /api/datasets/{id}/train} and {@code GET /api/training/jobs}[/{jobId}]. {@link
 * #trainingPort} shares {@link #modelRegistryPort}'s exact same {@link ManagedChannel} bean (see
 * that bean's own javadoc, "Channel reuse", for why); {@link #trainingJobService} now also takes
 * {@link #labelingService} as a collaborator — {@code DefaultTrainingJobService#start}'s
 * synchronous pre-check and {@code runJob}'s upload-then-train sequence both call back into it (see
 * that class's own javadoc). Its blocking {@code TrainingPort#startTraining} call still runs on
 * {@link DefaultTrainingJobService}'s own internal executor, never a request thread, so nothing
 * extra is configured here for that.
 *
 * <p>Split into its own {@code @Configuration} class rather than added to {@code
 * ApplicationServiceWiring} — same "split out by concern" precedent as {@link
 * DiscoveryWiringConfiguration}/{@link PersistenceWiringConfiguration} — because, unlike every port
 * {@link PersistenceWiringConfiguration} wires, none of the beans below has a fallback to select
 * between when disabled: they simply don't exist at all, the same "absent entirely" posture {@code
 * LiveController} takes for its own property, applied here to a whole small cluster of beans
 * instead of one controller. Every bean is therefore individually {@code
 * @ConditionalOnProperty}-gated — a shape {@link PersistenceWiringConfiguration} itself no longer
 * has any of, since docs/plans/done/POSTGRES-ONLY-CONTEXT.md W2b made every one of its beans
 * unconditional.
 *
 * <p>{@link DatasetRepositoryPort}/{@link TrainingSampleRepositoryPort}/{@link
 * SampleImageStorePort} are already unconditionally wired in {@link PersistenceWiringConfiguration}
 * (docs/plans/done/CV-TRAINING-PLAN.md Wave T3) — so the beans below just consume them as already-resolved
 * collaborators, same as {@code ApplicationServiceWiring#markService} consumes {@code
 * markRepositoryPort}. {@link #replaySources} does the same for {@link AssetUsageRepositoryPort}/
 * {@link DetectionRepositoryPort} — both are already unconditionally wired in {@code
 * ApplicationServiceWiring}/{@link PersistenceWiringConfiguration} (usage/history-tracking already
 * shipped in the product before this loop existed), so this bean is a one-line bundle over
 * already-resolved collaborators, not a new wiring decision — only {@code
 * PublishWiring#replayFrameExtractionPort} is genuinely new, feature-flagged (behind {@code
 * vision.publish.enabled}) infrastructure.
 */
@Configuration
@EnableConfigurationProperties({VisionTrainingProperties.class, VisionCvProperties.class})
public class TrainingWiringConfiguration {

    /**
     * Ships a composed YOLO dataset to cv-service over the training/control-plane channel
     * (docs/plans/done/CV-TRAINING-V2-PLAN.md §3/§6) — the replacement for the deleted {@code
     * FilesystemDatasetExport} bean; the same channel {@link #modelRegistryPort}/{@link
     * #trainingPort} resolve via {@code CvWiring#controlPlaneChannel} (see class javadoc). {@code
     * settings} maps {@link VisionCvProperties#upload()} (plus every other {@code GrpcCvSettings}
     * field) onto {@code GrpcCvSettings} (docs/plans/active/LAYERING-REFACTOR-PLAN.md wave F4) — the
     * same mapping {@code CvWiring#detectionPort} performs, duplicated here since this bean lives in
     * a separate {@code @Configuration} class with no shared private helper.
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.training", name = "enabled", havingValue = "true")
    public DatasetUploadPort datasetUploadPort(@Qualifier("cvTrainingChannel") ObjectProvider<ManagedChannel> cvTrainingChannel,
                                                @Qualifier("cvGrpcChannel") ObjectProvider<ManagedChannel> cvGrpcChannel,
                                                VisionCvProperties cvProperties) {
        ManagedChannel channel = CvWiring.controlPlaneChannel(cvTrainingChannel, cvGrpcChannel);
        return new GrpcDatasetUploadPort(channel, CvWiring.toGrpcCvSettings(cvProperties));
    }

    /**
     * The four Wave-T1 training ports bundled for {@link #labelingService} — see {@link
     * TrainingStores}'s own javadoc for why they're grouped into one record rather than four
     * separate constructor parameters. A one-line assembly over already-wired collaborators.
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.training", name = "enabled", havingValue = "true")
    public TrainingStores trainingStores(DatasetRepositoryPort datasetRepositoryPort,
                                          TrainingSampleRepositoryPort trainingSampleRepositoryPort,
                                          SampleImageStorePort sampleImageStorePort,
                                          DatasetUploadPort datasetUploadPort) {
        return new TrainingStores(datasetRepositoryPort, trainingSampleRepositoryPort, sampleImageStorePort,
                datasetUploadPort);
    }

    /**
     * The three replay-sourced collaborators {@link #labelingService}'s {@code captureFromReplay}
     * path needs, bundled for the same five-parameter-ceiling reason {@link #trainingStores}'s own
     * javadoc gives — see {@link ReplaySources}'s own javadoc. {@code assetUsageRepositoryPort}/
     * {@code detectionRepositoryPort} are already unconditionally-wired beans (usage tracking and
     * detection history both ship regardless of this flag); {@code replayFrameExtractionPort} is
     * {@code PublishWiring#replayFrameExtractionPort} — real when {@code
     * vision.publish.enabled}, a no-op otherwise.
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.training", name = "enabled", havingValue = "true")
    public ReplaySources replaySources(AssetUsageRepositoryPort assetUsageRepositoryPort,
                                        DetectionRepositoryPort detectionRepositoryPort,
                                        ReplayFrameExtractionPort replayFrameExtractionPort) {
        return new ReplaySources(assetUsageRepositoryPort, detectionRepositoryPort, replayFrameExtractionPort);
    }

    /**
     * Dataset CRUD (docs/plans/done/CV-TRAINING-PLAN.md §2) behind {@code DatasetController} (vision-api,
     * component-scanned) — a one-line assembly, mirroring {@code
     * ApplicationServiceWiring#geofenceService}'s shape.
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.training", name = "enabled", havingValue = "true")
    public DatasetService datasetService(DatasetRepositoryPort datasetRepositoryPort, AuditTrailPort auditTrailPort) {
        return new DefaultDatasetService(datasetRepositoryPort, auditTrailPort);
    }

    /**
     * Capture/label/upload (docs/plans/done/CV-TRAINING-PLAN.md §2, as delta'd by docs/plans/done/CV-TRAINING-V2-PLAN.md
     * §4) behind {@code LabelingController} (vision-api, component-scanned). {@code
     * streamService}/{@code assetDirectoryService} resolve a live capture's source stream/asset —
     * {@link AssetDirectoryService} rather than warehouse's raw {@code AssetRepositoryPort} since
     * docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R5; {@code replaySources} resolves a replay
     * capture's usage/detections/frame (see {@code DefaultLabelingService}'s own javadoc for both).
     * All four are already-wired, unconditional beans in {@code ApplicationServiceWiring}/{@link
     * PersistenceWiringConfiguration}, or {@link #replaySources} above.
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.training", name = "enabled", havingValue = "true")
    public LabelingService labelingService(TrainingStores trainingStores, ReplaySources replaySources,
                                            StreamService streamService, AssetDirectoryService assetDirectoryService,
                                            AuditTrailPort auditTrailPort,
                                            VisionApplicationProperties applicationProperties) {
        return new DefaultLabelingService(trainingStores, replaySources, streamService, assetDirectoryService,
                auditTrailPort, applicationProperties.training().jpegQuality());
    }

    /**
     * The CV model registry's gRPC client (docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2 T9) —
     * {@code Training/ListModels}/{@code Training/PromoteModel} over {@code CvWiring#controlPlaneChannel}
     * (see class javadoc) — the training channel when split, else the same channel {@code
     * GrpcDetectionPort} uses for {@code Inference/DetectStream} when {@code vision.cv.enabled=true}
     * too (see that bean's own javadoc, "Shutdown ownership", for why this class never closes it).
     * Behind {@code ModelRegistryController} (vision-api, component-scanned). {@code
     * cvProperties.registry().callTimeout()} (docs/plans/active/LAYERING-REFACTOR-PLAN.md wave F4,
     * {@code vision.cv.registry.call-timeout}) replaces {@code GrpcModelRegistryPort.CALL_TIMEOUT_SECONDS}
     * as the actual per-call deadline.
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.training", name = "enabled", havingValue = "true")
    public ModelRegistryPort modelRegistryPort(@Qualifier("cvTrainingChannel") ObjectProvider<ManagedChannel> cvTrainingChannel,
                                                @Qualifier("cvGrpcChannel") ObjectProvider<ManagedChannel> cvGrpcChannel,
                                                VisionCvProperties cvProperties) {
        ManagedChannel channel = CvWiring.controlPlaneChannel(cvTrainingChannel, cvGrpcChannel);
        return new GrpcModelRegistryPort(channel, cvProperties.registry().callTimeout());
    }

    /**
     * Model list/promote (docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2 T9) behind {@code
     * ModelRegistryController} (vision-api, component-scanned) — a one-line assembly, mirroring
     * {@link #datasetService}'s shape.
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.training", name = "enabled", havingValue = "true")
    public ModelRegistryService modelRegistryService(ModelRegistryPort modelRegistryPort,
                                                       AuditTrailPort auditTrailPort) {
        return new DefaultModelRegistryService(modelRegistryPort, auditTrailPort);
    }

    /**
     * The training-run gRPC client (docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2 — the last backend
     * wave) — {@code Training/StartTraining} over {@code CvWiring#controlPlaneChannel} (see class
     * javadoc), the <em>same</em> channel {@link #modelRegistryPort} and {@code GrpcDetectionPort}
     * resolve too (see {@link GrpcModelRegistryPort}'s own javadoc, "Channel reuse"). Behind {@code
     * TrainingJobController} (vision-api, component-scanned) via {@link #trainingJobService} below.
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.training", name = "enabled", havingValue = "true")
    public TrainingPort trainingPort(@Qualifier("cvTrainingChannel") ObjectProvider<ManagedChannel> cvTrainingChannel,
                                      @Qualifier("cvGrpcChannel") ObjectProvider<ManagedChannel> cvGrpcChannel) {
        return new GrpcTrainingPort(CvWiring.controlPlaneChannel(cvTrainingChannel, cvGrpcChannel));
    }

    /**
     * Starts fine-tune jobs and holds their pollable state (docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase
     * 2, upload-then-train folded in by docs/plans/done/CV-TRAINING-V2-PLAN.md §4) behind {@code
     * TrainingJobController} (vision-api, component-scanned). {@code labelingService} backs both
     * {@code start}'s synchronous "does this dataset have LABELED samples" pre-check and {@code
     * runJob}'s upload phase — see {@code DefaultTrainingJobService}'s own javadoc.
     * {@code DefaultTrainingJobService}'s own production constructor submits each run to its own
     * internal cached daemon-thread executor, so {@link TrainingPort#startTraining}'s blocking,
     * potentially many-epoch call never holds a request thread — nothing extra to wire here for
     * that.
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.training", name = "enabled", havingValue = "true")
    public TrainingJobService trainingJobService(TrainingPort trainingPort, LabelingService labelingService,
                                                   AuditTrailPort auditTrailPort,
                                                   VisionApplicationProperties applicationProperties) {
        return new DefaultTrainingJobService(trainingPort, labelingService, auditTrailPort,
                applicationProperties.training().maxFinishedJobs());
    }
}
