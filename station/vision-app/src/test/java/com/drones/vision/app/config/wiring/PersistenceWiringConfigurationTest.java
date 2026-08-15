package com.drones.vision.app.config.wiring;

import com.drones.vision.app.config.properties.VisionPersistenceProperties;
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

import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plain unit test (no Spring context, no Docker) for {@link PersistenceWiringConfiguration}'s
 * bean-selection methods themselves — the enabled-branch counterpart to {@link
 * PersistenceWiringTest}'s context-based disabled-branch assertions, which can't safely cover the
 * enabled branch: unlike CV's lazily-connecting gRPC channel ({@link CvEnabledWiringTest}),
 * actually building an {@link EntityManagerFactory} eagerly opens a JDBC connection and runs
 * Flyway (see {@code adapter-persistence}'s {@code PersistenceUnit}), so a real {@code
 * vision.persistence.enabled=true} Spring context would need a reachable Postgres. Calling the
 * {@code @Bean} methods directly with a mocked {@link EntityManagerFactory} — never actually
 * used by the constructed repository (its constructor only stores the reference) — proves the
 * same selection logic without needing either.
 */
class PersistenceWiringConfigurationTest {

    private final PersistenceWiringConfiguration configuration = new PersistenceWiringConfiguration();

    @SuppressWarnings("unchecked")
    private final ObjectProvider<EntityManagerFactory> entityManagerFactoryProvider = mock(ObjectProvider.class);

    @Test
    void enabledSelectsJpaCategoryRepository() {
        when(entityManagerFactoryProvider.getObject()).thenReturn(mock(EntityManagerFactory.class));
        VisionPersistenceProperties enabled = new VisionPersistenceProperties(true,
                "jdbc:postgresql://localhost:5432/vision", "vision", "vision");

        assertInstanceOf(JpaCategoryRepository.class,
                configuration.categoryRepositoryPort(enabled, entityManagerFactoryProvider));
    }

    @Test
    void enabledSelectsJpaDeviceRepository() {
        when(entityManagerFactoryProvider.getObject()).thenReturn(mock(EntityManagerFactory.class));
        VisionPersistenceProperties enabled = new VisionPersistenceProperties(true,
                "jdbc:postgresql://localhost:5432/vision", "vision", "vision");

        assertInstanceOf(JpaDeviceRepository.class,
                configuration.deviceRepositoryPort(enabled, entityManagerFactoryProvider));
    }

    @Test
    void enabledSelectsJpaAssetRepository() {
        when(entityManagerFactoryProvider.getObject()).thenReturn(mock(EntityManagerFactory.class));
        VisionPersistenceProperties enabled = new VisionPersistenceProperties(true,
                "jdbc:postgresql://localhost:5432/vision", "vision", "vision");

        assertInstanceOf(JpaAssetRepository.class,
                configuration.assetRepositoryPort(enabled, entityManagerFactoryProvider));
    }

    @Test
    void enabledSelectsJpaAssetUsageRepository() {
        when(entityManagerFactoryProvider.getObject()).thenReturn(mock(EntityManagerFactory.class));
        VisionPersistenceProperties enabled = new VisionPersistenceProperties(true,
                "jdbc:postgresql://localhost:5432/vision", "vision", "vision");

        assertInstanceOf(JpaAssetUsageRepository.class,
                configuration.assetUsageRepositoryPort(enabled, entityManagerFactoryProvider));
    }

    @Test
    void enabledSelectsJpaTelemetryRepository() {
        when(entityManagerFactoryProvider.getObject()).thenReturn(mock(EntityManagerFactory.class));
        VisionPersistenceProperties enabled = new VisionPersistenceProperties(true,
                "jdbc:postgresql://localhost:5432/vision", "vision", "vision");

        assertInstanceOf(JpaTelemetryRepository.class,
                configuration.telemetryRepositoryPort(enabled, entityManagerFactoryProvider));
    }

    @Test
    void enabledSelectsJpaDetectionRepository() {
        when(entityManagerFactoryProvider.getObject()).thenReturn(mock(EntityManagerFactory.class));
        VisionPersistenceProperties enabled = new VisionPersistenceProperties(true,
                "jdbc:postgresql://localhost:5432/vision", "vision", "vision");

        assertInstanceOf(JpaDetectionRepository.class,
                configuration.detectionRepositoryPort(enabled, entityManagerFactoryProvider));
    }

    @Test
    void enabledSelectsJpaAssetImageRepository() {
        when(entityManagerFactoryProvider.getObject()).thenReturn(mock(EntityManagerFactory.class));
        VisionPersistenceProperties enabled = new VisionPersistenceProperties(true,
                "jdbc:postgresql://localhost:5432/vision", "vision", "vision");

        assertInstanceOf(JpaAssetImageRepository.class,
                configuration.assetImageRepositoryPort(enabled, entityManagerFactoryProvider));
    }

