package com.drones.vision.adapter.persistence.config;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;

import java.util.Objects;
import java.util.function.Function;

/**
 * Opens one short-lived {@link EntityManager} per call and runs the given unit of work against
 * it — the transaction-boilerplate shared by every {@code Jpa*Repository} in {@code
 * com.drones.vision.adapter.persistence.repository}, so each of those classes stays a
 * one-constructor-argument wrapper around {@link EntityManagerFactory} plus its own entity/domain
 * mapping (delegated to {@code com.drones.vision.adapter.persistence.mapper}).
 *
 * <p>Public rather than package-private (docs/LAYERING-REFACTOR-PLAN.md §3/§7 row C): every
 * {@code Jpa*Repository} composing this class now lives in the sibling {@code repository}
 * package, so cross-package visibility is required — the alternative (leaving it package-private
 * and the repositories in the same flat package) is exactly the shape this refactor replaces.
 *
 * <p>Not a pooled/request-scoped {@code EntityManager} (there is no request or Spring
 * transaction context here to scope it to) — every {@link #write}/{@link #read} call is its own
 * connection acquire/release. Fine at this platform's scale; see {@code PersistenceUnit}'s
 * javadoc for the same tradeoff at the connection-provider level.
 */
public final class JpaOperations {

    private final EntityManagerFactory entityManagerFactory;

    public JpaOperations(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = Objects.requireNonNull(entityManagerFactory,
                "entityManagerFactory must not be null");
    }

    /** Runs {@code work} inside a committed transaction; rolls back and rethrows on failure. */
    public <T> T write(Function<EntityManager, T> work) {
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
    public <T> T read(Function<EntityManager, T> work) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            return work.apply(entityManager);
        } finally {
            entityManager.close();
        }
    }
}
