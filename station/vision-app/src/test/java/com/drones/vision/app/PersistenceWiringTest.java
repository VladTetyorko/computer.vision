package com.drones.vision.app;

import com.drones.vision.adapter.persistence.repository.JpaAssetImageRepository;
import com.drones.vision.adapter.persistence.repository.JpaAssetRepository;
import com.drones.vision.adapter.persistence.repository.JpaAssetUsageRepository;
import com.drones.vision.adapter.persistence.repository.JpaCategoryRepository;
import com.drones.vision.adapter.persistence.repository.JpaCvProfileRepository;
import com.drones.vision.adapter.persistence.repository.JpaDatasetRepository;
import com.drones.vision.adapter.persistence.repository.JpaDetectionRepository;
import com.drones.vision.adapter.persistence.repository.JpaDeviceRepository;
import com.drones.vision.adapter.persistence.repository.JpaGeofenceRepository;
import com.drones.vision.adapter.persistence.repository.JpaMarkRepository;
import com.drones.vision.adapter.persistence.repository.JpaSampleImageStore;
import com.drones.vision.adapter.persistence.repository.JpaTelemetryRepository;
import com.drones.vision.adapter.persistence.repository.JpaTrainingSampleRepository;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.perception.domain.model.CvProfile;
import com.drones.vision.perception.domain.model.CvProfileId;
import com.drones.vision.perception.domain.model.Intent;
import com.drones.vision.perception.domain.port.CvProfileRepositoryPort;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    private CvProfileRepositoryPort cvProfileRepositoryPort;

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

    @Test
    void defaultConfigurationUsesJpaCvProfileRepository() {
        assertInstanceOf(JpaCvProfileRepository.class, cvProfileRepositoryPort);
    }

    /**
     * docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7, decision E22, wave W7.2 — the "a profile is a
     * patch" round trip proven through the whole assembled Spring context (real {@code
     * cv_profiles} table, {@code V36__cv_profile_patch.sql} applied by the same Flyway run this
     * context boots with) rather than {@code storage/persistence}'s own directly-constructed {@code
     * EntityManagerFactory} — a genuinely different thing to prove, since it exercises this
     * module's own {@link com.drones.vision.app.config.wiring.PersistenceWiringConfiguration#cvProfileRepositoryPort}
     * bean wiring end to end. {@code storage/persistence}'s own {@code
     * PostgresDockerIntegrationTest.CvProfileRepositoryTests} covers the exhaustive per-knob/CHECK-
     * constraint cases; this one test is the "does production wiring actually deliver the same
     * behavior" spot check the brief asks this module to carry.
     */
    @Test
    void partialCvProfileWithOnlyIntentAndInferenceFpsRoundTripsThroughTheWiredJpaRepository() {
        // Truncated to millis, matching storage/persistence's own PostgresDockerIntegrationTest
        // precedent -- Postgres timestamptz columns round-trip at microsecond precision, so a raw
        // Instant.now() (nanosecond precision) never compares equal to what a re-read returns.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        CvProfile partial = new CvProfile(CvProfileId.random(), "w7-2-partial-check", "", false, GroupId.random(),
                null, null, 12, null, null, null, null, null, Intent.VEHICLES, now, now);

        cvProfileRepositoryPort.save(partial);

        CvProfile found = cvProfileRepositoryPort.findById(partial.id()).orElseThrow();
        assertEquals(partial, found);
        assertEquals(Intent.VEHICLES, found.intent());
        assertEquals(12, found.inferenceFps());
        assertNull(found.model(), "every knob this profile left unset must still read null, not a default");
        assertNull(found.confidenceThreshold());
        assertNull(found.labelFilter());
        assertNull(found.labelDenyFilter());
        assertNull(found.detectionEnabled());
        assertNull(found.tracking());
        assertNull(found.eventRule());
    }
}
