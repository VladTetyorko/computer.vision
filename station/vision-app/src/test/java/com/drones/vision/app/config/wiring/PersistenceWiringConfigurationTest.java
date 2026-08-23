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
import com.drones.vision.adapter.persistence.repository.TelemetryBatchSettings;
import com.drones.vision.app.config.properties.VisionPersistenceProperties;
import com.drones.vision.perception.application.pipeline.UsageSummaryBatchSettings;

import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

/**
 * Plain unit test (no Spring context, no Docker) for {@link PersistenceWiringConfiguration}'s
 * bean methods themselves: each just wraps a mocked {@link EntityManagerFactory} — never actually
 * used by the constructed repository (its constructor only stores the reference) — in the right
 * {@code Jpa*Repository}, so this proves the wiring without needing a real database.
 *
 * <p>Before docs/plans/done/POSTGRES-ONLY-CONTEXT.md W2b these methods also took a {@code
 * VisionPersistenceProperties}/{@code ObjectProvider<EntityManagerFactory>} pair and branched on
 * {@code vision.persistence.enabled} to select between this and a devsupport in-memory fallback;
 * that flag had exactly one legal value after W4 and is gone entirely now, so every method below is a
 * plain one-argument constructor call with nothing left to branch on — except {@code
 * telemetryRepositoryPort}, which reads the batching block SCALE-100 S4 added.
 */
class PersistenceWiringConfigurationTest {

    private final PersistenceWiringConfiguration configuration = new PersistenceWiringConfiguration();

    private final EntityManagerFactory entityManagerFactory = mock(EntityManagerFactory.class);

    /**
     * Bound from an <em>empty</em> source rather than constructed, so what this test sees is exactly
     * what a deployment with no {@code vision.persistence} block gets — the {@code @DefaultValue}
     * annotations, not whatever a hand-written {@code new} call happens to pass.
     */
    private final VisionPersistenceProperties persistenceProperties =
            new Binder(new MapConfigurationPropertySource(Map.of()))
                    .bindOrCreate("vision.persistence", VisionPersistenceProperties.class);

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
        assertInstanceOf(JpaTelemetryRepository.class,
                configuration.telemetryRepositoryPort(entityManagerFactory, persistenceProperties));
    }

    /**
     * The only bean here that reads configuration beyond the connection itself
     * (docs/plans/done/SCALE-100-PLAN.md S4). Wiring it with immediate settings — or forgetting to
     * pass them at all, which the one-argument constructor makes easy and silent — would leave the
     * per-sample flush in place while every doc claims it is gone, so the *default* configuration is
     * what this pins.
     */
    @Test
    void wiresTheTelemetryRepositoryToBatchByDefaultRatherThanFlushPerSample() {
        assertFalse(persistenceProperties.telemetry().toTelemetrySettings().isImmediate(),
                "an unset vision.persistence.telemetry block must still mean batched writes");
        assertEquals(TelemetryBatchSettings.defaults(), persistenceProperties.telemetry().toTelemetrySettings());
        assertEquals(UsageSummaryBatchSettings.defaults(), persistenceProperties.telemetry().toSummarySettings(),
                "both write paths take the same window -- one ingest decision, two settings types");
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
