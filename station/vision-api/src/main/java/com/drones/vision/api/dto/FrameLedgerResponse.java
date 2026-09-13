package com.drones.vision.api.dto;

import com.drones.vision.perception.domain.model.DetectorBox;
import com.drones.vision.perception.domain.model.FrameLedger;
import com.drones.vision.perception.domain.model.LedgerEntry;
import com.drones.vision.perception.domain.model.LedgerOutcome;
import com.drones.vision.perception.domain.model.ObjectEvidence;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Wire mirror of {@link FrameLedger} (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.4: the
 * "frame" half of {@code GET /api/streams/{id}/cv/trace}) — one frame's complete contributor
 * evidence, present only for a frame cv-service was actually asked to trace.
 *
 * @param streamId       the stream this frame belongs to, as a canonical UUID string
 * @param sequence       this session's own frame counter, from 0
 * @param capturedAtMillis capture timestamp of the source frame, epoch millis
 * @param levelServed    the capability ladder level this frame actually ran at
 * @param detectorReason the wire's {@code DetectorReason} value name, a plain string — see {@link
 *                       FrameLedger}'s own javadoc for why this is never the domain enum
 * @param eligible       contributor families the budget allowed this frame
 * @param entries        one row per contributor that ran or was considered, in run order
 * @param objects        track id (as a string, since a JSON object key can only be a string) to
 *                       every claim made about it this frame
 * @param dropsSinceLast frames the mailbox dropped before this one (push mode)
 * @param gateWaitMillis wall time inside the detector: queueing plus inference
 * @param totalMillis    the frame's total cost
 * @param halted         whether a contributor stopped the frame
 * @param detections     the detector's own raw boxes this frame, before association — empty when
 *                       not carried (CV-ORCHESTRATION wave W5b, decision E23; see {@link DetectorBox})
 * @param frameWidth     this frame's pixel width alongside {@code detections}; {@code 0} when not
 *                       carried
 * @param frameHeight    this frame's pixel height alongside {@code detections}; {@code 0} when not
 *                       carried
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FrameLedgerResponse(String streamId, long sequence, long capturedAtMillis, int levelServed,
                                   String detectorReason, List<String> eligible, List<LedgerEntryResponse> entries,
                                   Map<String, List<ObjectEvidenceResponse>> objects, int dropsSinceLast,
                                   double gateWaitMillis, double totalMillis, boolean halted,
                                   List<DetectorBoxResponse> detections, int frameWidth, int frameHeight) {

    /**
     * Maps a domain {@link FrameLedger} to its wire representation.
     *
     * @param ledger the ledger to map
     * @return the response body for {@code ledger}
     */
    public static FrameLedgerResponse from(FrameLedger ledger) {
        Map<String, List<ObjectEvidenceResponse>> objects = ledger.objects().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(entry -> Long.toString(entry.getKey()),
                        entry -> entry.getValue().stream().map(ObjectEvidenceResponse::from).toList(),
                        (a, b) -> a, java.util.LinkedHashMap::new));
        return new FrameLedgerResponse(ledger.streamId().value().toString(), ledger.sequence(),
                ledger.capturedAt().toEpochMilli(), ledger.levelServed(), ledger.detectorReason(),
                ledger.eligible(), ledger.entries().stream().map(LedgerEntryResponse::from).toList(), objects,
                ledger.dropsSinceLast(), ledger.gateWaitMillis(), ledger.totalMillis(), ledger.halted(),
                ledger.detections().stream().map(DetectorBoxResponse::from).toList(), ledger.frameWidth(),
                ledger.frameHeight());
    }

    /**
     * Wire mirror of {@link LedgerEntry} — one contributor's row on one frame.
     *
     * @param contributorId who ran
     * @param outcome       what happened to this contributor this frame
     * @param reason        why it was skipped, or the failure message; empty string, never absent,
     *                      when no reason was given (mirrors {@link LedgerEntry#reason()}'s own
     *                      non-null contract)
     * @param costMillis    this contributor's own cost for the frame
     * @param summary       free-form facts this contributor chose to record
     */
    public record LedgerEntryResponse(String contributorId, LedgerOutcome outcome, String reason, double costMillis,
                                       Map<String, String> summary) {
        public static LedgerEntryResponse from(LedgerEntry entry) {
            return new LedgerEntryResponse(entry.contributorId(), entry.outcome(), entry.reason(),
                    entry.costMillis(), entry.summary());
        }
    }

    /**
     * Wire mirror of {@link ObjectEvidence} — one claim one contributor made about one object.
     *
     * @param contributorId who made the claim
     * @param claim         the claim itself, free-form
     */
    public record ObjectEvidenceResponse(String contributorId, Map<String, String> claim) {
        public static ObjectEvidenceResponse from(ObjectEvidence evidence) {
            return new ObjectEvidenceResponse(evidence.contributorId(), evidence.claim());
        }
    }

    /**
     * Wire mirror of {@link DetectorBox} (CV-ORCHESTRATION wave W5b, decision E23) — one of the
     * detector's own raw boxes for a traced frame, before association. Reuses {@link
     * BoundingBoxResponse}, the same box shape {@link DetectionResponse} already uses, rather than a
     * second box DTO.
     *
     * @param label      class label
     * @param confidence detection confidence, range [0,1]
     * @param box        normalized bounding box
     */
    public record DetectorBoxResponse(String label, double confidence, BoundingBoxResponse box) {
        public static DetectorBoxResponse from(DetectorBox detectorBox) {
            return new DetectorBoxResponse(detectorBox.label(), detectorBox.confidence(),
                    BoundingBoxResponse.from(detectorBox.box()));
        }
    }
}
