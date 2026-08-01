package com.drones.vision.app;

import com.drones.vision.adapter.persistence.FilesystemDatasetExport;
import com.drones.vision.application.DatasetService;
import com.drones.vision.application.DefaultDatasetService;
import com.drones.vision.application.DefaultLabelingService;
import com.drones.vision.application.LabelingService;
import com.drones.vision.application.StreamService;
import com.drones.vision.application.TrainingStores;
import com.drones.vision.domain.port.out.AssetRepositoryPort;
import com.drones.vision.domain.port.out.AuditTrailPort;
import com.drones.vision.domain.port.out.DatasetExportPort;
import com.drones.vision.domain.port.out.DatasetRepositoryPort;
import com.drones.vision.domain.port.out.SampleImageStorePort;
import com.drones.vision.domain.port.out.TrainingSampleRepositoryPort;
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
}
