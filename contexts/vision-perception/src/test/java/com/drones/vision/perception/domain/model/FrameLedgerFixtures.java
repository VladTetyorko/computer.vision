package com.drones.vision.perception.domain.model;

import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.kernel.StreamId;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Shared {@link FrameLedger} fixtures for this wave's tests.
 *
 * <p>{@link #everyFieldDistinct()} is deliberately reused beyond {@code FrameLedgerTest} — a
 * later step of docs/plans/active/CV-ORCHESTRATION-PLAN.md wave W2 round-trips this exact
 * instance through a proto encode/decode test, the same convention {@link
 * ObjectStateFixtures#everyFieldDistinct()} already established for {@link ObjectState}. Every
 * numeric leaf value in the whole tree — including the two {@link LedgerEntry#costMillis()}
 * values and the two object-map track-id keys — is pairwise distinct, so a codec that swapped two
 * same-typed fields fails that round trip instead of passing by coincidence. Treat this class's
 * and method's signature as a stable cross-wave API, not just test scaffolding.
 */
public final class FrameLedgerFixtures {

    private FrameLedgerFixtures() {
    }

    public static FrameLedger everyFieldDistinct() {
        LedgerEntry entryOne = new LedgerEntry("detect.full", LedgerOutcome.RAN, "", 11.1,
                Map.of("model", "yolo26n"));
        LedgerEntry entryTwo = new LedgerEntry("assoc.cost", LedgerOutcome.SKIPPED, "budget exhausted", 22.2,
                Map.of("terms", "iou+appearance"));

        ObjectEvidence evidenceOneA = new ObjectEvidence("assoc.cost", Map.of("iou", "0.51"));
        ObjectEvidence evidenceOneB = new ObjectEvidence("memory.gallery", Map.of("distance", "0.12"));
        ObjectEvidence evidenceTwoA = new ObjectEvidence("assoc.cost", Map.of("iou", "0.62"));
        ObjectEvidence evidenceTwoB = new ObjectEvidence("memory.gallery", Map.of("distance", "0.23"));

        // CV-ORCHESTRATION wave W5b: the detector's own raw boxes, distinct from every box any
        // other fixture in this tree uses, so a codec bug that swapped these in for `entries`'/
        // `objects`' boxes would fail a round trip rather than pass by coincidence.
        DetectorBox detectionOne = new DetectorBox("car", 0.71, new BoundingBox(0.05, 0.06, 0.17, 0.18));
        DetectorBox detectionTwo = new DetectorBox("person", 0.82, new BoundingBox(0.25, 0.26, 0.27, 0.28));

        return new FrameLedger(
                StreamId.random(),
                100L,
                Instant.ofEpochMilli(900_000L),
                3,
                "CADENCE",
                List.of("detect", "assoc"),
                List.of(entryOne, entryTwo),
                Map.of(
                        301L, List.of(evidenceOneA, evidenceOneB),
                        302L, List.of(evidenceTwoA, evidenceTwoB)),
                4,
                5.5,
                6.6,
                true,
                List.of(detectionOne, detectionTwo),
                1920,
                1080);
    }
}
