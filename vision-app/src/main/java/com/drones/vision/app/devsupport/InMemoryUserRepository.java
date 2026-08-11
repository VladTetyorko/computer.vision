package com.drones.vision.app.devsupport;

import com.drones.vision.domain.model.User;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.UserRepositoryPort;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link UserRepositoryPort}: dev fallback with no durability across restarts
 * (docs/plans/done/U-AUTH-PLAN.md, wave 3).
 *
 * <p>Replaced by {@code adapter-persistence}'s {@code JpaUserRepository} when {@code
 * vision.persistence.enabled=true}. {@link #findByUsername} lower-cases its lookup key ({@code
 * Locale.ROOT}) before matching — every {@link User} already stores its username lower-cased (the
 * domain normalizes it), so an exact match against the stored value <em>is</em> the
 * case-insensitive lookup the port contract promises.
 */
public final class InMemoryUserRepository implements UserRepositoryPort {

    private final Map<UserId, User> users = new ConcurrentHashMap<>();

    @Override
    public Optional<User> findByUsername(String username) {
        if (username == null) {
            return Optional.empty();
        }
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        return users.values().stream()
                .filter(user -> user.username().equals(normalized))
                .findFirst();
    }

    @Override
    public Optional<User> findById(UserId id) {
        return Optional.ofNullable(users.get(id));
    }

    @Override
    public User save(User user) {
        users.put(user.id(), user);
        return user;
    }

    @Override
    public List<User> findAll() {
        return List.copyOf(users.values());
    }
}
