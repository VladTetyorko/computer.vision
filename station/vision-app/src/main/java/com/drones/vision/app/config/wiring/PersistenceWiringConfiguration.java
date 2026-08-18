package com.drones.vision.app.config.wiring;

import com.drones.vision.perception.domain.port.DetectionRepositoryPort;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;
import com.drones.vision.flight.domain.port.GeofenceRepositoryPort;
import com.drones.vision.flight.domain.port.TelemetryRepositoryPort;
import com.drones.vision.identity.domain.port.AssignmentRepositoryPort;
import com.drones.vision.identity.domain.port.GroupRepositoryPort;
import com.drones.vision.identity.domain.port.UserRepositoryPort;
import com.drones.vision.learning.domain.port.DatasetRepositoryPort;
import com.drones.vision.learning.domain.port.SampleImageStorePort;
import com.drones.vision.learning.domain.port.TrainingSampleRepositoryPort;
import com.drones.vision.map.domain.port.DrawingRepositoryPort;
import com.drones.vision.map.domain.port.MapLayerRepositoryPort;
import com.drones.vision.map.domain.port.MarkRepositoryPort;
import com.drones.vision.warehouse.domain.port.AssetImageRepositoryPort;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import com.drones.vision.warehouse.domain.port.CategoryRepositoryPort;
import com.drones.vision.warehouse.domain.port.DeviceRepositoryPort;
import com.drones.vision.adapter.persistence.config.PersistenceUnit;
import com.drones.vision.adapter.persistence.repository.*;
import com.drones.vision.app.config.properties.VisionPersistenceProperties;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the fleet-side repository ports (docs/plans/done/MVP2-PLAN.md P-a: categories, devices, assets) and
 * the history repository ports (docs/plans/done/MVP2-PLAN.md P-b: asset usages, telemetry, detections) — plus
 * every other repository port {@code adapter-persistence} covers — to their Postgres-backed JPA
 * implementations.
 *
 * <p>Straight-line wiring, no {@code @Conditional*}: docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b removed
 * {@code vision.persistence.enabled} (it had exactly one legal value, {@code true}, since W4) along
 * with the devsupport in-memory fallbacks it used to select between. {@link
 * #persistenceEntityManagerFactory} is therefore an ordinary bean like any other here — every port
 * bean below takes it as a plain constructor argument rather than reaching through an {@code
 * ObjectProvider}, since it is now guaranteed to exist by the time Spring resolves any of them.
 *
 * <p>The three P-b beans ({@link #assetUsageRepositoryPort}/{@link #telemetryRepositoryPort}/
 * {@link #detectionRepositoryPort}) use each {@code Jpa*Repository}'s one-argument constructor —
 * its generous default retention cap (100,000 rows per usage/stream) — rather than exposing a new
 * {@code vision.persistence.*} retention property: no operator-facing knob has asked for this yet,
 * and the cap is one constructor argument away from becoming configurable the moment one does
 * (see each {@code Jpa*Repository}'s javadoc for the two-argument constructor already in place for
 * exactly that).
 */
@Configuration
@EnableConfigurationProperties(VisionPersistenceProperties.class)
public class PersistenceWiringConfiguration {

    @Bean(destroyMethod = "close")
    public EntityManagerFactory persistenceEntityManagerFactory(VisionPersistenceProperties properties) {
        return PersistenceUnit.start(properties.jdbcUrl(), properties.username(), properties.password(),
                properties.seedDevUsers(), properties.pool().toSettings());
    }

    @Bean
    public CategoryRepositoryPort categoryRepositoryPort(EntityManagerFactory entityManagerFactory) {
        return new JpaCategoryRepository(entityManagerFactory);
    }

    @Bean
    public DeviceRepositoryPort deviceRepositoryPort(EntityManagerFactory entityManagerFactory) {
        return new JpaDeviceRepository(entityManagerFactory);
    }

    @Bean
    public AssetRepositoryPort assetRepositoryPort(EntityManagerFactory entityManagerFactory) {
        return new JpaAssetRepository(entityManagerFactory);
    }

    @Bean
    public AssetUsageRepositoryPort assetUsageRepositoryPort(EntityManagerFactory entityManagerFactory) {
        return new JpaAssetUsageRepository(entityManagerFactory);
    }

    /**
     * The one repository here with a tunable write path (docs/plans/active/SCALE-100-PLAN.md S4):
     * telemetry is the only table written once per incoming sample, so it is the only one where a
     * per-write flush is worth trading a bounded loss window for.
     */
    @Bean
    public TelemetryRepositoryPort telemetryRepositoryPort(EntityManagerFactory entityManagerFactory,
                                                            VisionPersistenceProperties properties) {
        return new JpaTelemetryRepository(entityManagerFactory,
                JpaTelemetryRepository.DEFAULT_RETENTION_LIMIT_PER_USAGE,
                properties.telemetry().toTelemetrySettings());
    }

    @Bean
    public DetectionRepositoryPort detectionRepositoryPort(EntityManagerFactory entityManagerFactory) {
        return new JpaDetectionRepository(entityManagerFactory);
    }

    /** docs/plans/done/UX-REWORK-PLAN.md §U-d item 3 — the asset image store, same shape as the six above. */
    @Bean
    public AssetImageRepositoryPort assetImageRepositoryPort(EntityManagerFactory entityManagerFactory) {
        return new JpaAssetImageRepository(entityManagerFactory);
    }

    /** docs/plans/done/OPS-CORE-PLAN.md §G — geofence zones, same shape as the seven above. */
    @Bean
    public GeofenceRepositoryPort geofenceRepositoryPort(EntityManagerFactory entityManagerFactory) {
        return new JpaGeofenceRepository(entityManagerFactory);
    }

    /** docs/plans/done/U-AUTH-PLAN.md wave 3 — users (identity aggregate), same shape as the eight above. */
    @Bean
    public UserRepositoryPort userRepositoryPort(EntityManagerFactory entityManagerFactory) {
        return new JpaUserRepository(entityManagerFactory);
    }

    /** docs/plans/done/U-AUTH-PLAN.md wave 3 — groups (org-chart nodes), same shape as the nine above. */
    @Bean
    public GroupRepositoryPort groupRepositoryPort(EntityManagerFactory entityManagerFactory) {
        return new JpaGroupRepository(entityManagerFactory);
    }

    /** docs/plans/done/U-SCOPE-PLAN.md slice 2 — pilot→asset assignments, same shape as the ten above. */
    @Bean
    public AssignmentRepositoryPort assignmentRepositoryPort(EntityManagerFactory entityManagerFactory) {
        return new JpaAssignmentRepository(entityManagerFactory);
    }

    /** docs/plans/done/TACTICAL-MARKS-PLAN.md §3 — tactical marks, same shape as the eleven above. */
    @Bean
    public MarkRepositoryPort markRepositoryPort(EntityManagerFactory entityManagerFactory) {
        return new JpaMarkRepository(entityManagerFactory);
    }

    /** docs/plans/done/CV-TRAINING-PLAN.md §1, Wave T3 — training datasets, same shape as the twelve above. */
    @Bean
    public DatasetRepositoryPort datasetRepositoryPort(EntityManagerFactory entityManagerFactory) {
        return new JpaDatasetRepository(entityManagerFactory);
    }

    /** docs/plans/done/CV-TRAINING-PLAN.md §1, Wave T3 — training samples, same shape as the thirteen above. */
    @Bean
    public TrainingSampleRepositoryPort trainingSampleRepositoryPort(EntityManagerFactory entityManagerFactory) {
        return new JpaTrainingSampleRepository(entityManagerFactory);
    }

    /**
     * docs/plans/done/CV-TRAINING-PLAN.md §1/§C, Wave T3 — the training-sample image store, same shape
     * as the fourteen above.
     */
    @Bean
    public SampleImageStorePort sampleImageStorePort(EntityManagerFactory entityManagerFactory) {
        return new JpaSampleImageStore(entityManagerFactory);
    }

    /**
     * docs/plans/done/MAP-REWORK-PLAN.md §2.3/§4.4 — map layers (with their grant list), same shape as
     * the fifteen above. {@code V12__map_layers.sql} seeds the COP layer at a fixed id, so {@code
     * LayerResolver#copLayerId()}'s find-or-create always finds it rather than creating one.
     */
    @Bean
    public MapLayerRepositoryPort mapLayerRepositoryPort(EntityManagerFactory entityManagerFactory) {
        return new JpaMapLayerRepository(entityManagerFactory);
    }

    /** docs/plans/done/MAP-REWORK-PLAN.md §2.3/§4.4 — map drawings, same shape as the sixteen above. */
    @Bean
    public DrawingRepositoryPort drawingRepositoryPort(EntityManagerFactory entityManagerFactory) {
        return new JpaDrawingRepository(entityManagerFactory);
    }
}
