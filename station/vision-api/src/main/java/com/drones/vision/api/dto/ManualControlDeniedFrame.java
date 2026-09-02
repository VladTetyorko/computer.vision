package com.drones.vision.api.dto;

import com.drones.vision.api.ws.ManualControlWebSocketHandler;

/**
 * Server&rarr;client {@code /ws/manual-control} frame refusing an {@code engage} request, or
 * flagging a malformed inbound frame (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md §4).
 *
 * @param type   always {@code "denied"}
 * @param code   one of the frozen codes {@code OUT_OF_SCOPE}/{@code NOT_COMMANDABLE}/{@code
 *               UNSUPPORTED}/{@code ALREADY_ENGAGED} for an {@code engage} refusal, plus the
 *               additive {@code VEHICLE_UNIDENTIFIED} (FLEET-RADIO R2 — an unrecognized/unsupported/
 *               not-a-vehicle link; the specific one of those three is in {@code reason}, not this
 *               field) and {@code INTERNAL_ERROR} (FLY-CONTROL-UX H1 — the handler's own catch-all:
 *               an exception {@code ManualControlService#engage} does not document; {@code reason}
 *               carries only a generic sentence plus the exception's simple class name, never its
 *               message) (see {@code ManualControlWebSocketHandler} for the exception&rarr;code
 *               mapping), or a handler-defensive code ({@code MALFORMED}/{@code UNKNOWN_TYPE}/{@code
 *               BAD_REQUEST}) for a frame the handler could not process at all — deliberately a
 *               plain string, not a closed enum on the wire, precisely so a new code like this one
 *               is additive: no frame removed, no wire break
 * @param reason a human-readable explanation, generally the triggering exception's message
 */
public record ManualControlDeniedFrame(String type, String code, String reason) {

    /** Convenience constructor: fills in the fixed {@code type} literal. */
    public ManualControlDeniedFrame(String code, String reason) {
        this("denied", code, reason);
    }
}
