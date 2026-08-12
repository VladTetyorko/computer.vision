package com.drones.vision.identity.application;

import com.drones.vision.kernel.GroupId;
import com.drones.vision.identity.domain.model.Membership;
import com.drones.vision.identity.domain.model.Role;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserSpecTest {

    private static Membership membership() {
        return new Membership(GroupId.random(), Role.PILOT);
    }

    @Test
    void rejectsBlankRawPassword() {
        assertThrows(IllegalArgumentException.class,
                () -> new UserSpec("pilot", "Pilot One", "pilot@example.com", " ", List.of(), true));
        assertThrows(IllegalArgumentException.class,
                () -> new UserSpec("pilot", "Pilot One", "pilot@example.com", null, List.of(), true));
    }

    @Test
    void rejectsNullMemberships() {
        assertThrows(IllegalArgumentException.class,
                () -> new UserSpec("pilot", "Pilot One", "pilot@example.com", "s3cret", null, true));
    }

    @Test
    void membershipsAreDefensivelyCopied() {
        List<Membership> mutable = new ArrayList<>(List.of(membership()));

        UserSpec spec = new UserSpec("pilot", "Pilot One", "pilot@example.com", "s3cret", mutable, true);
        mutable.add(membership());

        assertEquals(1, spec.memberships().size(), "later mutation of the source list must not affect the spec");
        assertThrows(UnsupportedOperationException.class, () -> spec.memberships().add(membership()),
                "returned memberships list must be immutable");
    }

    @Test
    void fiveArgConstructorDefaultsEnabledToTrue() {
        UserSpec spec = new UserSpec("pilot", "Pilot One", "pilot@example.com", "s3cret", List.of(membership()));

        assertTrue(spec.enabled());
    }

    @Test
    void fourArgConstructorDefaultsMembershipsEmptyAndEnabledTrue() {
        UserSpec spec = new UserSpec("pilot", "Pilot One", "pilot@example.com", "s3cret");

        assertTrue(spec.enabled());
        assertEquals(List.of(), spec.memberships());
    }
}
