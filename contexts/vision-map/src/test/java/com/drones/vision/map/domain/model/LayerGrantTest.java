package com.drones.vision.map.domain.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LayerGrantTest {

    @Test
    void rejectsNullSubjectType() {
        assertThrows(IllegalArgumentException.class,
                () -> new LayerGrant(null, UUID.randomUUID(), AccessLevel.VIEW));
    }

    @Test
    void rejectsNullSubjectId() {
        assertThrows(IllegalArgumentException.class,
                () -> new LayerGrant(LayerGrant.SubjectType.USER, null, AccessLevel.VIEW));
    }

    @Test
    void rejectsNullLevel() {
        assertThrows(IllegalArgumentException.class,
                () -> new LayerGrant(LayerGrant.SubjectType.USER, UUID.randomUUID(), null));
    }

    @Test
    void acceptsAWellFormedGrant() {
        UUID subjectId = UUID.randomUUID();

        LayerGrant grant = new LayerGrant(LayerGrant.SubjectType.GROUP, subjectId, AccessLevel.MANAGE);

        assertEquals(LayerGrant.SubjectType.GROUP, grant.subjectType());
        assertEquals(subjectId, grant.subjectId());
        assertEquals(AccessLevel.MANAGE, grant.level());
    }
}
