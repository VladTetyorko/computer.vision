package com.drones.vision.domain.model;

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
        return new Mark(MarkId.random(), POSITION, MarkKind.TARGET, "Bunker", "spotted at dusk",
                ownership(), Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL);
    }

    // --- Validation ------------------------------------------------------------

    @Test
    void rejectsNullId() {
        assertThrows(IllegalArgumentException.class,
                () -> new Mark(null, POSITION, MarkKind.TARGET, "label", null,
                        ownership(), Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL));
    }

    @Test
    void rejectsNullPosition() {
        assertThrows(IllegalArgumentException.class,
                () -> new Mark(MarkId.random(), null, MarkKind.TARGET, "label", null,
                        ownership(), Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL));
    }

    @Test
    void rejectsNullKind() {
        assertThrows(IllegalArgumentException.class,
                () -> new Mark(MarkId.random(), POSITION, null, "label", null,
                        ownership(), Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL));
    }

    @Test
    void rejectsBlankLabel() {
        assertThrows(IllegalArgumentException.class,
                () -> new Mark(MarkId.random(), POSITION, MarkKind.TARGET, "", null,
                        ownership(), Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL));
        assertThrows(IllegalArgumentException.class,
                () -> new Mark(MarkId.random(), POSITION, MarkKind.TARGET, "   ", null,
                        ownership(), Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL));
        assertThrows(IllegalArgumentException.class,
                () -> new Mark(MarkId.random(), POSITION, MarkKind.TARGET, null, null,
                        ownership(), Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL));
    }

    @Test
    void acceptsNullNote() {
        Mark mark = new Mark(MarkId.random(), POSITION, MarkKind.TARGET, "label", null,
                ownership(), Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL);

        assertNull(mark.note());
    }

    @Test
    void blankNoteNormalizesToNull() {
        Mark mark = new Mark(MarkId.random(), POSITION, MarkKind.TARGET, "label", "   ",
                ownership(), Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL);

        assertNull(mark.note());
    }

    @Test
    void rejectsNullOwnership() {
        assertThrows(IllegalArgumentException.class,
                () -> new Mark(MarkId.random(), POSITION, MarkKind.TARGET, "label", null,
                        null, Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL));
    }

    @Test
    void rejectsNullCreatedAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new Mark(MarkId.random(), POSITION, MarkKind.TARGET, "label", null,
                        ownership(), null, MarkStatus.ACTIVE, MarkSource.MANUAL));
    }

    @Test
    void rejectsNullStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> new Mark(MarkId.random(), POSITION, MarkKind.TARGET, "label", null,
                        ownership(), Instant.now(), null, MarkSource.MANUAL));
    }

    @Test
    void rejectsNullSource() {
        assertThrows(IllegalArgumentException.class,
                () -> new Mark(MarkId.random(), POSITION, MarkKind.TARGET, "label", null,
                        ownership(), Instant.now(), MarkStatus.ACTIVE, null));
    }

    // --- createdBy() -------------------------------------------------------------

    @Test
    void createdByDerivesFromOwnershipOwnerId() {
        Ownership ownership = ownership();
        Mark mark = new Mark(MarkId.random(), POSITION, MarkKind.TARGET, "label", null,
                ownership, Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL);

        assertEquals(ownership.ownerId(), mark.createdBy());
    }

    // --- withDetails ---------------------------------------------------------------

    @Test
    void withDetailsReturnsNewInstancePreservingIdentityOwnershipCreatedAtAndStatus() {
        Mark original = mark();
        GeoPosition newPosition = new GeoPosition(1.0, 2.0, 3.0);

        Mark updated = original.withDetails(MarkKind.HAZARD, "renamed", "new note", newPosition);

        assertEquals(MarkKind.HAZARD, updated.kind());
        assertEquals("renamed", updated.label());
        assertEquals("new note", updated.note());
        assertEquals(newPosition, updated.position());
        assertEquals(original.id(), updated.id(), "identity must be preserved");
        assertEquals(original.ownership(), updated.ownership(), "ownership must be preserved");
        assertEquals(original.createdAt(), updated.createdAt(), "createdAt must be preserved");
        assertEquals(original.status(), updated.status(), "status must be preserved");
        assertEquals(original.source(), updated.source(), "source must be preserved");
        assertEquals(MarkKind.TARGET, original.kind(), "original instance must be unchanged");
    }

    @Test
    void withDetailsValidatesReplacementFields() {
        Mark original = mark();

        assertThrows(IllegalArgumentException.class,
                () -> original.withDetails(MarkKind.HAZARD, "", "note", POSITION));
        assertThrows(IllegalArgumentException.class,
                () -> original.withDetails(MarkKind.HAZARD, "label", "note", null));
    }

    // --- withStatus ------------------------------------------------------------------

    @Test
    void withStatusReturnsNewInstanceLeavingOtherFieldsUnchanged() {
        Mark original = mark();

        Mark cleared = original.withStatus(MarkStatus.CLEARED);

        assertEquals(MarkStatus.CLEARED, cleared.status());
        assertEquals(MarkStatus.ACTIVE, original.status(), "original instance must be unchanged");
        assertEquals(original.id(), cleared.id());
        assertEquals(original.position(), cleared.position());
        assertEquals(original.kind(), cleared.kind());
        assertEquals(original.label(), cleared.label());
        assertEquals(original.note(), cleared.note());
        assertEquals(original.ownership(), cleared.ownership());
        assertEquals(original.createdAt(), cleared.createdAt());
        assertEquals(original.source(), cleared.source());
    }
}
