package com.drones.vision.api.dto;

import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.application.pipeline.DemandSnapshot;
import com.drones.vision.perception.application.pipeline.GateDecision;
import com.drones.vision.perception.application.pipeline.GateOutcome;
import com.drones.vision.perception.application.pipeline.GateReason;
import com.drones.vision.perception.domain.model.DetectionEventId;
import com.drones.vision.perception.domain.model.FollowState;
import com.drones.vision.perception.domain.model.FrameLedger;
import com.drones.vision.perception.domain.model.LedgerEntry;
import com.drones.vision.perception.domain.model.LedgerOutcome;
import com.drones.vision.perception.domain.model.ObjectEvidence;
import com.drones.vision.perception.domain.model.ObjectLifecycle;
import com.drones.vision.perception.domain.model.ObjectState;
import com.drones.vision.perception.domain.model.RenderTier;
import com.drones.vision.perception.domain.model.WorldObject;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The TypeScript-mirror acceptance test for CV-ORCHESTRATION wave W5.0 (the engineer inspector's
 * wire contract, {@code GET /api/streams/{streamId}/cv/trace}): {@link CvTraceResponse} serialized
 * and checked byte-for-byte (as a parsed JSON tree, so key order is irrelevant) against the
 * fixture committed at {@code
 * station/vision-web/src/app/core/api/__fixtures__/cv-trace.wire.json}. A vitest spec ({@code
 * station/vision-web/src/app/core/api/cv-trace.wire.contract.spec.ts}) loads the same fixture and
 * proves it satisfies the frontend's {@code CvTrace}/{@code GateDecision}/{@code FrameLedger}/
 * {@code LedgerEntry}/{@code ObjectEvidence} TypeScript types, key-for-key — same idiom as {@code
 * WorldObjectResponseWireContractTest}.
 *
 * <p>This test builds the domain records directly, the same as {@code
 * WorldObjectResponseWireContractTest}: {@link CvTraceResponse} is assembled in {@code
 * StreamController} from three already-mirrored pieces ({@link GateDecisionResponse}, {@link
 * FrameLedgerResponse}, {@link WorldObjectResponse}), so there is no codec to round-trip through
 * — proving each piece's {@code .from} mapping once, on a full and a minimal example, covers the
 * whole envelope.
 *
 * <p>The {@code full} example exercises every {@link GateReason} value (seven skip reasons, one
 * per gate entry) plus one {@code SENT} and one {@code PROBE} decision, one {@link FrameLedger}
 * with a {@code RAN}/{@code SKIPPED}/{@code FAILED} {@link LedgerEntry} triad and one {@link
 * ObjectEvidence} claim, and one {@link WorldObject}. The {@code minimal} example is the
 * never-traced-this-stream shape: every list empty, per {@link CvTraceResponse}'s own "never
 * errors" contract.
 *
 * <p>A bare {@code JsonMapper} is not dodging Spring's real Jackson config: {@code
 * @JsonInclude(NON_NULL)} is an annotation on the record itself, not a mapper-level toggle Spring
 * applies afterward, so {@code new JsonMapper()} here serializes byte-identically to whatever
 * {@code JsonMapper} bean the running application wires up (same idiom as {@code
 * WorldObjectResponseWireContractTest}).
 */
class CvTraceResponseWireContractTest {

    private static final JsonMapper JSON = new JsonMapper();

    /**
     * {@code vision-web} is a sibling module of {@code vision-api} under {@code station/}, and
     * Surefire's working directory for a forked test JVM defaults to the module's own {@code
     * basedir} -- so this relative path reaches the committed fixture without any build-time
     * resource copy between modules.
     */
    private static final Path FIXTURE_PATH = Path.of(
            "..", "vision-web", "src", "app", "core", "api", "__fixtures__", "cv-trace.wire.json");

    /**
     * Deliberately NOT a sibling of {@link #FIXTURE_PATH} — a sibling would land inside {@code
     * vision-web}'s tracked source tree, where nothing ignores it, so a failing run could leave an
     * untracked file a later {@code git add -A} could commit. {@code target/} is this module's own
     * untracked build-output directory, safe for exactly this kind of scratch artifact; {@code cp}
     * regeneration still works off the absolute path the failure message prints.
     */
    private static final Path ACTUAL_PATH = Path.of("target", "cv-trace.wire.actual.json");

    /** Fixed, not {@link StreamId#random()}: the fixture is a committed file and must be produced
     *  identically on every run. */
    private static final StreamId STREAM_ID = StreamId.of("dddddddd-dddd-dddd-dddd-dddddddddddd");

    /** Fixed epoch instant so every timestamp in the fixture is deterministic. */
    private static final Instant AT = Instant.ofEpochMilli(1_700_000_000_000L);

