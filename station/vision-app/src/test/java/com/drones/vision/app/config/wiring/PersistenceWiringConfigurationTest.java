package com.drones.vision.app.config.wiring;

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

import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

/**
 * Plain unit test (no Spring context, no Docker) for {@link PersistenceWiringConfiguration}'s
 * bean methods themselves: each just wraps a mocked {@link EntityManagerFactory} — never actually
 * used by the constructed repository (its constructor only stores the reference) — in the right
 * {@code Jpa*Repository}, so this proves the wiring without needing a real database.
 *
 * <p>Before docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b these methods also took a {@code
 * VisionPersistenceProperties}/{@code ObjectProvider<EntityManagerFactory>} pair and branched on
 * {@code vision.persistence.enabled} to select between this and a devsupport in-memory fallback;
 * that flag had exactly one legal value after W4 and is gone entirely now, so every method below
 * is a plain one-argument constructor call with nothing left to branch on.
 */
class PersistenceWiringConfigurationTest {

    private final PersistenceWiringConfiguration configuration = new PersistenceWiringConfiguration();

    private final EntityManagerFactory entityManagerFactory = mock(EntityManagerFactory.class);

    @Test
    void selectsJpaCategoryRepository() {
        assertInstanceOf(JpaCategoryRepository.class, configuration.categoryRepositoryPort(entityManagerFactory));
    }

    @Test
    void selectsJpaDeviceRepository() {
        assertInstanceOf(JpaDeviceRepository.class, configuration.deviceRepositoryPort(entityManagerFactory));
    }

    @Test
    void selectsJpaAssetRepository() {
        assertInstanceOf(JpaAssetRepository.class, configuration.assetRepositoryPort(entityManagerFactory));
    }

    @Test
    void selectsJpaAssetUsageRepository() {
        assertInstanceOf(JpaAssetUsageRepository.class, configuration.assetUsageRepositoryPort(entityManagerFactory));
    }

    @Test
    void selectsJpaTelemetryRepository() {
        assertInstanceOf(JpaTelemetryRepository.class, configuration.telemetryRepositoryPort(entityManagerFactory));
    }

    @Test
    void selectsJpaDetectionRepository() {
        assertInstanceOf(JpaDetectionRepository.class, configuration.detectionRepositoryPort(entityManagerFactory));
    }

    @Test
    void selectsJpaAssetImageRepository() {
        assertInstanceOf(JpaAssetImageRepository.class, configuration.assetImageRepositoryPort(entityManagerFactory));
    }

    @Test
    void selectsJpaGeofenceRepository() {
        assertInstanceOf(JpaGeofenceRepository.class, configuration.geofenceRepositoryPort(entityManagerFactory));
    }

    @Test
    void selectsJpaMarkRepository() {
        assertInstanceOf(JpaMarkRepository.class, configuration.markRepositoryPort(entityManagerFactory));
    }

    @Test
    void selectsJpaDatasetRepository() {
        assertInstanceOf(JpaDatasetRepository.class, configuration.datasetRepositoryPort(entityManagerFactory));
    }

    @Test
    void selectsJpaTrainingSampleRepository() {
        assertInstanceOf(JpaTrainingSampleRepository.class,
                configuration.trainingSampleRepositoryPort(entityManagerFactory));
    }

    @Test
    void selectsJpaSampleImageStore() {
        assertInstanceOf(JpaSampleImageStore.class, configuration.sampleImageStorePort(entityManagerFactory));
    }
}
