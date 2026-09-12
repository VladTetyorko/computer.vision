package com.drones.vision.api.dto;

import com.drones.vision.perception.application.pipeline.DemandSnapshot;
import com.drones.vision.perception.application.pipeline.GateDecision;
import com.drones.vision.perception.application.pipeline.GateOutcome;
import com.drones.vision.perception.application.pipeline.GateReason;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Wire mirror of {@link GateDecision} (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.4: the "gate"
 * half of {@code GET /api/streams/{id}/cv/trace}) — one sampler deadline's worth of gating,
 * consecutive same-{@code reason} {@code SKIPPED} runs already coalesced by {@code
 * FrameGateLedger} before this class ever sees them.
 *
 * @param frameSequence the frame sequence this deadline evaluated against
 * @param atMillis      when this decision was made, epoch millis
 * @param outcome       what happened
 * @param reason        why the frame was skipped, or absent when {@code outcome} is not {@link
 *                       GateOutcome#SKIPPED} — {@code @JsonInclude(NON_NULL)}, the same "absent,
 *                       never a dummy value" idiom {@code ObjectStateResponse} uses
 * @param demand        the demand facts behind this decision
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GateDecisionResponse(long frameSequence, long atMillis, GateOutcome outcome, GateReason reason,
                                    DemandSnapshotResponse demand) {

    /**
     * Maps a domain {@link GateDecision} to its wire representation.
     *
     * @param decision the decision to map
     * @return the response body for {@code decision}
     */
    public static GateDecisionResponse from(GateDecision decision) {
        return new GateDecisionResponse(decision.frameSequence(), decision.at().toEpochMilli(), decision.outcome(),
                decision.reason(), DemandSnapshotResponse.from(decision.demand()));
    }

    /**
     * Wire mirror of {@link DemandSnapshot} — the demand facts this module owns at the moment one
     * gate decision was made. See {@link DemandSnapshot}'s own javadoc for the known limitation:
     * the {@code sse | pose | poll} breakdown of {@code viewerDemand} is not obtainable here.
     *
     * @param detectionEnabled the operator's own switch
     * @param viewerDemand     whether anything was asking for detection at all
     * @param policyAlwaysOn   whether this asset had opted into {@code DetectionPolicy.ALWAYS}
     */
    public record DemandSnapshotResponse(boolean detectionEnabled, boolean viewerDemand, boolean policyAlwaysOn) {
        public static DemandSnapshotResponse from(DemandSnapshot demand) {
            return new DemandSnapshotResponse(demand.detectionEnabled(), demand.viewerDemand(),
                    demand.policyAlwaysOn());
        }
    }
}
