package com.drones.vision.adapter.persistence.config;

/**
 * HikariCP sizing for the single {@code DataSource} {@link PersistenceUnit#start} builds and
 * shares between Flyway's migration connection and every Hibernate-issued {@code EntityManager}
 * (docs/plans/active/SCALE-100-PLAN.md S3). Framework-free by design, matching {@link
 * PersistenceUnit}/{@link JpaOperations}'s own "plain class, no Spring dependency" convention:
 * {@code vision-app} binds the {@code vision.persistence.pool.*} properties and passes this
 * record in, rather than this module reading Spring configuration itself.
 *
 * @param maximumPoolSize            hard ceiling on physical Postgres connections this JVM ever
 *                                    holds open at once, across migration and every request.
 *                                    Default {@value #DEFAULT_MAXIMUM_POOL_SIZE}: this platform's
 *                                    target scale is ~100 concurrent users on one instance
 *                                    (docs/plans/active/SCALE-100-PLAN.md), and every repository call is a
 *                                    short borrow-execute-return against the pool (see {@link
 *                                    JpaOperations} -- no connection is held for a whole request),
 *                                    so a cap well under Postgres's own default {@code
 *                                    max_connections=100} bounds this JVM's worst case while
 *                                    leaving headroom for other clients (psql, monitoring).
 * @param minimumIdle                connections HikariCP keeps warm even when idle, so a request
 *                                    right after a quiet stretch doesn't pay a fresh TCP+auth
 *                                    round trip. Default {@value #DEFAULT_MINIMUM_IDLE} --
 *                                    deliberately below {@code maximumPoolSize} rather than
 *                                    HikariCP's own fixed-size-pool recommendation, because this
 *                                    app is meant to run unattended on operator-supplied hardware
 *                                    (CLAUDE.md's "different servers" deployment note) where
 *                                    holding the full ceiling open around the clock is wasted
 *                                    idle-connection cost on a database that may serve nobody
 *                                    overnight.
 * @param connectionTimeoutMillis    how long a caller waits for a pooled connection, once every
 *                                    pooled connection is checked out, before HikariCP gives up
 *                                    and throws. Default {@value #DEFAULT_CONNECTION_TIMEOUT_MILLIS}
 *                                    -- HikariCP's own well-tested default; nothing about this
 *                                    platform's request shape argues for a different number yet.
 * @param leakDetectionThresholdMillis how long a checked-out connection can go un-returned before
 *                                    HikariCP logs a stack trace of whoever borrowed it. Default
 *                                    {@value #DEFAULT_LEAK_DETECTION_THRESHOLD_MILLIS} -- every
 *                                    {@link JpaOperations} call closes its {@code EntityManager}
 *                                    in a {@code finally} block within milliseconds of opening it,
 *                                    so a connection still outstanding this long past that is a
 *                                    real bug (a leaked {@code EntityManager}), not a slow query;
 *                                    high enough that legitimate work never trips it.
 */
public record PersistencePoolSettings(int maximumPoolSize, int minimumIdle, long connectionTimeoutMillis,
                                       long leakDetectionThresholdMillis) {

    public static final int DEFAULT_MAXIMUM_POOL_SIZE = 20;
    public static final int DEFAULT_MINIMUM_IDLE = 5;
    public static final long DEFAULT_CONNECTION_TIMEOUT_MILLIS = 30_000L;
    public static final long DEFAULT_LEAK_DETECTION_THRESHOLD_MILLIS = 30_000L;

    public PersistencePoolSettings {
        if (maximumPoolSize < 1) {
            throw new IllegalArgumentException("maximumPoolSize must be at least 1");
        }
        if (minimumIdle < 0 || minimumIdle > maximumPoolSize) {
            throw new IllegalArgumentException("minimumIdle must be between 0 and maximumPoolSize");
        }
        if (connectionTimeoutMillis <= 0) {
            throw new IllegalArgumentException("connectionTimeoutMillis must be positive");
        }
        if (leakDetectionThresholdMillis < 0) {
            throw new IllegalArgumentException("leakDetectionThresholdMillis must not be negative");
        }
    }

    /**
     * The defaults documented on each component above -- what {@link PersistenceUnit#start(String,
     * String, String)} and {@link PersistenceUnit#start(String, String, String, boolean)} use for
     * callers (chiefly tests) that don't need to vary pool sizing.
     */
    public static PersistencePoolSettings defaults() {
        return new PersistencePoolSettings(DEFAULT_MAXIMUM_POOL_SIZE, DEFAULT_MINIMUM_IDLE,
                DEFAULT_CONNECTION_TIMEOUT_MILLIS, DEFAULT_LEAK_DETECTION_THRESHOLD_MILLIS);
    }
}
