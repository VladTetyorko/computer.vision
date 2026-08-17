package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the JPA/Postgres fleet persistence adapter ({@code vision.persistence.*}),
 * per docs/plans/done/MVP2-PLAN.md P-a.
 *
 * <p>Consumed by {@code PersistenceWiringConfiguration}, which wires {@code adapter-persistence}'s
 * {@code Jpa*Repository} implementations — the only repository implementations left, since
 * docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b deleted the devsupport in-memory fallbacks — against
 * {@link #jdbcUrl()}/{@link #username()}/{@link #password()}, migrating the schema with Flyway on
 * first use. A reachable Postgres is therefore required to run this app, or its {@code vision-app}
 * test suite (see that module's MODULE.md "Test infrastructure" section for how the test suite gets
 * one); there is no longer a flag to opt out of it.
 *
 * @param jdbcUrl       JDBC URL of the Postgres database; default {@value #DEFAULT_JDBC_URL}
 * @param username      database user
 * @param password      database password
 * @param seedDevUsers  whether {@link com.drones.vision.adapter.persistence.config.PersistenceUnit}
 *                      also applies {@code classpath:db/seed/dev} — the DEV-ONLY {@code admin}/
 *                      {@code manager}/{@code pilot} accounts (password equal to username) that used
 *                      to be seeded unconditionally by the now-deleted {@code AuthSeedRunner}; there
 *                      is no in-memory equivalent (see docs/plans/active/POSTGRES-ONLY-CONTEXT.md W1).
 *                      Default {@code false}: an operator does not get username-equals-password
 *                      login accounts unless they explicitly ask for them too — {@code
 *                      docker-compose.yml}'s friends-demo stack is the one place that does.
 *                      <strong>Never set this on anything but a local/demo database.</strong>
 */
@ConfigurationProperties(prefix = "vision.persistence")
public record VisionPersistenceProperties(@DefaultValue(VisionPersistenceProperties.DEFAULT_JDBC_URL) String jdbcUrl,
                                           @DefaultValue("vision") String username,
                                           @DefaultValue("vision") String password,
                                           @DefaultValue("false") boolean seedDevUsers) {

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
