package com.drones.vision.perception.domain.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObjectEvidenceTest {

    @Test
    void rejectsBlankContributorId() {
        assertThrows(IllegalArgumentException.class, () -> new ObjectEvidence(null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ObjectEvidence("  ", Map.of()));
    }

    @Test
    void rejectsNullClaim() {
        assertThrows(IllegalArgumentException.class, () -> new ObjectEvidence("assoc.cost", null));
    }

    @Test
    void claimIsDefensivelyCopiedAndImmutable() {
        Map<String, String> claim = new HashMap<>();
        claim.put("iou", "0.7");

        ObjectEvidence evidence = new ObjectEvidence("assoc.cost", claim);
        claim.put("distance", "0.3");

        assertEquals(1, evidence.claim().size(), "later mutation of the source map must not affect the evidence");
        assertThrows(UnsupportedOperationException.class, () -> evidence.claim().put("k", "v"),
                "returned claim map must be immutable");
    }
}
