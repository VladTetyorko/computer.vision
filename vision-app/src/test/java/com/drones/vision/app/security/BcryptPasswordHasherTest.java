package com.drones.vision.app.security;

import com.drones.vision.domain.port.out.PasswordHasherPort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The BCrypt {@link PasswordHasherPort} (docs/U-AUTH-PLAN.md, wave 3): hash/verify round trip. */
class BcryptPasswordHasherTest {

    private final PasswordHasherPort hasher = new BcryptPasswordHasher();

    @Test
    void hashThenVerifyRoundTrips() {
        String hash = hasher.hash("s3cret-pw");
        assertTrue(hasher.verify("s3cret-pw", hash));
        assertFalse(hasher.verify("wrong-pw", hash));
    }

    @Test
    void hashIsSaltedSoTheSamePasswordHashesDifferentlyEachTime() {
        assertNotEquals(hasher.hash("same"), hasher.hash("same"));
    }

    @Test
    void blankPasswordCannotBeHashed() {
        assertThrows(IllegalArgumentException.class, () -> hasher.hash("  "));
    }

    @Test
    void verifyOfNullRawPasswordIsFalseNotAnError() {
        String hash = hasher.hash("pw");
        assertFalse(hasher.verify(null, hash));
    }
}
