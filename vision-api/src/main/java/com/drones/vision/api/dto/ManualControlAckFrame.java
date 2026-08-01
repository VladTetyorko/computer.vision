package com.drones.vision.api.dto;

/**
 * Server&rarr;client {@code /ws/manual-control} frame acknowledging one {@code channels} frame
 * (docs/RC-CONTROL-PHASE1-PLAN.md §4) — one {@code ack} per {@code channels} frame, echoing the
 * client's own {@code seq}/{@code tSent} plus the server's receive timestamp, so the client can
 * compute glass-to-stick round-trip latency as {@code now - tSent}.
 *
 * @param type    always {@code "ack"}
 * @param seq     echoed verbatim from the {@code channels} frame
 * @param tSent   echoed verbatim from the {@code channels} frame (client-side send timestamp, epoch millis)
 * @param tServer the server's receive timestamp (epoch millis), taken right before sending this ack
 */
public record ManualControlAckFrame(String type, long seq, long tSent, long tServer) {

    /** Convenience constructor: fills in the fixed {@code type} literal. */
    public ManualControlAckFrame(long seq, long tSent, long tServer) {
        this("ack", seq, tSent, tServer);
    }
}
