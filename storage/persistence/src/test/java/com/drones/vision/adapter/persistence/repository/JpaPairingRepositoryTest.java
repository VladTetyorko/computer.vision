package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.PersistenceUnit;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.warehouse.domain.model.Pairing;
import com.drones.vision.warehouse.domain.model.PairingId;
import com.drones.vision.warehouse.domain.model.RadioBind;
import com.drones.vision.warehouse.domain.model.VehicleKey;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trips {@link JpaPairingRepository} against a real Postgres container (docs/plans/active/
 * LINK-PAIRING-PLAN.md §4 row L2) — a dedicated, narrowly-scoped test rather than one more section
 * added to the {@code PostgresDockerIntegrationTest} monolith, so this wave's own file scope stays
 * disjoint from other agents' concurrent work in that same shared file.
 *
 * <p>The one fact this file exists to prove and the monolith's generic round-trip loop would not
 * catch on its own: {@link VehicleKey}'s 32 raw bytes survive a save/read cycle through {@code
 * bytea} <em>exactly</em>, byte-for-byte — {@link VehicleKey#equals} already does value comparison,
 * but this test also asserts on the raw {@code byte[]} directly so a silent truncation/padding bug
 * in the {@code bytea} mapping cannot hide behind a record's own equality contract.
 *
 * <p>Skips cleanly (not a failure) when no Docker daemon is reachable — same {@code @EnabledIf}
 * contract as {@link com.drones.vision.adapter.persistence.PostgresDockerIntegrationTest}.
 */
@Testcontainers
@EnabledIf(value = "dockerAvailable", disabledReason = "docker is not available in this environment")
class JpaPairingRepositoryTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    private static EntityManagerFactory entityManagerFactory;

    @BeforeAll
    static void migrateAndOpen() {
        entityManagerFactory = PersistenceUnit.start(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    @AfterAll
    static void close() {
        if (entityManagerFactory != null) {
            entityManagerFactory.close();
        }
    }

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * {@code uq_pairings_sysid} is a real, class-wide unique index and {@link #POSTGRES} is one
     * container shared by every test method in this class (not reset between them) — a fixed sysid
     * reused across methods collides. Each call hands out the next number in a private range no
     * other test in this class or {@link com.drones.vision.adapter.persistence.PostgresDockerIntegrationTest}
     * (a separate container) can reach.
     */
    private static final AtomicInteger NEXT_SYSID = new AtomicInteger(10);

    private static byte[] randomKeyBytes() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static Pairing pairing(byte[] keyBytes) {
        return new Pairing(PairingId.random(), DeviceId.random(), NEXT_SYSID.getAndIncrement(),
                new VehicleKey(keyBytes), BigInteger.valueOf(123456789L), RadioBind.NONE, NOW, null);
    }

    @Test
    void vehicleKeyBytesRoundTripExactly() {
        byte[] keyBytes = randomKeyBytes();
        Pairing pairing = pairing(keyBytes);
        JpaPairingRepository repository = new JpaPairingRepository(entityManagerFactory);

        repository.save(pairing);
        Pairing reloaded = repository.findById(pairing.id()).orElseThrow();

        assertArrayEquals(keyBytes, reloaded.vehicleKey().value(),
                "VehicleKey's 32 bytes must round-trip exactly through bytea");
        assertEquals(pairing.vehicleKey(), reloaded.vehicleKey());
    }

    @Test
    void vehicleKeyBytesSurviveAFreshEntityManagerFactoryAgainstTheSameDatabase() {
        byte[] keyBytes = randomKeyBytes();
        Pairing pairing = pairing(keyBytes);
        new JpaPairingRepository(entityManagerFactory).save(pairing);

        EntityManagerFactory freshContext = PersistenceUnit.start(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        try {
            Pairing reloaded = new JpaPairingRepository(freshContext).findById(pairing.id()).orElseThrow();
            assertArrayEquals(keyBytes, reloaded.vehicleKey().value());
        } finally {
            freshContext.close();
        }
    }

    @Test
    void savePersistsEveryFieldAndFindByDeviceIdAndFindBySysidAgree() {
        Pairing pairing = pairing(randomKeyBytes());
        JpaPairingRepository repository = new JpaPairingRepository(entityManagerFactory);

        repository.save(pairing);

        Optional<Pairing> byId = repository.findById(pairing.id());
        Optional<Pairing> byDevice = repository.findByDeviceId(pairing.deviceId());
        Optional<Pairing> bySysid = repository.findBySysid(pairing.sysid());
        assertTrue(byId.isPresent());
        assertTrue(byDevice.isPresent());
        assertTrue(bySysid.isPresent());
        assertEquals(pairing.id(), byId.get().id());
        assertEquals(pairing.id(), byDevice.get().id());
        assertEquals(pairing.id(), bySysid.get().id());
        assertEquals(pairing.hardwareUid(), byId.get().hardwareUid());
        assertEquals(pairing.createdAt(), byId.get().createdAt());
        assertEquals(Map.of(), byId.get().radioBind().attributes());
    }

    @Test
    void deleteByIdHardDeletesTheRowAndIsIdempotent() {
        Pairing pairing = pairing(randomKeyBytes());
        JpaPairingRepository repository = new JpaPairingRepository(entityManagerFactory);
        repository.save(pairing);

        repository.deleteById(pairing.id());
        repository.deleteById(pairing.id()); // idempotent -- second call must not throw

        assertTrue(repository.findById(pairing.id()).isEmpty());
    }

    @Test
    void twoVehicleKeysWithDifferentBytesAreNeverEqualAfterRoundTripping() {
        JpaPairingRepository repository = new JpaPairingRepository(entityManagerFactory);
        Pairing first = pairing(randomKeyBytes());
        Pairing second = new Pairing(PairingId.random(), DeviceId.random(), NEXT_SYSID.getAndIncrement(),
                new VehicleKey(randomKeyBytes()), null, RadioBind.NONE, NOW, null);
        repository.save(first);
        repository.save(second);

        Pairing reloadedFirst = repository.findById(first.id()).orElseThrow();
        Pairing reloadedSecond = repository.findById(second.id()).orElseThrow();

        assertTrue(!Arrays.equals(reloadedFirst.vehicleKey().value(), reloadedSecond.vehicleKey().value()));
    }
}
