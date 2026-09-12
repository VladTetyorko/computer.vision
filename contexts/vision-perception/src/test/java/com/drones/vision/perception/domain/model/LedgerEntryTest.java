package com.drones.vision.perception.domain.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LedgerEntryTest {

    @Test
    void rejectsBlankContributorId() {
        assertThrows(IllegalArgumentException.class,
                () -> new LedgerEntry(null, LedgerOutcome.RAN, "", 1.0, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new LedgerEntry("  ", LedgerOutcome.RAN, "", 1.0, Map.of()));
    }

    @Test
    void rejectsNullOutcome() {
        assertThrows(IllegalArgumentException.class,
                () -> new LedgerEntry("detect.full", null, "", 1.0, Map.of()));
    }

    @Test
    void rejectsNullReason() {
        assertThrows(IllegalArgumentException.class,
                () -> new LedgerEntry("detect.full", LedgerOutcome.RAN, null, 1.0, Map.of()));
    }

    @Test
    void allowsEmptyReason() {
        LedgerEntry entry = new LedgerEntry("detect.full", LedgerOutcome.RAN, "", 1.0, Map.of());

        assertEquals("", entry.reason());
    }

    @Test
    void rejectsNonFiniteOrNegativeCostMillis() {
        assertThrows(IllegalArgumentException.class,
                () -> new LedgerEntry("detect.full", LedgerOutcome.RAN, "", Double.NaN, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new LedgerEntry("detect.full", LedgerOutcome.RAN, "", Double.POSITIVE_INFINITY, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new LedgerEntry("detect.full", LedgerOutcome.RAN, "", -1.0, Map.of()));
    }

    @Test
    void rejectsNullSummary() {
        assertThrows(IllegalArgumentException.class,
                () -> new LedgerEntry("detect.full", LedgerOutcome.RAN, "", 1.0, null));
    }

    @Test
    void summaryIsDefensivelyCopiedAndImmutable() {
        Map<String, String> summary = new HashMap<>();
        summary.put("k", "v");

        LedgerEntry entry = new LedgerEntry("detect.full", LedgerOutcome.RAN, "", 1.0, summary);
        summary.put("k2", "v2");

        assertEquals(1, entry.summary().size(), "later mutation of the source map must not affect the entry");
        assertThrows(UnsupportedOperationException.class, () -> entry.summary().put("k3", "v3"),
                "returned summary map must be immutable");
    }
}
