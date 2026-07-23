package com.drones.vision.app;

import com.drones.vision.adapter.persistence.JpaAssetRepository;
import com.drones.vision.adapter.persistence.JpaAssetUsageRepository;
import com.drones.vision.adapter.persistence.JpaCategoryRepository;
import com.drones.vision.adapter.persistence.JpaDetectionRepository;
import com.drones.vision.adapter.persistence.JpaDeviceRepository;
import com.drones.vision.adapter.persistence.JpaTelemetryRepository;
import com.drones.vision.app.devsupport.InMemoryAssetRepository;
import com.drones.vision.app.devsupport.InMemoryAssetUsageRepository;
import com.drones.vision.app.devsupport.InMemoryCategoryRepository;
import com.drones.vision.app.devsupport.InMemoryDetectionRepository;
import com.drones.vision.app.devsupport.InMemoryDeviceRepository;
import com.drones.vision.app.devsupport.InMemoryTelemetryRepository;

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
                VisionPersistenceProperties.DEFAULT_JDBC_URL, "vision", "vision");

        assertInstanceOf(JpaCategoryRepository.class,
                configuration.categoryRepositoryPort(enabled, entityManagerFactoryProvider));
    }

    @Test
    void enabledSelectsJpaDeviceRepository() {
        when(entityManagerFactoryProvider.getObject()).thenReturn(mock(EntityManagerFactory.class));
        VisionPersistenceProperties enabled = new VisionPersistenceProperties(true,
                VisionPersistenceProperties.DEFAULT_JDBC_URL, "vision", "vision");

        assertInstanceOf(JpaDeviceRepository.class,
                configuration.deviceRepositoryPort(enabled, entityManagerFactoryProvider));
    }

    @Test
    void enabledSelectsJpaAssetRepository() {
        when(entityManagerFactoryProvider.getObject()).thenReturn(mock(EntityManagerFactory.class));
        VisionPersistenceProperties enabled = new VisionPersistenceProperties(true,
                VisionPersistenceProperties.DEFAULT_JDBC_URL, "vision", "vision");

        assertInstanceOf(JpaAssetRepository.class,
                configuration.assetRepositoryPort(enabled, entityManagerFactoryProvider));
    }

    @Test
    void enabledSelectsJpaAssetUsageRepository() {
        when(entityManagerFactoryProvider.getObject()).thenReturn(mock(EntityManagerFactory.class));
        VisionPersistenceProperties enabled = new VisionPersistenceProperties(true,
                VisionPersistenceProperties.DEFAULT_JDBC_URL, "vision", "vision");

        assertInstanceOf(JpaAssetUsageRepository.class,
                configuration.assetUsageRepositoryPort(enabled, entityManagerFactoryProvider));
    }

    @Test
    void enabledSelectsJpaTelemetryRepository() {
        when(entityManagerFactoryProvider.getObject()).thenReturn(mock(EntityManagerFactory.class));
        VisionPersistenceProperties enabled = new VisionPersistenceProperties(true,
                VisionPersistenceProperties.DEFAULT_JDBC_URL, "vision", "vision");

        assertInstanceOf(JpaTelemetryRepository.class,
                configuration.telemetryRepositoryPort(enabled, entityManagerFactoryProvider));
    }

    @Test
    void enabledSelectsJpaDetectionRepository() {
        when(entityManagerFactoryProvider.getObject()).thenReturn(mock(EntityManagerFactory.class));
        VisionPersistenceProperties enabled = new VisionPersistenceProperties(true,
                VisionPersistenceProperties.DEFAULT_JDBC_URL, "vision", "vision");

        assertInstanceOf(JpaDetectionRepository.class,
                configuration.detectionRepositoryPort(enabled, entityManagerFactoryProvider));
    }

    @Test
    void disabledSelectsInMemoryRepositoriesWithoutTouchingTheProvider() {
        VisionPersistenceProperties disabled = new VisionPersistenceProperties(false,
                VisionPersistenceProperties.DEFAULT_JDBC_URL, "vision", "vision");

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
    }
}
