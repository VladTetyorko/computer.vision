package com.drones.vision.app.config.wiring;

import com.drones.vision.adapter.cvgrpc.GrpcCvSettings;
import com.drones.vision.adapter.cvgrpc.GrpcDatasetUploadPort;
import com.drones.vision.adapter.cvgrpc.GrpcModelRegistryPort;
import com.drones.vision.adapter.cvgrpc.GrpcTrainingPort;
import com.drones.vision.app.config.properties.VisionApplicationProperties;
import com.drones.vision.app.config.properties.VisionCvProperties;
import com.drones.vision.app.config.properties.VisionTrainingProperties;
import com.drones.vision.application.replay.*;
import com.drones.vision.application.stream.*;
import com.drones.vision.application.training.*;
import com.drones.vision.domain.port.out.*;
import io.grpc.ManagedChannel;
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
 * property. {@link #modelRegistryPort} consumes {@code CvWiring}'s shared {@link
 * ManagedChannel} bean directly (not via {@link org.springframework.beans.factory.ObjectProvider})
 * — safe because that channel bean's own {@code @ConditionalOnExpression} matches whenever this
 * property is {@code true}, the exact same guarantee that lets {@link #trainingStores} below take
 * its port arguments as plain, unconditional parameters.
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
 * DiscoveryWiringConfiguration}/{@link PersistenceWiringConfiguration} — because, unlike most ports
 * in this codebase, none of the beans below has a no-op/in-memory fallback to select between when
 * disabled: they simply don't exist at all, the same "absent entirely" posture {@code
 * LiveController} takes for its own property, applied here to a whole small cluster of beans
 * instead of one controller. Every bean is therefore individually {@code
 * @ConditionalOnProperty}-gated (mirroring {@link
 * PersistenceWiringConfiguration#persistenceEntityManagerFactory}'s own precedent for a bean with
 * no fallback), rather than that class's usual real-impl-vs-in-memory-fallback {@code if/else}
 * shape.
 *
 * <p>{@link DatasetRepositoryPort}/{@link TrainingSampleRepositoryPort}/{@link
 * SampleImageStorePort} are already unconditionally wired in {@link PersistenceWiringConfiguration}
 * (docs/plans/done/CV-TRAINING-PLAN.md Wave T3) — real JPA or in-memory devsupport, selected independently by
 * {@code vision.persistence.enabled} — so the beans below just consume them as already-resolved
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
     * Ships a composed YOLO dataset to cv-service over the shared gRPC channel
     * (docs/plans/done/CV-TRAINING-V2-PLAN.md §3/§6) — the replacement for the deleted {@code
     * FilesystemDatasetExport} bean; the same channel {@link #modelRegistryPort}/{@link
     * #trainingPort} already reuse. {@code settings} maps {@link VisionCvProperties#upload()} (plus
     * every other {@code GrpcCvSettings} field) onto {@code GrpcCvSettings} (docs/plans/active/LAYERING-REFACTOR-PLAN.md
     * wave F4) — the same mapping {@code CvWiring#detectionPort} performs, duplicated here since
     * this bean lives in a separate {@code @Configuration} class with no shared private helper.
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.training", name = "enabled", havingValue = "true")
    public DatasetUploadPort datasetUploadPort(ManagedChannel cvGrpcChannel, VisionCvProperties cvProperties) {
        return new GrpcDatasetUploadPort(cvGrpcChannel, CvWiring.toGrpcCvSettings(cvProperties));
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
     * streamService}/{@code assetRepositoryPort} resolve a live capture's source stream/asset;
     * {@code replaySources} resolves a replay capture's usage/detections/frame (see {@code
     * DefaultLabelingService}'s own javadoc for both). All four are already-wired, unconditional
     * beans in {@code ApplicationServiceWiring}/{@link PersistenceWiringConfiguration}, or {@link
     * #replaySources} above.
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.training", name = "enabled", havingValue = "true")
    public LabelingService labelingService(TrainingStores trainingStores, ReplaySources replaySources,
                                            StreamService streamService, AssetRepositoryPort assetRepositoryPort,
                                            AuditTrailPort auditTrailPort,
                                            VisionApplicationProperties applicationProperties) {
        return new DefaultLabelingService(trainingStores, replaySources, streamService, assetRepositoryPort,
                auditTrailPort, applicationProperties.training().jpegQuality());
    }

    /**
     * The CV model registry's gRPC client (docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2 T9) —
     * {@code Training/ListModels}/{@code Training/PromoteModel} over {@code CvWiring#cvGrpcChannel},
     * the exact same channel {@code GrpcDetectionPort} uses for {@code
     * Inference/DetectStream} when {@code vision.cv.enabled=true} too (see that bean's own javadoc,
     * "Shutdown ownership", for why this class never closes it). Behind {@code
     * ModelRegistryController} (vision-api, component-scanned). {@code cvProperties.registry().callTimeout()}
     * (docs/plans/active/LAYERING-REFACTOR-PLAN.md wave F4, {@code vision.cv.registry.call-timeout}) replaces
     * {@code GrpcModelRegistryPort.CALL_TIMEOUT_SECONDS} as the actual per-call deadline.
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.training", name = "enabled", havingValue = "true")
    public ModelRegistryPort modelRegistryPort(ManagedChannel cvGrpcChannel, VisionCvProperties cvProperties) {
        return new GrpcModelRegistryPort(cvGrpcChannel, cvProperties.registry().callTimeout());
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
     * wave) — {@code Training/StartTraining} over {@link WiringConfiguration#cvGrpcChannel}, the
     * <em>same</em> shared channel {@link #modelRegistryPort} and {@code GrpcDetectionPort}
     * already use (see {@link GrpcModelRegistryPort}'s own javadoc, "Channel reuse"). Taking the
     * channel as a plain, unconditional parameter (not an {@link
     * org.springframework.beans.factory.ObjectProvider}) is safe for the same reason {@link
     * #modelRegistryPort} does: this bean's own {@code @ConditionalOnProperty} on {@code
     * vision.training.enabled} already guarantees {@code cvGrpcChannel}'s {@code
     * @ConditionalOnExpression} matches too. Behind {@code TrainingJobController} (vision-api,
     * component-scanned) via {@link #trainingJobService} below.
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.training", name = "enabled", havingValue = "true")
    public TrainingPort trainingPort(ManagedChannel cvGrpcChannel) {
        return new GrpcTrainingPort(cvGrpcChannel);
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
