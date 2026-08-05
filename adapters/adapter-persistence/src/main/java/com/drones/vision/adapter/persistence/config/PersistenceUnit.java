package com.drones.vision.adapter.persistence.config;

import com.drones.vision.adapter.persistence.entity.AssetEntity;
import com.drones.vision.adapter.persistence.entity.AssetImageEntity;
import com.drones.vision.adapter.persistence.entity.AssetUsageEntity;
import com.drones.vision.adapter.persistence.entity.AssignmentEntity;
import com.drones.vision.adapter.persistence.entity.CategoryEntity;
import com.drones.vision.adapter.persistence.entity.DatasetEntity;
import com.drones.vision.adapter.persistence.entity.DetectionResultEntity;
import com.drones.vision.adapter.persistence.entity.DeviceEntity;
import com.drones.vision.adapter.persistence.entity.GeofenceZoneEntity;
import com.drones.vision.adapter.persistence.entity.GroupEntity;
import com.drones.vision.adapter.persistence.entity.MapDrawingEntity;
import com.drones.vision.adapter.persistence.entity.MapLayerEntity;
import com.drones.vision.adapter.persistence.entity.MarkEntity;
import com.drones.vision.adapter.persistence.entity.SampleImageEntity;
import com.drones.vision.adapter.persistence.entity.TelemetrySampleEntity;
import com.drones.vision.adapter.persistence.entity.TrainingSampleEntity;
import com.drones.vision.adapter.persistence.entity.UserEntity;

import jakarta.persistence.EntityManagerFactory;

import org.flywaydb.core.Flyway;
import org.hibernate.cfg.Configuration;

/**
 * Boots the persistence unit backing every {@code Jpa*Repository} in this module: migrates the
 * schema with Flyway ({@code classpath:db/migration}), then opens a Hibernate {@link
 * EntityManagerFactory} against the same database.
 *
 * <p>No connection pool and no Spring involved: {@link #start} uses Hibernate's native bootstrap
 * API ({@link Configuration}, whose {@link Configuration#buildSessionFactory()} return type —
 * {@link org.hibernate.SessionFactory} — implements {@link EntityManagerFactory} directly, so
 * every {@code Jpa*Repository} still only ever calls standard {@code jakarta.persistence} API)
 * and its default {@code DriverManager}-based connection provider (one JDBC connection per
 * {@code EntityManager}, opened/closed per call — see {@link JpaOperations}). Adequate for this
 * platform's single-instance scope; swapping in a pooled provider (e.g. HikariCP, already on the
 * classpath transitively via Hibernate's own dependencies) is a config-only change if concurrency
 * ever demands it — see MODULE.md's Gotchas.
 *
 * <p>Every value {@link #start} sets on {@link Configuration} is either a caller-supplied
 * argument (JDBC URL/user/password) or a fixed protocol/design constant (the JDBC driver class,
 * the SQL dialect, {@code hibernate.hbm2ddl.auto=validate}) — there is no hardcoded
 * connection-pool sizing, batch size, or timeout here to externalize into a settings record
 * (docs/LAYERING-REFACTOR-PLAN.md §3/§7 row C item 5): this module has no framework dependency of
 * its own to read such a setting from, and none of these three properties varies per environment
 * the way a pool size or timeout would.
 */
public final class PersistenceUnit {

    private PersistenceUnit() {
    }

    /**
     * Migrates the schema then opens an {@link EntityManagerFactory} mapping every entity in
     * {@link com.drones.vision.adapter.persistence.entity}.
     *
     * @param jdbcUrl  JDBC URL, e.g. {@code jdbc:postgresql://localhost:5432/vision}
     * @param username database user
     * @param password database password
     * @return an open {@link EntityManagerFactory}; the caller owns its lifecycle and must
     *         {@code close()} it on shutdown
     */
    public static EntityManagerFactory start(String jdbcUrl, String username, String password) {
        Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .load()
                .migrate();

        Configuration configuration = new Configuration();
        configuration.setProperty("jakarta.persistence.jdbc.url", jdbcUrl);
        configuration.setProperty("jakarta.persistence.jdbc.user", username);
        configuration.setProperty("jakarta.persistence.jdbc.password", password);
        configuration.setProperty("jakarta.persistence.jdbc.driver", "org.postgresql.Driver");
        configuration.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        // Flyway owns the schema; Hibernate only ever validates its entity mapping against it.
        configuration.setProperty("hibernate.hbm2ddl.auto", "validate");
        configuration.addAnnotatedClass(CategoryEntity.class);
        configuration.addAnnotatedClass(DeviceEntity.class);
        configuration.addAnnotatedClass(AssetEntity.class);
        configuration.addAnnotatedClass(AssetUsageEntity.class);
        configuration.addAnnotatedClass(TelemetrySampleEntity.class);
        configuration.addAnnotatedClass(DetectionResultEntity.class);
        configuration.addAnnotatedClass(AssetImageEntity.class);
        configuration.addAnnotatedClass(GeofenceZoneEntity.class);
        configuration.addAnnotatedClass(UserEntity.class);
        configuration.addAnnotatedClass(GroupEntity.class);
        configuration.addAnnotatedClass(AssignmentEntity.class);
        configuration.addAnnotatedClass(MarkEntity.class);
        configuration.addAnnotatedClass(DatasetEntity.class);
        configuration.addAnnotatedClass(TrainingSampleEntity.class);
        configuration.addAnnotatedClass(SampleImageEntity.class);
        // docs/MAP-REWORK-PLAN.md Wave C (V12__map_layers.sql). LayerGrantEmbeddable needs no
        // registration of its own -- Hibernate discovers an @Embeddable through the @ElementCollection
        // field that uses it, unlike an @Entity, which must be named explicitly here.
        configuration.addAnnotatedClass(MapLayerEntity.class);
        configuration.addAnnotatedClass(MapDrawingEntity.class);
        return configuration.buildSessionFactory();
    }
}
