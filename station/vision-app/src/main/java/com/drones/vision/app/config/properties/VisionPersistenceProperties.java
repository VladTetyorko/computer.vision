package com.drones.vision.app.config.properties;

import com.drones.vision.adapter.persistence.config.PersistencePoolSettings;
import com.drones.vision.adapter.persistence.repository.TelemetryBatchSettings;
import com.drones.vision.perception.application.pipeline.UsageSummaryBatchSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

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
 * @param pool          HikariCP sizing for the one pool {@code PersistenceUnit} shares between
 *                      Flyway and Hibernate (docs/plans/active/SCALE-100-PLAN.md S3). Every field
 *                      defaults to {@code PersistencePoolSettings.defaults()}, so an unset {@code
 *                      vision.persistence.pool} block is the same pool an explicit one describing
 *                      the defaults would build.
 * @param telemetry     how the telemetry ingest path batches its writes
 *                      (docs/plans/active/SCALE-100-PLAN.md S4) — one block governing both write
 *                      paths, see {@link Telemetry}.
 */
@ConfigurationProperties(prefix = "vision.persistence")
public record VisionPersistenceProperties(@DefaultValue(VisionPersistenceProperties.DEFAULT_JDBC_URL) String jdbcUrl,
                                           @DefaultValue("vision") String username,
                                           @DefaultValue("vision") String password,
                                           @DefaultValue("false") boolean seedDevUsers,
                                           @DefaultValue Pool pool,
                                           @DefaultValue Telemetry telemetry) {

    /**
     * Binds {@code vision.persistence.pool.*}. Separate from {@link PersistencePoolSettings} — which
     * lives in {@code adapter-persistence} and must stay framework-free — because that module has no
     * Spring dependency to carry {@code @ConfigurationProperties} itself; this record is the Spring
     * half, {@link #toSettings()} the one-line bridge.
     *
     * @param maxSize                     maximum pooled connections; the ceiling on concurrent
     *                                    {@code JpaOperations} calls, since each opens its own
     *                                    {@code EntityManager}
     * @param minIdle                     connections kept warm when idle
     * @param connectionTimeout           how long a caller waits for a connection before failing
     *                                    rather than queueing behind a saturated pool
     * @param leakDetectionThreshold      how long a checked-out connection may be held before
     *                                    HikariCP logs it as a suspected leak
     */
    public record Pool(@DefaultValue("" + PersistencePoolSettings.DEFAULT_MAXIMUM_POOL_SIZE) int maxSize,
                       @DefaultValue("" + PersistencePoolSettings.DEFAULT_MINIMUM_IDLE) int minIdle,
                       @DefaultValue(PersistencePoolSettings.DEFAULT_CONNECTION_TIMEOUT_MILLIS + "ms") Duration connectionTimeout,
                       @DefaultValue(PersistencePoolSettings.DEFAULT_LEAK_DETECTION_THRESHOLD_MILLIS + "ms") Duration leakDetectionThreshold) {

        /** Bridges to {@code adapter-persistence}'s own framework-free settings record. */
        public PersistencePoolSettings toSettings() {
            return new PersistencePoolSettings(maxSize, minIdle, connectionTimeout.toMillis(),
                    leakDetectionThreshold.toMillis());
        }
    }

    /**
     * Binds {@code vision.persistence.telemetry.*} (docs/plans/active/SCALE-100-PLAN.md S4) — how
     * long, and how many samples deep, the ingest path may buffer before it writes.
     *
     * <p>One block, two bridges: the durable sample write lives in {@code adapter-persistence}
     * ({@link TelemetryBatchSettings}) and the coalesced usage-summary write lives in {@code
     * vision-perception} ({@link UsageSummaryBatchSettings}), which cannot share one Java type — a
     * context module must not depend on an adapter. They are still one <em>decision</em>, so they
     * are one property block here rather than two an operator could drift apart.
     *
     * <p>{@code batch-window: 0} is the explicit zero-loss opt-out: both settings read it as
     * immediate mode and every write goes back to being synchronous.
     *
     * @param batchSize   samples buffered per usage before a flush is forced regardless of the
     *                    window — a ceiling for an unusually fast source, not the usual trigger
     * @param batchWindow how long a sample may sit in heap before it is written, and therefore the
     *                    upper bound on what an unclean shutdown loses per open usage
     */
    public record Telemetry(@DefaultValue("" + TelemetryBatchSettings.DEFAULT_BATCH_SIZE_SAMPLES) int batchSize,
                            @DefaultValue(TelemetryBatchSettings.DEFAULT_BATCH_WINDOW_MILLIS + "ms") Duration batchWindow) {

        /** Bridges to {@code adapter-persistence}'s framework-free settings record. */
        public TelemetryBatchSettings toTelemetrySettings() {
            return new TelemetryBatchSettings(batchSize, batchWindow.toMillis());
        }

        /** Bridges to {@code vision-perception}'s own, from the same two numbers. */
        public UsageSummaryBatchSettings toSummarySettings() {
            return new UsageSummaryBatchSettings(batchSize, batchWindow.toMillis());
        }
    }

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
