package com.drones.vision.identity.domain.model;

import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserTest {

    private static User user(String username, String email, String passwordHash, List<Membership> memberships) {
        return new User(UserId.random(), username, "Ada Lovelace", email, passwordHash, true, memberships);
    }

    private static User user(List<Membership> memberships) {
        return user("ada", "ada@example.com", "hashed-secret", memberships);
    }

    @Test
    void rejectsNullId() {
        assertThrows(IllegalArgumentException.class,
                () -> new User(null, "ada", "Ada", "ada@example.com", "hash", true, List.of()));
    }

    @Test
    void rejectsNullUsername() {
        assertThrows(IllegalArgumentException.class,
                () -> user(null, "ada@example.com", "hash", List.of()));
    }

    @Test
    void rejectsBlankUsername() {
        assertThrows(IllegalArgumentException.class,
                () -> user("   ", "ada@example.com", "hash", List.of()));
    }

    @Test
    void rejectsNullDisplayName() {
        assertThrows(IllegalArgumentException.class,
                () -> new User(UserId.random(), "ada", null, "ada@example.com", "hash", true, List.of()));
    }

    @Test
    void rejectsBlankDisplayName() {
        assertThrows(IllegalArgumentException.class,
                () -> new User(UserId.random(), "ada", " ", "ada@example.com", "hash", true, List.of()));
    }

    @Test
    void rejectsNullEmail() {
        assertThrows(IllegalArgumentException.class,
                () -> user("ada", null, "hash", List.of()));
    }

    @Test
    void rejectsBlankEmail() {
        assertThrows(IllegalArgumentException.class,
                () -> user("ada", "   ", "hash", List.of()));
    }

    @Test
    void rejectsEmailMissingAtSign() {
        assertThrows(IllegalArgumentException.class,
                () -> user("ada", "ada-example.com", "hash", List.of()));
    }

    @Test
    void rejectsNullPasswordHash() {
        assertThrows(IllegalArgumentException.class,
                () -> user("ada", "ada@example.com", null, List.of()));
    }

    @Test
    void rejectsBlankPasswordHash() {
        assertThrows(IllegalArgumentException.class,
                () -> user("ada", "ada@example.com", "   ", List.of()));
    }

    @Test
    void rejectsNullMemberships() {
        assertThrows(IllegalArgumentException.class,
                () -> new User(UserId.random(), "ada", "Ada", "ada@example.com", "hash", true, null));
    }

    @Test
    void normalizesUsernameToLowerCase() {
        User user = user("AdaLovelace", "ada@example.com", "hash", List.of());

        assertEquals("adalovelace", user.username());
    }

    @Test
    void trimsAndNormalizesUsername() {
        User user = user("  AdaLovelace  ", "ada@example.com", "hash", List.of());

        assertEquals("adalovelace", user.username());
    }

    @Test
    void membershipsAreDefensivelyCopied() {
        List<Membership> memberships = new ArrayList<>();
        memberships.add(new Membership(GroupId.random(), Role.PILOT));

        User user = user(memberships);
        memberships.add(new Membership(GroupId.random(), Role.ADMIN));

        assertEquals(1, user.memberships().size(), "later mutation of the source list must not affect the user");
        assertThrows(UnsupportedOperationException.class,
                () -> user.memberships().add(new Membership(GroupId.random(), Role.MANAGER)),
                "returned memberships list must be immutable");
    }

    @Test
    void emptyMembershipsAreAllowed() {
        User user = user(List.of());

        assertTrue(user.memberships().isEmpty());
    }

    @Test
    void convenienceConstructorDefaultsToEmptyMemberships() {
        User user = new User(UserId.random(), "ada", "Ada", "ada@example.com", "hash", true);

        assertTrue(user.memberships().isEmpty());
    }

    @Test
    void topRoleIsEmptyWithNoMemberships() {
        User user = user(List.of());

        assertEquals(Optional.empty(), user.topRole());
    }

    @Test
    void topRoleReturnsTheOnlyMembershipsRole() {
        User user = user(List.of(new Membership(GroupId.random(), Role.MANAGER)));

        assertEquals(Optional.of(Role.MANAGER), user.topRole());
    }

    @Test
    void topRoleReturnsTheHighestPrivilegeRegardlessOfInsertionOrder() {
        User user = user(List.of(
                new Membership(GroupId.random(), Role.PILOT),
                new Membership(GroupId.random(), Role.ADMIN),
                new Membership(GroupId.random(), Role.MANAGER)));

        assertEquals(Optional.of(Role.ADMIN), user.topRole());
    }

    @Test
    void topRoleReturnsAdminEvenWhenAdminIsInsertedFirst() {
        User user = user(List.of(
                new Membership(GroupId.random(), Role.ADMIN),
                new Membership(GroupId.random(), Role.PILOT)));

        assertEquals(Optional.of(Role.ADMIN), user.topRole());
    }

    @Test
    void toStringRedactsPasswordHash() {
        String distinctiveHash = "super-secret-hash-value-12345";

        User user = user("ada", "ada@example.com", distinctiveHash, List.of());

        String rendered = user.toString();

        assertFalse(rendered.contains(distinctiveHash), "toString must not leak the password hash");
        assertTrue(rendered.contains("ada"), "toString should still include the username");
        assertTrue(rendered.contains("Ada Lovelace"), "toString should still include the display name");
        assertTrue(rendered.contains("ada@example.com"), "toString should still include the email");
        assertTrue(rendered.contains("passwordHash=***"), "toString should show a redaction placeholder");
    }
}
