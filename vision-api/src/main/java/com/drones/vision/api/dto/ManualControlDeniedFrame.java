package com.drones.vision.api.dto;

import com.drones.vision.api.ws.ManualControlWebSocketHandler;

/**
 * Server&rarr;client {@code /ws/manual-control} frame refusing an {@code engage} request, or
 * flagging a malformed inbound frame (docs/RC-CONTROL-PHASE1-PLAN.md §4).
 *
 * @param type   always {@code "denied"}
 * @param code   one of the frozen codes {@code OUT_OF_SCOPE}/{@code NOT_COMMANDABLE}/{@code
 *               UNSUPPORTED}/{@code ALREADY_ENGAGED} for an {@code engage} refusal (see {@code
 *               ManualControlWebSocketHandler} for the exception&rarr;code mapping), or a
 *               handler-defensive code ({@code MALFORMED}/{@code UNKNOWN_TYPE}/{@code
 *               BAD_REQUEST}) for a frame the handler could not process at all — deliberately a
 *               plain string, not a closed enum on the wire, since the second group is not part
 *               of the frozen §4 contract
 * @param reason a human-readable explanation, generally the triggering exception's message
 */
public record ManualControlDeniedFrame(String type, String code, String reason) {

    /** Convenience constructor: fills in the fixed {@code type} literal. */
    public ManualControlDeniedFrame(String code, String reason) {
        this("denied", code, reason);
    }
}
