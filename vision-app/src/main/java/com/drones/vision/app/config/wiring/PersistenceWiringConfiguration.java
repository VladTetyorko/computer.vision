package com.drones.vision.app.config.wiring;

import com.drones.vision.adapter.persistence.config.PersistenceUnit;
import com.drones.vision.adapter.persistence.repository.*;
import com.drones.vision.app.config.properties.VisionPersistenceProperties;
import com.drones.vision.app.devsupport.*;
import com.drones.vision.domain.port.out.*;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the fleet-side repository ports (docs/plans/done/MVP2-PLAN.md P-a: categories, devices, assets) and
 * the history repository ports (docs/plans/done/MVP2-PLAN.md P-b: asset usages, telemetry, detections) to
 * either {@code adapter-persistence}'s Postgres-backed JPA implementations or their devsupport
 * in-memory fallbacks, selected by {@link VisionPersistenceProperties#enabled()} (default
 * {@code false} — unchanged in-memory behavior for every existing test and IDE run).
 *
 * <p>{@link #persistenceEntityManagerFactory} is the only bean gated by {@code @Conditional*}
 * here (rather than a plain if/else inside one method, {@code WiringConfiguration}'s usual
 * style, e.g. {@code detectionPort}): unlike a no-op fallback object, actually *constructing* an
 * {@link EntityManagerFactory} opens a real database connection and runs Flyway, so it must not
 * even be attempted when persistence is disabled — {@link ConditionalOnProperty}
 * keeps the bean method itself from ever running in that case (same idiom {@code
 * DiscoveryWiringConfiguration} uses for its scanner beans). The twelve port beans below then
 * consume it through {@link ObjectProvider}, which tolerates the bean being entirely absent when
 * disabled — {@link ObjectProvider#getObject()} is only ever called on the branch where {@link
 * VisionPersistenceProperties#enabled()} guarantees it exists.
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
    @ConditionalOnProperty(prefix = "vision.persistence", name = "enabled", havingValue = "true")
    public EntityManagerFactory persistenceEntityManagerFactory(VisionPersistenceProperties properties) {
        return PersistenceUnit.start(properties.jdbcUrl(), properties.username(), properties.password());
    }

    @Bean
    public CategoryRepositoryPort categoryRepositoryPort(VisionPersistenceProperties properties,
                                                           ObjectProvider<EntityManagerFactory> entityManagerFactory) {
        if (properties.enabled()) {
            return new JpaCategoryRepository(entityManagerFactory.getObject());
        }
        return new InMemoryCategoryRepository();
    }

    @Bean
    public DeviceRepositoryPort deviceRepositoryPort(VisionPersistenceProperties properties,
                                                       ObjectProvider<EntityManagerFactory> entityManagerFactory) {
        if (properties.enabled()) {
            return new JpaDeviceRepository(entityManagerFactory.getObject());
        }
        return new InMemoryDeviceRepository();
    }

    @Bean
    public AssetRepositoryPort assetRepositoryPort(VisionPersistenceProperties properties,
                                                     ObjectProvider<EntityManagerFactory> entityManagerFactory) {
        if (properties.enabled()) {
            return new JpaAssetRepository(entityManagerFactory.getObject());
        }
        return new InMemoryAssetRepository();
    }

    @Bean
    public AssetUsageRepositoryPort assetUsageRepositoryPort(VisionPersistenceProperties properties,
                                                                ObjectProvider<EntityManagerFactory> entityManagerFactory) {
        if (properties.enabled()) {
            return new JpaAssetUsageRepository(entityManagerFactory.getObject());
        }
        return new InMemoryAssetUsageRepository();
    }

    @Bean
    public TelemetryRepositoryPort telemetryRepositoryPort(VisionPersistenceProperties properties,
                                                              ObjectProvider<EntityManagerFactory> entityManagerFactory) {
        if (properties.enabled()) {
            return new JpaTelemetryRepository(entityManagerFactory.getObject());
        }
        return new InMemoryTelemetryRepository();
    }

    @Bean
    public DetectionRepositoryPort detectionRepositoryPort(VisionPersistenceProperties properties,
                                                              ObjectProvider<EntityManagerFactory> entityManagerFactory) {
        if (properties.enabled()) {
            return new JpaDetectionRepository(entityManagerFactory.getObject());
        }
        return new InMemoryDetectionRepository();
    }

    /** docs/plans/done/UX-REWORK-PLAN.md §U-d item 3 — the asset image store, same toggle idiom as the six above. */
    @Bean
    public AssetImageRepositoryPort assetImageRepositoryPort(VisionPersistenceProperties properties,
                                                                ObjectProvider<EntityManagerFactory> entityManagerFactory) {
        if (properties.enabled()) {
            return new JpaAssetImageRepository(entityManagerFactory.getObject());
        }
        return new InMemoryAssetImageRepository();
    }

    /** docs/plans/done/OPS-CORE-PLAN.md §G — geofence zones, same toggle idiom as the seven above. */
    @Bean
    public GeofenceRepositoryPort geofenceRepositoryPort(VisionPersistenceProperties properties,
                                                           ObjectProvider<EntityManagerFactory> entityManagerFactory) {
        if (properties.enabled()) {
            return new JpaGeofenceRepository(entityManagerFactory.getObject());
        }
        return new InMemoryGeofenceRepository();
    }

    /** docs/plans/done/U-AUTH-PLAN.md wave 3 — users (identity aggregate), same toggle idiom as the eight above. */
    @Bean
    public UserRepositoryPort userRepositoryPort(VisionPersistenceProperties properties,
                                                  ObjectProvider<EntityManagerFactory> entityManagerFactory) {
        if (properties.enabled()) {
            return new JpaUserRepository(entityManagerFactory.getObject());
        }
        return new InMemoryUserRepository();
    }

    /** docs/plans/done/U-AUTH-PLAN.md wave 3 — groups (org-chart nodes), same toggle idiom as the nine above. */
    @Bean
    public GroupRepositoryPort groupRepositoryPort(VisionPersistenceProperties properties,
                                                    ObjectProvider<EntityManagerFactory> entityManagerFactory) {
        if (properties.enabled()) {
            return new JpaGroupRepository(entityManagerFactory.getObject());
        }
        return new InMemoryGroupRepository();
    }

    /** docs/plans/done/U-SCOPE-PLAN.md slice 2 — pilot→asset assignments, same toggle idiom as the ten above. */
    @Bean
    public AssignmentRepositoryPort assignmentRepositoryPort(VisionPersistenceProperties properties,
                                                             ObjectProvider<EntityManagerFactory> entityManagerFactory) {
        if (properties.enabled()) {
            return new JpaAssignmentRepository(entityManagerFactory.getObject());
        }
        return new InMemoryAssignmentRepository();
    }

    /** docs/plans/done/TACTICAL-MARKS-PLAN.md §3 — tactical marks, same toggle idiom as the eleven above. */
    @Bean
    public MarkRepositoryPort markRepositoryPort(VisionPersistenceProperties properties,
                                                  ObjectProvider<EntityManagerFactory> entityManagerFactory) {
        if (properties.enabled()) {
            return new JpaMarkRepository(entityManagerFactory.getObject());
        }
        return new InMemoryMarkRepository();
    }

    /** docs/plans/done/CV-TRAINING-PLAN.md §1, Wave T3 — training datasets, same toggle idiom as the twelve above. */
    @Bean
    public DatasetRepositoryPort datasetRepositoryPort(VisionPersistenceProperties properties,
                                                        ObjectProvider<EntityManagerFactory> entityManagerFactory) {
        if (properties.enabled()) {
            return new JpaDatasetRepository(entityManagerFactory.getObject());
        }
        return new InMemoryDatasetRepository();
    }

    /** docs/plans/done/CV-TRAINING-PLAN.md §1, Wave T3 — training samples, same toggle idiom as the thirteen above. */
    @Bean
    public TrainingSampleRepositoryPort trainingSampleRepositoryPort(VisionPersistenceProperties properties,
                                                                     ObjectProvider<EntityManagerFactory> entityManagerFactory) {
        if (properties.enabled()) {
            return new JpaTrainingSampleRepository(entityManagerFactory.getObject());
        }
        return new InMemoryTrainingSampleRepository();
    }

    /**
     * docs/plans/done/CV-TRAINING-PLAN.md §1/§C, Wave T3 — the training-sample image store, same toggle idiom
     * as the fourteen above.
     */
    @Bean
    public SampleImageStorePort sampleImageStorePort(VisionPersistenceProperties properties,
                                                      ObjectProvider<EntityManagerFactory> entityManagerFactory) {
        if (properties.enabled()) {
            return new JpaSampleImageStore(entityManagerFactory.getObject());
        }
        return new InMemorySampleImageStore();
    }

    /**
     * docs/plans/done/MAP-REWORK-PLAN.md §2.3/§4.4 — map layers (with their grant list), same toggle idiom as
     * the fifteen above.
     *
     * <p>The two persistence modes seed the COP layer differently but converge: {@code
     * V12__map_layers.sql} inserts it with a fixed id, while the in-memory fallback starts empty and
     * relies on {@code LayerResolver#copLayerId()}'s synchronized find-or-create, which {@code
     * ApplicationServiceWiring#mapLayerBootstrapRunner} calls once at startup.
     */
    @Bean
    public MapLayerRepositoryPort mapLayerRepositoryPort(VisionPersistenceProperties properties,
                                                          ObjectProvider<EntityManagerFactory> entityManagerFactory) {
        if (properties.enabled()) {
            return new JpaMapLayerRepository(entityManagerFactory.getObject());
        }
        return new InMemoryMapLayerRepository();
    }

    /** docs/plans/done/MAP-REWORK-PLAN.md §2.3/§4.4 — map drawings, same toggle idiom as the sixteen above. */
    @Bean
    public DrawingRepositoryPort drawingRepositoryPort(VisionPersistenceProperties properties,
                                                        ObjectProvider<EntityManagerFactory> entityManagerFactory) {
        if (properties.enabled()) {
            return new JpaDrawingRepository(entityManagerFactory.getObject());
        }
        return new InMemoryDrawingRepository();
    }
}
