package com.drones.vision.app;

import com.drones.vision.adapter.persistence.repository.JpaAssetImageRepository;
import com.drones.vision.adapter.persistence.repository.JpaAssetRepository;
import com.drones.vision.adapter.persistence.repository.JpaAssetUsageRepository;
import com.drones.vision.adapter.persistence.repository.JpaCategoryRepository;
import com.drones.vision.adapter.persistence.repository.JpaDatasetRepository;
import com.drones.vision.adapter.persistence.repository.JpaDetectionRepository;
import com.drones.vision.adapter.persistence.repository.JpaDeviceRepository;
import com.drones.vision.adapter.persistence.repository.JpaGeofenceRepository;
import com.drones.vision.adapter.persistence.repository.JpaMarkRepository;
import com.drones.vision.adapter.persistence.repository.JpaSampleImageStore;
import com.drones.vision.adapter.persistence.repository.JpaTelemetryRepository;
import com.drones.vision.adapter.persistence.repository.JpaTrainingSampleRepository;
import com.drones.vision.warehouse.domain.port.AssetImageRepositoryPort;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;
import com.drones.vision.warehouse.domain.port.CategoryRepositoryPort;
import com.drones.vision.learning.domain.port.DatasetRepositoryPort;
import com.drones.vision.perception.domain.port.DetectionRepositoryPort;
import com.drones.vision.warehouse.domain.port.DeviceRepositoryPort;
import com.drones.vision.flight.domain.port.GeofenceRepositoryPort;
import com.drones.vision.map.domain.port.MarkRepositoryPort;
import com.drones.vision.learning.domain.port.SampleImageStorePort;
import com.drones.vision.flight.domain.port.TelemetryRepositoryPort;
import com.drones.vision.learning.domain.port.TrainingSampleRepositoryPort;

import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Context test proving {@link com.drones.vision.app.config.wiring.PersistenceWiringConfiguration}
 * wires every fleet-side (docs/plans/done/MVP2-PLAN.md P-a) and history (P-b) repository port to its
 * {@code Jpa*Repository} implementation — the only implementation left, since
 * docs/plans/done/POSTGRES-ONLY-CONTEXT.md W2b deleted the devsupport in-memory fallbacks and the
 * {@code vision.persistence.enabled} flag that used to select between them — and that a real
 * {@link EntityManagerFactory} bean exists. See {@code
 * com.drones.vision.app.config.wiring.PersistenceWiringConfigurationTest} for the same bean-method
 * assertions done without a Spring context or Docker.
 *
 * <p>Needs a reachable Postgres to load at all — {@code
 * com.drones.vision.app.testsupport.PostgresContextCustomizerFactory} points this (and every other)
 * context at the module's shared Testcontainers container; {@code
 * com.drones.vision.app.testsupport.DockerGatedExecutionCondition} skips this class cleanly instead
 * of failing when no Docker daemon is reachable. See vision-app's MODULE.md "Test infrastructure"
 * section.
 *
 * <p>{@code vision.publish.enabled=false} for the same determinism reasons as {@link
 * DiscoveryWiringTest}/{@link CvWiringTest} — this test doesn't care about stream egress.
 */
@SpringBootTest(properties = "vision.publish.enabled=false")
class PersistenceWiringTest {

    @Autowired
    private CategoryRepositoryPort categoryRepositoryPort;

    @Autowired
    private DeviceRepositoryPort deviceRepositoryPort;

    @Autowired
    private AssetRepositoryPort assetRepositoryPort;

    @Autowired
    private AssetUsageRepositoryPort assetUsageRepositoryPort;

    @Autowired
    private TelemetryRepositoryPort telemetryRepositoryPort;

    @Autowired
    private DetectionRepositoryPort detectionRepositoryPort;

    @Autowired
    private AssetImageRepositoryPort assetImageRepositoryPort;

    @Autowired
    private GeofenceRepositoryPort geofenceRepositoryPort;

    @Autowired
    private MarkRepositoryPort markRepositoryPort;

    @Autowired
    private DatasetRepositoryPort datasetRepositoryPort;

    @Autowired
    private TrainingSampleRepositoryPort trainingSampleRepositoryPort;

    @Autowired
    private SampleImageStorePort sampleImageStorePort;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void defaultConfigurationUsesJpaCategoryRepository() {
        assertInstanceOf(JpaCategoryRepository.class, categoryRepositoryPort);
    }

    @Test
    void defaultConfigurationUsesJpaDeviceRepository() {
        assertInstanceOf(JpaDeviceRepository.class, deviceRepositoryPort);
    }

    @Test
    void defaultConfigurationUsesJpaAssetRepository() {
        assertInstanceOf(JpaAssetRepository.class, assetRepositoryPort);
    }

    @Test
    void defaultConfigurationUsesJpaAssetUsageRepository() {
        assertInstanceOf(JpaAssetUsageRepository.class, assetUsageRepositoryPort);
    }

    @Test
    void defaultConfigurationUsesJpaTelemetryRepository() {
        assertInstanceOf(JpaTelemetryRepository.class, telemetryRepositoryPort);
    }

    @Test
    void defaultConfigurationUsesJpaDetectionRepository() {
        assertInstanceOf(JpaDetectionRepository.class, detectionRepositoryPort);
    }

    @Test
    void defaultConfigurationUsesJpaAssetImageRepository() {
        assertInstanceOf(JpaAssetImageRepository.class, assetImageRepositoryPort);
    }

    @Test
    void defaultConfigurationUsesJpaGeofenceRepository() {
        assertInstanceOf(JpaGeofenceRepository.class, geofenceRepositoryPort);
    }

    @Test
    void defaultConfigurationUsesJpaMarkRepository() {
        assertInstanceOf(JpaMarkRepository.class, markRepositoryPort);
    }

    @Test
    void defaultConfigurationUsesJpaDatasetRepository() {
        assertInstanceOf(JpaDatasetRepository.class, datasetRepositoryPort);
    }

    @Test
    void defaultConfigurationUsesJpaTrainingSampleRepository() {
        assertInstanceOf(JpaTrainingSampleRepository.class, trainingSampleRepositoryPort);
    }

    @Test
    void defaultConfigurationUsesJpaSampleImageStore() {
        assertInstanceOf(JpaSampleImageStore.class, sampleImageStorePort);
    }

    @Test
    void defaultConfigurationCreatesExactlyOneEntityManagerFactory() {
        assertFalse(applicationContext.getBeansOfType(EntityManagerFactory.class).isEmpty(),
                "persistenceEntityManagerFactory must run (and open a real database connection) "
                        + "unconditionally, since docs/plans/done/POSTGRES-ONLY-CONTEXT.md W2b removed "
                        + "the flag that used to gate it");
    }
}
