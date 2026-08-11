package com.drones.vision.app;

import com.drones.vision.app.devsupport.InMemoryAssetImageRepository;
import com.drones.vision.app.devsupport.InMemoryAssetRepository;
import com.drones.vision.app.devsupport.InMemoryAssetUsageRepository;
import com.drones.vision.app.devsupport.InMemoryCategoryRepository;
import com.drones.vision.app.devsupport.InMemoryDatasetRepository;
import com.drones.vision.app.devsupport.InMemoryDetectionRepository;
import com.drones.vision.app.devsupport.InMemoryDeviceRepository;
import com.drones.vision.app.devsupport.InMemoryGeofenceRepository;
import com.drones.vision.app.devsupport.InMemoryMarkRepository;
import com.drones.vision.app.devsupport.InMemorySampleImageStore;
import com.drones.vision.app.devsupport.InMemoryTelemetryRepository;
import com.drones.vision.app.devsupport.InMemoryTrainingSampleRepository;
import com.drones.vision.warehouse.domain.port.AssetImageRepositoryPort;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import com.drones.vision.flight.domain.port.AssetUsageRepositoryPort;
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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Context test for the <em>default</em> {@code vision.persistence.*} configuration (no override,
 * per {@link VisionPersistenceProperties#enabled()}'s default of {@code false}): asserts {@link
 * PersistenceWiringConfiguration} keeps today's behavior — every fleet-side (docs/plans/done/MVP2-PLAN.md
 * P-a) and history (P-b) repository port stays its devsupport in-memory fallback — and,
 * critically, that no {@link EntityManagerFactory}
 * bean exists at all, proving {@code persistenceEntityManagerFactory}'s {@code
 * @ConditionalOnProperty} really did keep it from ever running (so this test needs no database,
 * same as every other context test in this module). See {@link PersistenceWiringConfigurationTest}
 * for the enabled-branch bean-selection assertions, done without a Spring context or Docker.
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
    void defaultConfigurationKeepsInMemoryCategoryRepository() {
        assertInstanceOf(InMemoryCategoryRepository.class, categoryRepositoryPort);
    }

    @Test
    void defaultConfigurationKeepsInMemoryDeviceRepository() {
        assertInstanceOf(InMemoryDeviceRepository.class, deviceRepositoryPort);
    }

    @Test
    void defaultConfigurationKeepsInMemoryAssetRepository() {
        assertInstanceOf(InMemoryAssetRepository.class, assetRepositoryPort);
    }

    @Test
    void defaultConfigurationKeepsInMemoryAssetUsageRepository() {
        assertInstanceOf(InMemoryAssetUsageRepository.class, assetUsageRepositoryPort);
    }

    @Test
    void defaultConfigurationKeepsInMemoryTelemetryRepository() {
        assertInstanceOf(InMemoryTelemetryRepository.class, telemetryRepositoryPort);
    }

    @Test
    void defaultConfigurationKeepsInMemoryDetectionRepository() {
        assertInstanceOf(InMemoryDetectionRepository.class, detectionRepositoryPort);
    }

    @Test
    void defaultConfigurationKeepsInMemoryAssetImageRepository() {
        assertInstanceOf(InMemoryAssetImageRepository.class, assetImageRepositoryPort);
    }

    @Test
    void defaultConfigurationKeepsInMemoryGeofenceRepository() {
        assertInstanceOf(InMemoryGeofenceRepository.class, geofenceRepositoryPort);
    }

    @Test
    void defaultConfigurationKeepsInMemoryMarkRepository() {
        assertInstanceOf(InMemoryMarkRepository.class, markRepositoryPort);
    }

    @Test
    void defaultConfigurationKeepsInMemoryDatasetRepository() {
        assertInstanceOf(InMemoryDatasetRepository.class, datasetRepositoryPort);
    }

    @Test
    void defaultConfigurationKeepsInMemoryTrainingSampleRepository() {
        assertInstanceOf(InMemoryTrainingSampleRepository.class, trainingSampleRepositoryPort);
    }

    @Test
    void defaultConfigurationKeepsInMemorySampleImageStore() {
        assertInstanceOf(InMemorySampleImageStore.class, sampleImageStorePort);
    }

    @Test
    void defaultConfigurationNeverCreatesAnEntityManagerFactory() {
        assertTrue(applicationContext.getBeansOfType(EntityManagerFactory.class).isEmpty(),
                "persistenceEntityManagerFactory must not run (and therefore must not attempt a "
                        + "database connection) while vision.persistence.enabled=false");
    }
}