    @Test
    void cvTraceResponseMatchesTheCommittedFixture() throws IOException {
        CvTraceResponse full = new CvTraceResponse(STREAM_ID.value().toString(), fullGateDecisions(),
                List.of(FrameLedgerResponse.from(fullFrameLedger())), List.of(WorldObjectResponse.from(fullWorld())));
        // The never-traced-this-stream shape (CvTraceResponse's own "never errors" contract): every
        // list empty, not absent -- @JsonInclude(NON_NULL) applies to the record's own scalar/object
        // fields, never silently drops a populated List type to a missing key.
        CvTraceResponse minimal = new CvTraceResponse(STREAM_ID.value().toString(), List.of(), List.of(), List.of());

        String fullJson = JSON.writeValueAsString(full);
        String minimalJson = JSON.writeValueAsString(minimal);
        String actualJson = "{\"full\":" + fullJson + ",\"minimal\":" + minimalJson + "}";

        JsonNode actual = JSON.readTree(actualJson);
        JsonNode expected = JSON.readTree(Files.readString(FIXTURE_PATH, StandardCharsets.UTF_8));

        // Compared as parsed JSON trees (order-independent) so a harmless field-ordering difference
        // never fails this test -- only an actual content difference does. On a mismatch we do NOT
        // overwrite the fixture; we write the produced JSON to ACTUAL_PATH so regenerating is still
        // one `cp` away.
        if (!expected.equals(actual)) {
            Files.writeString(ACTUAL_PATH, actualJson, StandardCharsets.UTF_8);
            fail("Produced JSON does not match the committed fixture " + FIXTURE_PATH.toAbsolutePath()
                    + " -- wrote the actual output to " + ACTUAL_PATH.toAbsolutePath()
                    + ". If this difference is the intended new shape, regenerate with "
                    + "`cp " + ACTUAL_PATH + " " + FIXTURE_PATH + "` and re-run.\nexpected: " + expected
                    + "\nactual:   " + actual);
        }
    }

    /**
     * One {@link GateDecision} per {@link GateReason} value (all seven, in declaration order) plus
     * one {@code SENT} and one {@code PROBE} -- the frontend's {@code GATE_REASONS}/{@code
     * GATE_OUTCOMES} pinned tuples (W5.1) must cover exactly this set.
     */
    private static List<GateDecisionResponse> fullGateDecisions() {
        DemandSnapshot demandOn = new DemandSnapshot(true, true, false);
        DemandSnapshot demandOff = new DemandSnapshot(false, false, false);
        long seq = 0;
        List<GateDecisionResponse> decisions = new java.util.ArrayList<>();
        decisions.add(GateDecisionResponse.from(
                new GateDecision(seq++, AT, GateOutcome.SENT, null, demandOn)));
        decisions.add(GateDecisionResponse.from(
                new GateDecision(seq++, AT, GateOutcome.PROBE, null, demandOn)));
        for (GateReason reason : GateReason.values()) {
            decisions.add(GateDecisionResponse.from(
                    new GateDecision(seq++, AT, GateOutcome.SKIPPED, reason, demandOff)));
        }
        return List.copyOf(decisions);
    }

    /**
     * One {@link FrameLedger} exercising all three {@link LedgerOutcome} values and one {@link
     * ObjectEvidence} claim, mirroring {@code predict.cv}'s real "held" box key ({@code
     * cv/cv-service/cv_service/orchestration/contributors/predict.py}) so the fixture's evidence
     * shape matches what cv-service actually emits.
     */
    private static FrameLedger fullFrameLedger() {
        List<LedgerEntry> entries = List.of(
                new LedgerEntry("detect.full", LedgerOutcome.RAN, "", 12.5, Map.of("detections", "2")),
                new LedgerEntry("assoc.cost", LedgerOutcome.SKIPPED, "no detections", 0.0, Map.of()),
                new LedgerEntry("predict.cv", LedgerOutcome.FAILED, "RuntimeException: model unavailable", 3.1,
                        Map.of()));
        Map<Long, List<ObjectEvidence>> objects = new LinkedHashMap<>();
        objects.put(7L, List.of(new ObjectEvidence("predict.cv",
                Map.of("predicted", "0.10,0.20,0.30,0.40", "held", "0.11,0.21,0.29,0.39",
                        "velocity", "0.01,-0.02"))));
        return new FrameLedger(STREAM_ID, 42L, AT, 2, "FULL", List.of("detect", "assoc", "predict"), entries,
                objects, 1, 8.2, 24.6, false);
    }

    private static WorldObject fullWorld() {
        return new WorldObject(minimalState(7L), new WorldObject.Operator(true, false, FollowState.HOLDING),
                new WorldObject.EventLink(DetectionEventId.of("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")),
                new WorldObject.Render(RenderTier.T1));
    }

    private static ObjectState minimalState(long id) {
        return new ObjectState(id, ObjectLifecycle.CONFIRMED, STREAM_ID, null, null, null, null, null, null, null);
    }
}
