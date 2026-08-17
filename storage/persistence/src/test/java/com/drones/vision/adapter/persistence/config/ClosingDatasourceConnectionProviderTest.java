package com.drones.vision.adapter.persistence.config;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import java.io.Closeable;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast, docker-free proof of the one behavior this class adds over its base {@code
 * DatasourceConnectionProviderImpl}: stopping the provider closes the {@link Closeable} {@link
 * DataSource} it was configured with. {@code PostgresDockerIntegrationTest}'s {@code
 * ConnectionPoolTests} proves the pool bounds concurrency end to end against a real Postgres; this
 * proves the narrower shutdown-doesn't-leak claim in isolation.
 */
class ClosingDatasourceConnectionProviderTest {

    @Test
    void stopClosesACloseableDataSource() {
        FakeCloseableDataSource dataSource = new FakeCloseableDataSource();
        ClosingDatasourceConnectionProvider provider = new ClosingDatasourceConnectionProvider();
        provider.configure(Map.of("hibernate.connection.datasource", dataSource));
        assertFalse(dataSource.closed, "must not be closed before stop() runs");

        provider.stop();

        assertTrue(dataSource.closed, "stop() must close the DataSource it was configured with");
    }

    @Test
    void stopToleratesADataSourceThatIsNotCloseable() {
        ClosingDatasourceConnectionProvider provider = new ClosingDatasourceConnectionProvider();
        provider.configure(Map.of("hibernate.connection.datasource", new FakeNonCloseableDataSource()));

        provider.stop(); // must not throw just because the configured DataSource isn't Closeable
    }

    /** Every {@link DataSource} method this test never exercises throws, by design. */
    private abstract static class AbstractFakeDataSource implements DataSource {

        @Override
        public Connection getConnection() {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public PrintWriter getLogWriter() {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public void setLoginTimeout(int seconds) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public int getLoginTimeout() {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException("not exercised by this test");
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    private static final class FakeCloseableDataSource extends AbstractFakeDataSource implements Closeable {

        private boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class FakeNonCloseableDataSource extends AbstractFakeDataSource {
    }
}
