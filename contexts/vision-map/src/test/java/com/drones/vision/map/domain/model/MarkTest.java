package com.drones.vision.map.domain.model;

import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarkTest {

    private static final GeoPosition POSITION = new GeoPosition(50.45, 30.52, null);

    private static Ownership ownership() {
        return new Ownership(UserId.random(), GroupId.random());
    }

    private static Mark mark() {
        return new Mark(MarkId.random(), LayerId.random(), POSITION, MarkKind.TARGET, Affiliation.HOSTILE,
                "Bunker", "spotted at dusk", ownership(), Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL,
                Verification.unverified());
    }

    // --- Validation ------------------------------------------------------------

    @Test
    void rejectsNullId() {
        assertThrows(IllegalArgumentException.class,
                () -> new Mark(null, LayerId.random(), POSITION, MarkKind.TARGET, Affiliation.HOSTILE, "label",
                        null, ownership(), Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL,
                        Verification.unverified()));
    }

    @Test
    void rejectsNullLayerId() {
        assertThrows(IllegalArgumentException.class,
                () -> new Mark(MarkId.random(), null, POSITION, MarkKind.TARGET, Affiliation.HOSTILE, "label",
                        null, ownership(), Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL,
                        Verification.unverified()));
    }

    @Test
    void rejectsNullPosition() {
        assertThrows(IllegalArgumentException.class,
                () -> new Mark(MarkId.random(), LayerId.random(), null, MarkKind.TARGET, Affiliation.HOSTILE,
                        "label", null, ownership(), Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL,
                        Verification.unverified()));
    }

    @Test
    void rejectsNullKind() {
        assertThrows(IllegalArgumentException.class,
                () -> new Mark(MarkId.random(), LayerId.random(), POSITION, null, Affiliation.HOSTILE, "label",
                        null, ownership(), Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL,
                        Verification.unverified()));
    }

    @Test
    void rejectsNullAffiliation() {
        assertThrows(IllegalArgumentException.class,
                () -> new Mark(MarkId.random(), LayerId.random(), POSITION, MarkKind.TARGET, null, "label",
                        null, ownership(), Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL,
                        Verification.unverified()));
    }

    @Test
    void rejectsBlankLabel() {
        assertThrows(IllegalArgumentException.class,
                () -> new Mark(MarkId.random(), LayerId.random(), POSITION, MarkKind.TARGET, Affiliation.HOSTILE,
                        "", null, ownership(), Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL,
                        Verification.unverified()));
        assertThrows(IllegalArgumentException.class,
                () -> new Mark(MarkId.random(), LayerId.random(), POSITION, MarkKind.TARGET, Affiliation.HOSTILE,
                        "   ", null, ownership(), Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL,
                        Verification.unverified()));
        assertThrows(IllegalArgumentException.class,
                () -> new Mark(MarkId.random(), LayerId.random(), POSITION, MarkKind.TARGET, Affiliation.HOSTILE,
                        null, null, ownership(), Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL,
                        Verification.unverified()));
    }

    @Test
    void acceptsNullNote() {
        Mark mark = new Mark(MarkId.random(), LayerId.random(), POSITION, MarkKind.TARGET, Affiliation.HOSTILE,
                "label", null, ownership(), Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL,
                Verification.unverified());

        assertNull(mark.note());
    }

    @Test
    void blankNoteNormalizesToNull() {
        Mark mark = new Mark(MarkId.random(), LayerId.random(), POSITION, MarkKind.TARGET, Affiliation.HOSTILE,
                "label", "   ", ownership(), Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL,
                Verification.unverified());

        assertNull(mark.note());
    }

    @Test
    void rejectsNullOwnership() {
        assertThrows(IllegalArgumentException.class,
                () -> new Mark(MarkId.random(), LayerId.random(), POSITION, MarkKind.TARGET, Affiliation.HOSTILE,
                        "label", null, null, Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL,
                        Verification.unverified()));
    }

    @Test
    void rejectsNullCreatedAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new Mark(MarkId.random(), LayerId.random(), POSITION, MarkKind.TARGET, Affiliation.HOSTILE,
                        "label", null, ownership(), null, MarkStatus.ACTIVE, MarkSource.MANUAL,
                        Verification.unverified()));
    }

    @Test
    void rejectsNullStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> new Mark(MarkId.random(), LayerId.random(), POSITION, MarkKind.TARGET, Affiliation.HOSTILE,
                        "label", null, ownership(), Instant.now(), null, MarkSource.MANUAL,
                        Verification.unverified()));
    }

    @Test
    void rejectsNullSource() {
        assertThrows(IllegalArgumentException.class,
                () -> new Mark(MarkId.random(), LayerId.random(), POSITION, MarkKind.TARGET, Affiliation.HOSTILE,
                        "label", null, ownership(), Instant.now(), MarkStatus.ACTIVE, null,
                        Verification.unverified()));
    }

    @Test
    void rejectsNullVerification() {
        assertThrows(IllegalArgumentException.class,
                () -> new Mark(MarkId.random(), LayerId.random(), POSITION, MarkKind.TARGET, Affiliation.HOSTILE,
                        "label", null, ownership(), Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL, null));
    }

    // --- createdBy() -------------------------------------------------------------

    @Test
    void createdByDerivesFromOwnershipOwnerId() {
        Ownership ownership = ownership();
        Mark mark = new Mark(MarkId.random(), LayerId.random(), POSITION, MarkKind.TARGET, Affiliation.HOSTILE,
                "label", null, ownership, Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL,
                Verification.unverified());

        assertEquals(ownership.ownerId(), mark.createdBy());
    }

    // --- withPosition ------------------------------------------------------------

    @Test
    void withPositionReturnsNewInstancePreservingEverythingElse() {
        Mark original = mark();
        GeoPosition newPosition = new GeoPosition(1.0, 2.0, 3.0);

        Mark updated = original.withPosition(newPosition);

        assertEquals(newPosition, updated.position());
        assertEquals(original.id(), updated.id());
        assertEquals(original.layerId(), updated.layerId());
        assertEquals(original.kind(), updated.kind());
        assertEquals(original.affiliation(), updated.affiliation());
        assertEquals(original.label(), updated.label());
        assertEquals(original.note(), updated.note());
        assertEquals(original.ownership(), updated.ownership());
        assertEquals(original.createdAt(), updated.createdAt());
        assertEquals(original.status(), updated.status());
        assertEquals(original.source(), updated.source());
        assertEquals(original.verification(), updated.verification());
        assertEquals(POSITION, original.position(), "original instance must be unchanged");
    }

    @Test
    void withPositionValidatesReplacementPosition() {
        Mark original = mark();

        assertThrows(IllegalArgumentException.class, () -> original.withPosition(null));
    }

    // --- withDetails ---------------------------------------------------------------

    @Test
    void withDetailsReturnsNewInstancePreservingIdentityLayerPositionOwnershipCreatedAtStatusSourceVerification() {
        Mark original = mark();

        Mark updated = original.withDetails("renamed", "new note", MarkKind.HAZARD, Affiliation.NEUTRAL);

        assertEquals("renamed", updated.label());
        assertEquals("new note", updated.note());
        assertEquals(MarkKind.HAZARD, updated.kind());
        assertEquals(Affiliation.NEUTRAL, updated.affiliation());
        assertEquals(original.id(), updated.id(), "identity must be preserved");
        assertEquals(original.layerId(), updated.layerId(), "layer must be preserved");
        assertEquals(original.position(), updated.position(), "position must be preserved");
        assertEquals(original.ownership(), updated.ownership(), "ownership must be preserved");
        assertEquals(original.createdAt(), updated.createdAt(), "createdAt must be preserved");
        assertEquals(original.status(), updated.status(), "status must be preserved");
        assertEquals(original.source(), updated.source(), "source must be preserved");
        assertEquals(original.verification(), updated.verification(), "verification must be preserved");
        assertEquals(MarkKind.TARGET, original.kind(), "original instance must be unchanged");
    }

    @Test
    void withDetailsValidatesReplacementFields() {
        Mark original = mark();

        assertThrows(IllegalArgumentException.class,
                () -> original.withDetails("", "note", MarkKind.HAZARD, Affiliation.NEUTRAL));
        assertThrows(IllegalArgumentException.class,
                () -> original.withDetails("label", "note", null, Affiliation.NEUTRAL));
        assertThrows(IllegalArgumentException.class,
                () -> original.withDetails("label", "note", MarkKind.HAZARD, null));
    }

    // --- withStatus ------------------------------------------------------------------

    @Test
    void withStatusReturnsNewInstanceLeavingOtherFieldsUnchanged() {
        Mark original = mark();

        Mark cleared = original.withStatus(MarkStatus.CLEARED);

        assertEquals(MarkStatus.CLEARED, cleared.status());
        assertEquals(MarkStatus.ACTIVE, original.status(), "original instance must be unchanged");
        assertEquals(original.id(), cleared.id());
        assertEquals(original.layerId(), cleared.layerId());
        assertEquals(original.position(), cleared.position());
        assertEquals(original.kind(), cleared.kind());
        assertEquals(original.affiliation(), cleared.affiliation());
        assertEquals(original.label(), cleared.label());
        assertEquals(original.note(), cleared.note());
        assertEquals(original.ownership(), cleared.ownership());
        assertEquals(original.createdAt(), cleared.createdAt());
        assertEquals(original.source(), cleared.source());
        assertEquals(original.verification(), cleared.verification());
    }

    // --- withVerification --------------------------------------------------------------

    @Test
    void withVerificationReturnsNewInstanceLeavingOtherFieldsUnchanged() {
        Mark original = mark();
        Verification confirmed = new Verification(Verification.VerificationState.CONFIRMED, UserId.random(),
                Instant.now());

        Mark updated = original.withVerification(confirmed);

        assertEquals(confirmed, updated.verification());
        assertEquals(Verification.unverified(), original.verification(), "original instance must be unchanged");
        assertEquals(original.id(), updated.id());
        assertEquals(original.layerId(), updated.layerId());
        assertEquals(original.status(), updated.status());
    }

    // --- withLayer (promotion) -----------------------------------------------------------

    @Test
    void withLayerReturnsNewInstanceLeavingOtherFieldsUnchanged() {
        Mark original = mark();
        LayerId copLayerId = LayerId.random();

        Mark promoted = original.withLayer(copLayerId);

        assertEquals(copLayerId, promoted.layerId());
        assertEquals(original.id(), promoted.id());
        assertEquals(original.position(), promoted.position());
        assertEquals(original.kind(), promoted.kind());
        assertEquals(original.affiliation(), promoted.affiliation());
        assertEquals(original.label(), promoted.label());
        assertEquals(original.note(), promoted.note());
        assertEquals(original.ownership(), promoted.ownership());
        assertEquals(original.createdAt(), promoted.createdAt());
        assertEquals(original.status(), promoted.status());
        assertEquals(original.source(), promoted.source());
        assertEquals(original.verification(), promoted.verification());
    }

    @Test
    void withLayerValidatesReplacementLayerId() {
        Mark original = mark();

        assertThrows(IllegalArgumentException.class, () -> original.withLayer(null));
    }
}