    @Test
    void enabledSelectsJpaGeofenceRepository() {
        when(entityManagerFactoryProvider.getObject()).thenReturn(mock(EntityManagerFactory.class));
        VisionPersistenceProperties enabled = new VisionPersistenceProperties(true,
                "jdbc:postgresql://localhost:5432/vision", "vision", "vision");

        assertInstanceOf(JpaGeofenceRepository.class,
                configuration.geofenceRepositoryPort(enabled, entityManagerFactoryProvider));
    }

    @Test
    void enabledSelectsJpaMarkRepository() {
        when(entityManagerFactoryProvider.getObject()).thenReturn(mock(EntityManagerFactory.class));
        VisionPersistenceProperties enabled = new VisionPersistenceProperties(true,
                "jdbc:postgresql://localhost:5432/vision", "vision", "vision");

        assertInstanceOf(JpaMarkRepository.class,
                configuration.markRepositoryPort(enabled, entityManagerFactoryProvider));
    }

    @Test
    void enabledSelectsJpaDatasetRepository() {
        when(entityManagerFactoryProvider.getObject()).thenReturn(mock(EntityManagerFactory.class));
        VisionPersistenceProperties enabled = new VisionPersistenceProperties(true,
                "jdbc:postgresql://localhost:5432/vision", "vision", "vision");

        assertInstanceOf(JpaDatasetRepository.class,
                configuration.datasetRepositoryPort(enabled, entityManagerFactoryProvider));
    }

    @Test
    void enabledSelectsJpaTrainingSampleRepository() {
        when(entityManagerFactoryProvider.getObject()).thenReturn(mock(EntityManagerFactory.class));
        VisionPersistenceProperties enabled = new VisionPersistenceProperties(true,
                "jdbc:postgresql://localhost:5432/vision", "vision", "vision");

        assertInstanceOf(JpaTrainingSampleRepository.class,
                configuration.trainingSampleRepositoryPort(enabled, entityManagerFactoryProvider));
    }

    @Test
    void enabledSelectsJpaSampleImageStore() {
        when(entityManagerFactoryProvider.getObject()).thenReturn(mock(EntityManagerFactory.class));
        VisionPersistenceProperties enabled = new VisionPersistenceProperties(true,
                "jdbc:postgresql://localhost:5432/vision", "vision", "vision");

        assertInstanceOf(JpaSampleImageStore.class,
                configuration.sampleImageStorePort(enabled, entityManagerFactoryProvider));
    }

    @Test
    void disabledSelectsInMemoryRepositoriesWithoutTouchingTheProvider() {
        VisionPersistenceProperties disabled = new VisionPersistenceProperties(false,
                "jdbc:postgresql://localhost:5432/vision", "vision", "vision");

        assertInstanceOf(InMemoryCategoryRepository.class,
                configuration.categoryRepositoryPort(disabled, entityManagerFactoryProvider));
        assertInstanceOf(InMemoryDeviceRepository.class,
                configuration.deviceRepositoryPort(disabled, entityManagerFactoryProvider));
        assertInstanceOf(InMemoryAssetRepository.class,
                configuration.assetRepositoryPort(disabled, entityManagerFactoryProvider));
        assertInstanceOf(InMemoryAssetUsageRepository.class,
                configuration.assetUsageRepositoryPort(disabled, entityManagerFactoryProvider));
        assertInstanceOf(InMemoryTelemetryRepository.class,
                configuration.telemetryRepositoryPort(disabled, entityManagerFactoryProvider));
        assertInstanceOf(InMemoryDetectionRepository.class,
                configuration.detectionRepositoryPort(disabled, entityManagerFactoryProvider));
        assertInstanceOf(InMemoryAssetImageRepository.class,
                configuration.assetImageRepositoryPort(disabled, entityManagerFactoryProvider));
        assertInstanceOf(InMemoryGeofenceRepository.class,
                configuration.geofenceRepositoryPort(disabled, entityManagerFactoryProvider));
        assertInstanceOf(InMemoryMarkRepository.class,
                configuration.markRepositoryPort(disabled, entityManagerFactoryProvider));
        assertInstanceOf(InMemoryDatasetRepository.class,
                configuration.datasetRepositoryPort(disabled, entityManagerFactoryProvider));
        assertInstanceOf(InMemoryTrainingSampleRepository.class,
                configuration.trainingSampleRepositoryPort(disabled, entityManagerFactoryProvider));
        assertInstanceOf(InMemorySampleImageStore.class,
                configuration.sampleImageStorePort(disabled, entityManagerFactoryProvider));
    }
}
