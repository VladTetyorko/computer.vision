package com.drones.vision.app;

import com.drones.vision.adapter.cvgrpc.GrpcModelRegistryPort;
import com.drones.vision.adapter.cvgrpc.GrpcTrainingPort;
import com.drones.vision.adapter.persistence.FilesystemDatasetExport;
import com.drones.vision.application.DatasetService;
import com.drones.vision.application.DefaultDatasetService;
import com.drones.vision.application.DefaultLabelingService;
import com.drones.vision.application.DefaultModelRegistryService;
import com.drones.vision.application.DefaultTrainingJobService;
import com.drones.vision.application.LabelingService;
import com.drones.vision.application.ModelRegistryService;
import com.drones.vision.application.StreamService;
import com.drones.vision.application.TrainingJobService;
import com.drones.vision.application.TrainingStores;
import com.drones.vision.domain.port.out.AssetRepositoryPort;
import com.drones.vision.domain.port.out.AuditTrailPort;
import com.drones.vision.domain.port.out.DatasetExportPort;
import com.drones.vision.domain.port.out.DatasetRepositoryPort;
import com.drones.vision.domain.port.out.ModelRegistryPort;
import com.drones.vision.domain.port.out.SampleImageStorePort;
import com.drones.vision.domain.port.out.TrainingPort;
import com.drones.vision.domain.port.out.TrainingSampleRepositoryPort;
import io.grpc.ManagedChannel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Wires the CV model-improvement training loop (docs/CV-TRAINING-PLAN.md §3, Wave T4) — {@link
 * DatasetService}/{@link LabelingService} plus their {@code adapter-persistence}-backed {@link
 * DatasetExportPort} — behind {@link VisionTrainingProperties#enabled()} (default {@code false}).
 *
 * <p>Also wires the model registry control plane (docs/CV-TRAINING-PLAN.md §7/§8, Phase 2 T9):
 * {@link #modelRegistryPort}/{@link #modelRegistryService} behind {@code ModelRegistryController}
 * (vision-api, component-scanned), gated by the same {@link VisionTrainingProperties#enabled()}
 * property. {@link #modelRegistryPort} consumes {@link WiringConfiguration}'s shared {@link
 * ManagedChannel} bean directly (not via {@link org.springframework.beans.factory.ObjectProvider})
 * — safe because that channel bean's own {@code @ConditionalOnExpression} matches whenever this
 * property is {@code true}, the exact same guarantee that lets {@link #trainingStores} below take
 * its port arguments as plain, unconditional parameters.
 *
 * <p>{@code
 * ModelRegistryPort}'s history: dormant since docs/CV-CONTROL-PLAN.md §D noted "not the dormant
 * {@code ModelRegistryPort}" for the detection-model roster ({@code CvModelsController}'s static
 * config-backed picker) — this task is the port's first real wiring.
 *
 * <p><strong>Training-job flow (docs/CV-TRAINING-PLAN.md §7/§8, Phase 2, last backend wave)</strong>:
 * {@link #trainingPort}/{@link #trainingJobService} behind {@code TrainingJobController}
 * (vision-api, component-scanned) — {@code POST /api/datasets/{id}/train} and {@code GET
 * /api/training/jobs}[/{jobId}]. {@link #trainingPort} shares {@link #modelRegistryPort}'s exact
 * same {@link ManagedChannel} bean (see that bean's own javadoc, "Channel reuse", for why); {@link
 * #trainingJobService}'s blocking {@code TrainingPort#startTraining} call runs on {@link
 * DefaultTrainingJobService}'s own internal executor, never a request thread, so nothing extra is
 * configured here for that.
 *
 * <p>Split into its own {@code @Configuration} class rather than added to {@link
 * WiringConfiguration} — same "split out by concern" precedent as {@link
 * DiscoveryWiringConfiguration}/{@link PersistenceWiringConfiguration} — because, unlike most ports
 * in this codebase, none of the four beans below has a no-op/in-memory fallback to select between
 * when disabled: they simply don't exist at all, the same "absent entirely" posture {@code
 * LiveController} takes for its own property, applied here to a whole small cluster of beans
 * instead of one controller. Every bean is therefore individually {@code
 * @ConditionalOnProperty}-gated (mirroring {@link
 * PersistenceWiringConfiguration#persistenceEntityManagerFactory}'s own precedent for a bean with
 * no fallback), rather than that class's usual real-impl-vs-in-memory-fallback {@code if/else}
 * shape.
 *
 * <p>{@link DatasetRepositoryPort}/{@link TrainingSampleRepositoryPort}/{@link
 * SampleImageStorePort} are already unconditionally wired in {@link PersistenceWiringConfiguration}
 * (docs/CV-TRAINING-PLAN.md Wave T3) — real JPA or in-memory devsupport, selected independently by
 * {@code vision.persistence.enabled} — so the beans below just consume them as already-resolved
 * collaborators, same as {@link WiringConfiguration#markService} consumes {@code
 * markRepositoryPort}.
 */
@Configuration
@EnableConfigurationProperties(VisionTrainingProperties.class)
public class TrainingWiringConfiguration {

    /**
     * The filesystem sink for a completed dataset export's YOLO zip (docs/CV-TRAINING-PLAN.md §5),
     * rooted at {@link VisionTrainingProperties#exportDir()} — the plan's Open Questions §1
     * placement, {@code adapter-persistence} (the module that already owns every other "user data
     * storage" concern: bytea image bytes, jsonb columns, Flyway-migrated schema).
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.training", name = "enabled", havingValue = "true")
    public DatasetExportPort datasetExportPort(VisionTrainingProperties properties) {
        return new FilesystemDatasetExport(Path.of(properties.exportDir()));
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
                                          DatasetExportPort datasetExportPort) {
        return new TrainingStores(datasetRepositoryPort, trainingSampleRepositoryPort, sampleImageStorePort,
                datasetExportPort);
    }

    /**
     * Dataset CRUD (docs/CV-TRAINING-PLAN.md §2) behind {@code DatasetController} (vision-api,
     * component-scanned) — a one-line assembly, mirroring {@link
     * WiringConfiguration#geofenceService}'s shape.
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.training", name = "enabled", havingValue = "true")
    public DatasetService datasetService(DatasetRepositoryPort datasetRepositoryPort, AuditTrailPort auditTrailPort) {
        return new DefaultDatasetService(datasetRepositoryPort, auditTrailPort);
    }

    /**
     * Capture/label/export (docs/CV-TRAINING-PLAN.md §2) behind {@code LabelingController}
     * (vision-api, component-scanned). {@code streamService}/{@code assetRepositoryPort} resolve a
     * capture's source stream/asset (see {@code DefaultLabelingService}'s own javadoc); both are
     * already-wired, unconditional beans in {@link WiringConfiguration}/{@link
     * PersistenceWiringConfiguration}.
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.training", name = "enabled", havingValue = "true")
    public LabelingService labelingService(TrainingStores trainingStores, StreamService streamService,
                                            AssetRepositoryPort assetRepositoryPort, AuditTrailPort auditTrailPort) {
        return new DefaultLabelingService(trainingStores, streamService, assetRepositoryPort, auditTrailPort);
    }

    /**
     * The CV model registry's gRPC client (docs/CV-TRAINING-PLAN.md §7/§8, Phase 2 T9) —
     * {@code Training/ListModels}/{@code Training/PromoteModel} over {@link WiringConfiguration
     * #cvGrpcChannel}, the exact same channel {@code GrpcDetectionPort} uses for {@code
     * Inference/DetectStream} when {@code vision.cv.enabled=true} too (see that bean's own javadoc,
     * "Shutdown ownership", for why this class never closes it). Behind {@code
     * ModelRegistryController} (vision-api, component-scanned).
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.training", name = "enabled", havingValue = "true")
    public ModelRegistryPort modelRegistryPort(ManagedChannel cvGrpcChannel) {
        return new GrpcModelRegistryPort(cvGrpcChannel);
    }

    /**
     * Model list/promote (docs/CV-TRAINING-PLAN.md §7/§8, Phase 2 T9) behind {@code
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
     * The training-run gRPC client (docs/CV-TRAINING-PLAN.md §7/§8, Phase 2 — the last backend
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
     * Starts fine-tune jobs and holds their pollable state (docs/CV-TRAINING-PLAN.md §7/§8, Phase
     * 2) behind {@code TrainingJobController} (vision-api, component-scanned) — a one-line
     * assembly, mirroring {@link #modelRegistryService}'s shape. {@link
     * DefaultTrainingJobService}'s own production constructor submits each run to its own internal
     * cached daemon-thread executor, so {@link TrainingPort#startTraining}'s blocking, potentially
     * many-epoch call never holds a request thread — nothing extra to wire here for that.
     */
    @Bean
    @ConditionalOnProperty(prefix = "vision.training", name = "enabled", havingValue = "true")
    public TrainingJobService trainingJobService(TrainingPort trainingPort, AuditTrailPort auditTrailPort) {
        return new DefaultTrainingJobService(trainingPort, auditTrailPort);
    }
}
