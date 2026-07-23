package com.drones.vision.app;

import com.drones.vision.adapter.persistence.JpaAssetRepository;
import com.drones.vision.adapter.persistence.JpaAssetUsageRepository;
import com.drones.vision.adapter.persistence.JpaCategoryRepository;
import com.drones.vision.adapter.persistence.JpaDetectionRepository;
import com.drones.vision.adapter.persistence.JpaDeviceRepository;
import com.drones.vision.adapter.persistence.JpaTelemetryRepository;
import com.drones.vision.adapter.persistence.PersistenceUnit;
import com.drones.vision.app.devsupport.InMemoryAssetRepository;
import com.drones.vision.app.devsupport.InMemoryAssetUsageRepository;
import com.drones.vision.app.devsupport.InMemoryCategoryRepository;
import com.drones.vision.app.devsupport.InMemoryDetectionRepository;
import com.drones.vision.app.devsupport.InMemoryDeviceRepository;
import com.drones.vision.app.devsupport.InMemoryTelemetryRepository;
import com.drones.vision.domain.port.out.AssetRepositoryPort;
import com.drones.vision.domain.port.out.AssetUsageRepositoryPort;
import com.drones.vision.domain.port.out.CategoryRepositoryPort;
import com.drones.vision.domain.port.out.DetectionRepositoryPort;
import com.drones.vision.domain.port.out.DeviceRepositoryPort;
import com.drones.vision.domain.port.out.TelemetryRepositoryPort;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the fleet-side repository ports (docs/MVP2-PLAN.md P-a: categories, devices, assets) and
 * the history repository ports (docs/MVP2-PLAN.md P-b: asset usages, telemetry, detections) to
 * either {@code adapter-persistence}'s Postgres-backed JPA implementations or their devsupport
 * in-memory fallbacks, selected by {@link VisionPersistenceProperties#enabled()} (default
 * {@code false} — unchanged in-memory behavior for every existing test and IDE run).
 *
 * <p>{@link #persistenceEntityManagerFactory} is the only bean gated by {@code @Conditional*}
 * here (rather than a plain if/else inside one method, {@code WiringConfiguration}'s usual
 * style, e.g. {@code detectionPort}): unlike a no-op fallback object, actually *constructing* an
 * {@link EntityManagerFactory} opens a real database connection and runs Flyway, so it must not
 * even be attempted when persistence is disabled — {@link org.springframework.boot.autoconfigure.condition.ConditionalOnProperty}
 * keeps the bean method itself from ever running in that case (same idiom {@code
 * DiscoveryWiringConfiguration} uses for its scanner beans). The six port beans below then
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
}
