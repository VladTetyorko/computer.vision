package com.drones.vision.adapter.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;

import java.util.Objects;
import java.util.function.Function;

/**
 * Opens one short-lived {@link EntityManager} per call and runs the given unit of work against
 * it — the transaction-boilerplate shared by every {@code Jpa*Repository} in this module, so each
 * of those classes stays a one-constructor-argument wrapper around {@link EntityManagerFactory}
 * plus its own entity/domain mapping.
 *
 * <p>Not a pooled/request-scoped {@code EntityManager} (there is no request or Spring
 * transaction context here to scope it to) — every {@link #write}/{@link #read} call is its own
 * connection acquire/release. Fine at this platform's scale; see {@code PersistenceUnit}'s
 * javadoc for the same tradeoff at the connection-provider level.
 */
final class JpaOperations {

    private final EntityManagerFactory entityManagerFactory;

    JpaOperations(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = Objects.requireNonNull(entityManagerFactory,
                "entityManagerFactory must not be null");
    }

    /** Runs {@code work} inside a committed transaction; rolls back and rethrows on failure. */
    <T> T write(Function<EntityManager, T> work) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        EntityTransaction transaction = entityManager.getTransaction();
        try {
            transaction.begin();
            T result = work.apply(entityManager);
            transaction.commit();
            return result;
        } catch (RuntimeException e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            throw e;
        } finally {
            entityManager.close();
        }
    }

    /** Runs {@code work} against a plain (non-transactional) {@link EntityManager}. */
    <T> T read(Function<EntityManager, T> work) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            return work.apply(entityManager);
        } finally {
            entityManager.close();
        }
    }
}
