package com.drones.vision.platform;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Guards the one property {@link PlatformActor#USER_ID} exists for: an unattended action must never
 * be audited under an id that some deployment also hands to a real person.
 *
 * <p>Worth a test rather than a comment because the failure is silent and remote — the constant is
 * declared in this module, the colliding rows are seeded by SQL in {@code storage/persistence}, and
 * nothing in a compiler or a wiring test connects the two. A first version of this constant did
 * collide, taking {@code UUID(0, 2)} on the reasoning that it was "the next free slot after
 * DevPrincipal" — which is exactly the id {@code db/seed/dev/V90001__dev_accounts.sql} gives the dev
 * manager account.
 */
class PlatformActorTest {

    @Test
    void theUnattendedActorIsNotInTheNamespaceWhereWellKnownAndSeededIdsLive() {
        assertNotEquals(0L, PlatformActor.USER_ID.value().getMostSignificantBits(),
                "every well-known id in this repo — DevPrincipal's user/group, LayerResolver's system "
                        + "principal, and the dev seed's manager/pilot accounts — is a UUID(0, n). An "
                        + "unattended actor inside that range is one seed away from naming a real person.");
    }

    @Test
    void theActorIdIsStableAcrossReleases() {
        assertEquals(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), PlatformActor.USER_ID.value(),
                "audit rows already written name this id; changing it orphans every historical "
                        + "unattended entry rather than renaming it");
    }
}
