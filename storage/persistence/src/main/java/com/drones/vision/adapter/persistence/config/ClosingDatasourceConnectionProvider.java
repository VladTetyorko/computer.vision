package com.drones.vision.adapter.persistence.config;

import org.hibernate.engine.jdbc.connections.internal.DatasourceConnectionProviderImpl;

import javax.sql.DataSource;

import java.io.Closeable;
import java.io.IOException;

/**
 * {@link DatasourceConnectionProviderImpl} extended to close the {@link DataSource} it wraps when
 * Hibernate stops it, so closing the {@code EntityManagerFactory} {@link PersistenceUnit#start}
 * returns also tears down the connection pool underneath it (docs/plans/done/SCALE-100-PLAN.md S3).
 *
 * <p>The base class is written for a container-managed {@link DataSource} (e.g. a JNDI lookup)
 * that Hibernate never owns and must never close. That assumption is wrong for {@link
 * PersistenceUnit#start}: it builds the {@link DataSource} itself, hands the same instance to
 * Flyway and to this provider, and nothing else references it once the {@code
 * EntityManagerFactory} is gone -- so closing the factory must close the pool too, or every JVM
 * shutdown (and every fresh-{@code EntityManagerFactory} test in this module) leaks HikariCP's
 * background threads. Only a {@link Closeable} data source is closed here (not every {@link
 * DataSource} implements that), keeping this provider usable for any pooled implementation, not
 * only HikariCP.
 *
 * <p>Selected via {@code hibernate.connection.provider_class} rather than Hibernate's own {@code
 * org.hibernate.hikaricp.internal.HikariCPConnectionProvider}: that class always builds its
 * <em>own</em> internal {@code HikariDataSource} from {@code hibernate.hikari.*} properties, which
 * would be a second, independent pool that Flyway's migration connection never shares -- see
 * {@link PersistenceUnit}'s javadoc for why one shared pool is the point of this wave.
 */
public final class ClosingDatasourceConnectionProvider extends DatasourceConnectionProviderImpl {

    @Override
    public void stop() {
        DataSource dataSource = getDataSource();
        super.stop();
        if (dataSource instanceof Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException e) {
                throw new IllegalStateException("failed to close the persistence connection pool", e);
            }
        }
    }
}
