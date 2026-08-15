package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.UserEntity;
import com.drones.vision.adapter.persistence.mapper.UserMapper;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.kernel.UserId;
import com.drones.vision.identity.domain.port.UserRepositoryPort;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.NoResultException;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@link UserRepositoryPort} backed by Postgres via plain JPA (see {@link JpaOperations}) —
 * docs/plans/done/U-AUTH-PLAN.md, wave 3.
 *
 * <p>{@link #save} is an upsert (merge-by-id), matching {@code InMemoryUserRepository}'s ({@code
 * vision-app} devsupport) {@code Map#put}. {@link #findByUsername} lower-cases its lookup key
 * ({@code Locale.ROOT}) before an exact-match query — the domain {@code User} already stores the
 * username lower-cased, so this is the case-insensitive match the port promises; the {@code UNIQUE}
 * constraint on {@code users.username} enforces the actual uniqueness the port delegates to
 * implementations. Memberships ride on the row as jsonb (see {@link UserEntity}).
 */
public final class JpaUserRepository implements UserRepositoryPort {

    private final JpaOperations jpa;

    public JpaUserRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public Optional<User> findByUsername(String username) {
        if (username == null) {
            return Optional.empty();
        }
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        return jpa.read(em -> {
            try {
                return Optional.of(em.createQuery(
                                "select u from UserEntity u where u.username = :username", UserEntity.class)
                        .setParameter("username", normalized)
                        .getSingleResult());
            } catch (NoResultException e) {
                return Optional.<UserEntity>empty();
            }
        }).map(UserMapper::toDomain);
    }

    @Override
    public Optional<User> findById(UserId id) {
        return jpa.read(em -> Optional.ofNullable(em.find(UserEntity.class, id.value())))
                .map(UserMapper::toDomain);
    }

    @Override
    public User save(User user) {
        UserEntity saved = jpa.write(em -> em.merge(UserMapper.toEntity(user)));
        return UserMapper.toDomain(saved);
    }

    @Override
    public List<User> findAll() {
        return jpa.read(em -> em.createQuery("select u from UserEntity u", UserEntity.class).getResultList())
                .stream()
                .map(UserMapper::toDomain)
                .toList();
    }
}
