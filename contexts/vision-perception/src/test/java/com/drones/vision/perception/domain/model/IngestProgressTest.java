package com.drones.vision.perception.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;

class IngestProgressTest {

    private static final RegionBounds BOUNDS = new RegionBounds(50.4020, 50.3860, 30.6400, 30.6120);
    private static final ReferenceIndexSummary SUMMARY = new ReferenceIndexSummary("kyiv-pozniaky", "Poznyaky",
            BOUNDS, 17, Instant.parse("2026-08-20T09:12:03Z"), 312, 312, 512, "eigenplaces_r18_512", 0.62, 0.03,
            0.55, 43.0, 12_000_000L, 4);

    @Test
    void nonTerminalStatesRequireNullSummary() {
        new IngestProgress("kyiv-pozniaky", "encoding", 140, 312, IngestState.RUNNING, "", null);
        new IngestProgress("kyiv-pozniaky", "encoding", 140, 312, IngestState.FAILED, "boom", null);

        assertThrows(IllegalArgumentException.class,
                () -> new IngestProgress("kyiv-pozniaky", "encoding", 140, 312, IngestState.RUNNING, "", SUMMARY));
        assertThrows(IllegalArgumentException.class,
                () -> new IngestProgress("kyiv-pozniaky", "encoding", 140, 312, IngestState.FAILED, "boom", SUMMARY));
    }

    @Test
    void succeededAcceptsEitherNullOrPresentSummary() {
        new IngestProgress("kyiv-pozniaky", "done", 312, 312, IngestState.SUCCEEDED, "", SUMMARY);
        new IngestProgress("kyiv-pozniaky", "done", 312, 312, IngestState.SUCCEEDED, "", null);
    }

    @Test
    void rejectsBlankRegionIdAndPhaseAndNullMessage() {
        assertThrows(IllegalArgumentException.class,
                () -> new IngestProgress("", "encoding", 0, 0, IngestState.RUNNING, "", null));
        assertThrows(IllegalArgumentException.class,
                () -> new IngestProgress("kyiv-pozniaky", "", 0, 0, IngestState.RUNNING, "", null));
        assertThrows(IllegalArgumentException.class,
                () -> new IngestProgress("kyiv-pozniaky", "encoding", 0, 0, IngestState.RUNNING, null, null));
    }

    @Test
    void rejectsNegativeDoneOrTotal() {
        assertThrows(IllegalArgumentException.class,
                () -> new IngestProgress("kyiv-pozniaky", "encoding", -1, 0, IngestState.RUNNING, "", null));
        assertThrows(IllegalArgumentException.class,
                () -> new IngestProgress("kyiv-pozniaky", "encoding", 0, -1, IngestState.RUNNING, "", null));
    }
}
