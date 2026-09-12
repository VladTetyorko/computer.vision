package com.drones.vision.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Response body for {@code GET /api/streams/{streamId}/cv/trace?last=N} (docs/plans/active/
 * CV-ORCHESTRATION-PLAN.md §4.4) — the warm trace tier's three ledgers side by side: the
 * <b>gate</b> (why a frame was or was not sent to cv-service), the <b>frame</b> (what cv-service's
 * contributors actually did on the frames it was asked to trace), and the <b>world</b> (the
 * platform's current fold over every contributor's claims — the same view {@code
 * StreamTracksResponse#objects} already exposes, repeated here so an inspector needs one request
 * rather than two).
 *
 * <p><b>Never errors.</b> An unknown or stopped stream reads as a {@code 200} with every list
 * empty, the same forgiving idiom {@code StreamController#tracks} already uses.
 *
 * @param streamId the queried stream, canonical UUID string; echoed back even when nothing is
 *                 running
 * @param gate     recent gate decisions, oldest first, coalesced by {@code FrameGateLedger}
 * @param frame    recent frame ledgers, oldest first — empty whenever tracing was never requested
 *                 for this stream, regardless of {@code last}
 * @param world    the current world-object fold (wave W2.8: {@link WorldObjectResponse}, carrying
 *                 the operator/event/render relations alongside the wire mirror, the same shape
 *                 {@code StreamTracksResponse#objects} uses) — not windowed by {@code last}, since
 *                 {@code WorldModel} holds one live fold rather than a history
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CvTraceResponse(String streamId, List<GateDecisionResponse> gate, List<FrameLedgerResponse> frame,
                               List<WorldObjectResponse> world) {

    public CvTraceResponse {
        gate = List.copyOf(gate);
        frame = List.copyOf(frame);
        world = List.copyOf(world);
    }
}
