package com.drones.vision.perception.application.pipeline;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FrameGateLedger}'s own ring/coalescing behavior in isolation from {@link StreamPipeline} —
 * see {@link StreamPipelineGateLedgerTest} for the production wiring that actually calls {@link
 * FrameGateLedger#record}.
 */
class FrameGateLedgerTest {

    private static final Instant T0 = Instant.parse("2026-09-12T10:00:00Z");
    private static final DemandSnapshot DEMAND = new DemandSnapshot(true, true, false);

    private static GateDecision skipped(long frameSequence, GateReason reason) {
        return new GateDecision(frameSequence, T0.plusMillis(frameSequence), GateOutcome.SKIPPED, reason, DEMAND);
    }

    private static GateDecision sent(long frameSequence) {
        return new GateDecision(frameSequence, T0.plusMillis(frameSequence), GateOutcome.SENT, null, DEMAND);
    }

    @Test
    void rejectsNonPositiveDepth() {
        assertThrows(IllegalArgumentException.class, () -> new FrameGateLedger(0));
        assertThrows(IllegalArgumentException.class, () -> new FrameGateLedger(-1));
    }

    @Test
    void startsEmpty() {
        FrameGateLedger ledger = new FrameGateLedger(8);
        assertTrue(ledger.all().isEmpty());
        assertTrue(ledger.recent(5).isEmpty());
    }

    @Test
    void recordsOneEntry() {
        FrameGateLedger ledger = new FrameGateLedger(8);
        ledger.record(sent(1L));
        assertEquals(List.of(sent(1L)), ledger.all());
    }

    @Test
    void consecutiveIdenticalSkipReasonsCoalesceIntoOneEntry() {
        FrameGateLedger ledger = new FrameGateLedger(8);
        for (long i = 1; i <= 5; i++) {
            ledger.record(skipped(i, GateReason.DEADLINE_NOT_DUE));
        }
        List<GateDecision> all = ledger.all();
        assertEquals(1, all.size(), "five identical skip reasons in a row should coalesce to one entry");
        assertEquals(5L, all.get(0).frameSequence(), "the coalesced entry is refreshed to the latest frame");
    }

    @Test
    void aDifferentSkipReasonStartsAFreshEntry() {
        FrameGateLedger ledger = new FrameGateLedger(8);
        ledger.record(skipped(1L, GateReason.DEADLINE_NOT_DUE));
        ledger.record(skipped(2L, GateReason.DEADLINE_NOT_DUE));
        ledger.record(skipped(3L, GateReason.OUTAGE_BACKOFF));
        List<GateDecision> all = ledger.all();
        assertEquals(2, all.size());
        assertEquals(GateReason.DEADLINE_NOT_DUE, all.get(0).reason());
        assertEquals(GateReason.OUTAGE_BACKOFF, all.get(1).reason());
    }

    @Test
    void sentEntriesAreNeverCoalescedEvenWhenIdentical() {
        FrameGateLedger ledger = new FrameGateLedger(8);
        ledger.record(sent(1L));
        ledger.record(sent(2L));
        ledger.record(sent(3L));
        assertEquals(3, ledger.all().size(), "each SENT is real submitted work and must stay individually visible");
    }

    @Test
    void probeEntriesAreNeverCoalescedEvenWhenIdentical() {
        FrameGateLedger ledger = new FrameGateLedger(8);
        GateDecision probe1 = new GateDecision(1L, T0, GateOutcome.PROBE, null, DEMAND);
        GateDecision probe2 = new GateDecision(2L, T0.plusMillis(1), GateOutcome.PROBE, null, DEMAND);
        ledger.record(probe1);
        ledger.record(probe2);
        assertEquals(2, ledger.all().size());
    }

    @Test
    void evictsTheOldestNonCoalescedEntryOnceDepthIsExceeded() {
        FrameGateLedger ledger = new FrameGateLedger(3);
        ledger.record(sent(1L));
        ledger.record(sent(2L));
        ledger.record(sent(3L));
        ledger.record(sent(4L));
        List<GateDecision> all = ledger.all();
        assertEquals(3, all.size());
        assertEquals(List.of(2L, 3L, 4L), all.stream().map(GateDecision::frameSequence).toList());
    }

    @Test
    void coalescingNeverCountsAgainstDepth() {
        FrameGateLedger ledger = new FrameGateLedger(2);
        ledger.record(sent(1L));
        for (long i = 2; i <= 100; i++) {
            ledger.record(skipped(i, GateReason.GATE_OFF));
        }
        // one SENT entry + one coalesced GATE_OFF entry: never evicted by the 99 coalesced records
        List<GateDecision> all = ledger.all();
        assertEquals(2, all.size());
        assertEquals(GateOutcome.SENT, all.get(0).outcome());
        assertEquals(GateReason.GATE_OFF, all.get(1).reason());
        assertEquals(100L, all.get(1).frameSequence());
    }

    @Test
    void recentReturnsOnlyTheMostRecentEntriesOldestFirst() {
        FrameGateLedger ledger = new FrameGateLedger(8);
        ledger.record(sent(1L));
        ledger.record(sent(2L));
        ledger.record(sent(3L));
        List<GateDecision> recent = ledger.recent(2);
        assertEquals(List.of(2L, 3L), recent.stream().map(GateDecision::frameSequence).toList());
    }

    @Test
    void recentToleratesAskingForMoreThanIsHeld() {
        FrameGateLedger ledger = new FrameGateLedger(8);
        ledger.record(sent(1L));
        assertEquals(1, ledger.recent(50).size());
    }

    @Test
    void recentRejectsNegativeCount() {
        FrameGateLedger ledger = new FrameGateLedger(8);
        assertThrows(IllegalArgumentException.class, () -> ledger.recent(-1));
    }

    @Test
    void clearEmptiesTheRing() {
        FrameGateLedger ledger = new FrameGateLedger(8);
        ledger.record(sent(1L));
        ledger.record(skipped(2L, GateReason.GATE_OFF));
        ledger.clear();
        assertTrue(ledger.all().isEmpty());
    }

    @Test
    void rejectsNullDecision() {
        FrameGateLedger ledger = new FrameGateLedger(8);
        assertThrows(NullPointerException.class, () -> ledger.record(null));
    }
}
