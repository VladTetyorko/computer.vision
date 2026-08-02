package com.drones.vision.api.dto;

import com.drones.vision.api.ws.ManualControlWebSocketHandler;

/**
 * Server&rarr;client {@code /ws/manual-control} frame confirming the relay session ended
 * (docs/RC-CONTROL-PHASE1-PLAN.md §4).
 *
 * @param type   always {@code "released"}
 * @param reason {@code "EXPLICIT"} (the client sent a {@code release} frame) or {@code
 *               "SOCKET_CLOSE"} (best-effort only — a closed socket usually can't actually receive
 *               this frame; {@code ManualControlWebSocketHandler#afterConnectionClosed} releases
 *               the session but never attempts to send it)
 */
public record ManualControlReleasedFrame(String type, String reason) {

    /** The two frozen reason values (docs/RC-CONTROL-PHASE1-PLAN.md §4). */
    public static final String REASON_EXPLICIT = "EXPLICIT";
    public static final String REASON_SOCKET_CLOSE = "SOCKET_CLOSE";

    /** Convenience constructor: fills in the fixed {@code type} literal. */
    public ManualControlReleasedFrame(String reason) {
        this("released", reason);
    }
}
