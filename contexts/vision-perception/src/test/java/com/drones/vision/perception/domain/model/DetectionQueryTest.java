package com.drones.vision.perception.domain.model;

import com.drones.vision.kernel.StreamId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DetectionQueryTest {

    @Test
    void rejectsANonPositiveLimit() {
        assertThrows(IllegalArgumentException.class, () -> new DetectionQuery(null, null, null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new DetectionQuery(null, null, null, null, -1));
    }

    @Test
    void everyFilterIsOptionalBecauseNullMeansDoNotFilterOnThis() {
        DetectionQuery query = new DetectionQuery(null, null, null, null, 100);

        assertNull(query.streamId());
        assertNull(query.from());
        assertNull(query.to());
        assertNull(query.label());
    }

    @Test
    void acceptsAFullySpecifiedQuery() {
        DetectionQuery query = new DetectionQuery(StreamId.random(), Instant.parse("2026-07-22T00:00:00Z"),
                Instant.parse("2026-07-23T00:00:00Z"), "person", 50);

        assertEquals("person", query.label());
        assertEquals(50, query.limit());
    }
}
