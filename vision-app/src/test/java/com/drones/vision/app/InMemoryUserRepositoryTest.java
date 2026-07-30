package com.drones.vision.app;

import com.drones.vision.app.devsupport.InMemoryUserRepository;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.Membership;
import com.drones.vision.domain.model.Role;
import com.drones.vision.domain.model.User;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.UserRepositoryPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link InMemoryUserRepository}: case-insensitive lookup + upsert (docs/U-AUTH-PLAN.md, wave 3). */
class InMemoryUserRepositoryTest {

    private final UserRepositoryPort repository = new InMemoryUserRepository();

    @Test
    void findByUsernameIsCaseInsensitiveAgainstTheNormalizedStoredValue() {
        User user = new User(UserId.random(), "Alice", "Alice", "alice@vision.local", "hash", true, List.of());
        repository.save(user);

        assertTrue(repository.findByUsername("alice").isPresent());
        assertTrue(repository.findByUsername("ALICE").isPresent());
        assertTrue(repository.findByUsername("  Alice  ").isPresent());
        assertEquals(user.id(), repository.findByUsername("alice").orElseThrow().id());
    }

    @Test
    void unknownLookupsReturnEmpty() {
        assertTrue(repository.findByUsername("ghost").isEmpty());
        assertTrue(repository.findByUsername(null).isEmpty());
        assertTrue(repository.findById(UserId.random()).isEmpty());
    }

    @Test
    void saveUpsertsById() {
        UserId id = UserId.random();
        repository.save(new User(id, "bob", "Bob", "bob@vision.local", "h1", true, List.of()));
        repository.save(new User(id, "bob", "Bobby", "bob@vision.local", "h2", false,
                List.of(new Membership(GroupId.random(), Role.ADMIN))));

        User found = repository.findById(id).orElseThrow();
        assertEquals("Bobby", found.displayName());
        assertEquals("h2", found.passwordHash());
        assertFalse(found.enabled());
        assertEquals(1, found.memberships().size());
        assertEquals(1, repository.findAll().size());
    }
}
