package com.drones.vision.perception.application.pipeline;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GateDecisionTest {

    private static final Instant AT = Instant.now();
    private static final DemandSnapshot DEMAND = new DemandSnapshot(true, false, false);

    @Test
    void rejectsNegativeFrameSequence() {
        assertThrows(IllegalArgumentException.class,
                () -> new GateDecision(-1L, AT, GateOutcome.SENT, null, DEMAND));
    }

    @Test
    void rejectsNullAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new GateDecision(0L, null, GateOutcome.SENT, null, DEMAND));
    }

    @Test
    void rejectsNullOutcome() {
        assertThrows(IllegalArgumentException.class,
                () -> new GateDecision(0L, AT, null, null, DEMAND));
    }

    @Test
    void rejectsNullDemand() {
        assertThrows(IllegalArgumentException.class,
                () -> new GateDecision(0L, AT, GateOutcome.SENT, null, null));
    }

    @Test
    void skippedWithNoReasonIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new GateDecision(0L, AT, GateOutcome.SKIPPED, null, DEMAND));
    }

    @Test
    void sentWithReasonIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new GateDecision(0L, AT, GateOutcome.SENT, GateReason.GATE_OFF, DEMAND));
    }

    @Test
    void probeWithReasonIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new GateDecision(0L, AT, GateOutcome.PROBE, GateReason.GATE_OFF, DEMAND));
    }

    @Test
    void skippedWithReasonIsAccepted() {
        assertDoesNotThrow(() -> new GateDecision(0L, AT, GateOutcome.SKIPPED, GateReason.GATE_NO_DEMAND, DEMAND));
    }

    @Test
    void sentWithNoReasonIsAccepted() {
        assertDoesNotThrow(() -> new GateDecision(0L, AT, GateOutcome.SENT, null, DEMAND));
    }

    @Test
    void probeWithNoReasonIsAccepted() {
        assertDoesNotThrow(() -> new GateDecision(0L, AT, GateOutcome.PROBE, null, DEMAND));
    }
}
