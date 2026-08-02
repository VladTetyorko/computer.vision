package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the JPA/Postgres fleet persistence adapter ({@code vision.persistence.*}),
 * per docs/MVP2-PLAN.md P-a.
 *
 * <p>Selected by {@code wiring.PersistenceWiring}: {@link #enabled()} {@code false} (the
 * default, today's behavior) keeps every repository port wired to its devsupport in-memory
 * fallback, so every existing test and IDE run stays untouched; {@code true} wires {@code
 * adapter-persistence}'s {@code Jpa*Repository} implementations against {@link #jdbcUrl()}/{@link
 * #username()}/{@link #password()} instead, migrating the schema with Flyway on first use.
 *
 * @param enabled  whether to wire the JPA repositories instead of the in-memory fallbacks;
 *                 default {@code false}
 * @param jdbcUrl  JDBC URL of the Postgres database; only read when {@link #enabled()} is
 *                 {@code true}; default {@value #DEFAULT_JDBC_URL}
 * @param username database user; only read when {@link #enabled()} is {@code true}
 * @param password database password; only read when {@link #enabled()} is {@code true}
 */
@ConfigurationProperties(prefix = "vision.persistence")
public record VisionPersistenceProperties(@DefaultValue("false") boolean enabled,
                                           @DefaultValue(VisionPersistenceProperties.DEFAULT_JDBC_URL) String jdbcUrl,
                                           @DefaultValue("vision") String username,
                                           @DefaultValue("vision") String password) {

    static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:5432/vision";

    public VisionPersistenceProperties {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("vision.persistence.jdbc-url must not be blank");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("vision.persistence.username must not be blank");
        }
        if (password == null) {
            throw new IllegalArgumentException("vision.persistence.password must not be null");
        }
    }
}
