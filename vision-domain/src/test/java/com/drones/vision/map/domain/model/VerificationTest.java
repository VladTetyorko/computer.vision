package com.drones.vision.map.domain.model;

import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VerificationTest {

    @Test
    void rejectsNullState() {
        assertThrows(IllegalArgumentException.class,
                () -> new Verification(null, UserId.random(), Instant.now()));
    }

    @Test
    void unverifiedAllowsNullVerifiedByAndVerifiedAt() {
        Verification verification = new Verification(Verification.VerificationState.UNVERIFIED, null, null);

        assertEquals(Verification.VerificationState.UNVERIFIED, verification.state());
        assertNull(verification.verifiedBy());
        assertNull(verification.verifiedAt());
    }

    @Test
    void confirmedRequiresVerifiedBy() {
        assertThrows(IllegalArgumentException.class,
                () -> new Verification(Verification.VerificationState.CONFIRMED, null, Instant.now()));
    }

    @Test
    void confirmedRequiresVerifiedAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new Verification(Verification.VerificationState.CONFIRMED, UserId.random(), null));
    }

    @Test
    void rejectedRequiresVerifiedBy() {
        assertThrows(IllegalArgumentException.class,
                () -> new Verification(Verification.VerificationState.REJECTED, null, Instant.now()));
    }

    @Test
    void rejectedRequiresVerifiedAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new Verification(Verification.VerificationState.REJECTED, UserId.random(), null));
    }

    @Test
    void acceptsAWellFormedConfirmedVerification() {
        UserId verifiedBy = UserId.random();
        Instant verifiedAt = Instant.now();

        Verification verification = new Verification(Verification.VerificationState.CONFIRMED, verifiedBy, verifiedAt);

        assertEquals(Verification.VerificationState.CONFIRMED, verification.state());
        assertEquals(verifiedBy, verification.verifiedBy());
        assertEquals(verifiedAt, verification.verifiedAt());
    }

    @Test
    void acceptsAWellFormedRejectedVerification() {
        UserId verifiedBy = UserId.random();
        Instant verifiedAt = Instant.now();

        Verification verification = new Verification(Verification.VerificationState.REJECTED, verifiedBy, verifiedAt);

        assertEquals(Verification.VerificationState.REJECTED, verification.state());
    }

    @Test
    void unverifiedFactoryReturnsUnverifiedWithNoReviewer() {
        Verification verification = Verification.unverified();

        assertEquals(Verification.VerificationState.UNVERIFIED, verification.state());
        assertNull(verification.verifiedBy());
        assertNull(verification.verifiedAt());
    }
}
