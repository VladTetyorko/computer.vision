package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.api.dto.ObjectStateResponse;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.ObjectState;
import com.drones.vision.proto.v1.DetectionResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Headline acceptance test for CV-ORCHESTRATION wave W1 "wire mirror"
 * (docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.5): a round trip proto {@code ObjectState}
 * &rarr; domain {@link ObjectState} &rarr; {@link ObjectStateResponse} JSON, checked byte-for-byte
 * (as a parsed JSON tree, so key order is irrelevant) against the fixture committed at {@code
 * station/vision-web/src/app/core/api/__fixtures__/object-state.wire.json}. A vitest spec
 * ({@code station/vision-web/src/app/core/api/object-state.wire.contract.spec.ts}) loads the same
 * fixture and proves it satisfies the frontend's {@code ObjectState} TypeScript type, key-for-key —
 * together the two specs are the one place a Java field rename that the TS interface missed (or
 * vice versa) actually fails a build.
 *
 * <h2>Why this test lives in {@code vision-app}, in {@code adapter-cv-grpc}'s own package</h2>
 * {@link DetectionFrameCodec#decode} — the wire&harr;domain codec under test, the thing that
 * actually maps {@code DetectionResponse.objects[]} onto {@link ObjectState} — is package-private
 * ({@code final class}, package {@code com.drones.vision.adapter.cvgrpc}). Widening its visibility
 * would be a real API-surface change (out of this wave's scope, and against the module's own
 * "no gRPC call machinery or wire types escape this package" posture); reaching it via reflection
 * would be worse — brittle, and dishonest about what "package-private" means here. {@code
 * vision-app} is the only module in the reactor that depends on both {@code adapter-cv-grpc}
 * (compile scope, for the runtime codec) and {@code vision-api} (compile scope, for {@link
 * ObjectStateResponse}) — see this module's own MODULE.md. So this test class is declared in the
 * codec's own package, {@code com.drones.vision.adapter.cvgrpc}, under {@code vision-app}'s test
 * source root. This repository builds on the plain classpath, not the Java module path (no {@code
 * module-info.java} anywhere in the reactor), so package-private access here works exactly as it
 * would from inside {@code cv/grpc} itself — same package name, same classloader, no reflection.
 *
 * <h2>A bare {@code JsonMapper} is not dodging Spring's real Jackson config</h2>
 * {@code @JsonInclude(NON_NULL)} on {@link ObjectStateResponse} and every nested record is an
 * annotation <em>on the record itself</em>, not a mapper-level {@code SerializationConfig} toggle
 * Spring applies afterward — so {@code new JsonMapper()} here serializes these types
 * byte-identically to whatever {@code JsonMapper} bean the running application wires up. Same
 * idiom as {@code OnboardingWireContractTest} (vision-api).
 */
class ObjectStateRoundTripTest {

    private static final JsonMapper JSON = new JsonMapper();

    /**
     * {@code vision-web} is a sibling module of {@code vision-app} under {@code station/}, and
     * Surefire's working directory for a forked test JVM defaults to the module's own {@code
     * basedir} — so this relative path reaches the committed fixture without any build-time
     * resource copy between modules.
     */
    private static final Path FIXTURE_PATH = Path.of(
            "..", "vision-web", "src", "app", "core", "api", "__fixtures__", "object-state.wire.json");

    private static final Path ACTUAL_PATH = FIXTURE_PATH.resolveSibling("object-state.wire.actual.json");

    /** Fixed, not {@link StreamId#random()}: the fixture is a committed file and must be produced
     *  identically on every run. */
    private static final StreamId STREAM_ID = StreamId.of("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test
    void objectStateRoundTripsProtoToJavaToJsonMatchingTheCommittedFixture() throws IOException {
        DetectionResponse response = DetectionResponse.newBuilder()
                .setStreamId("response-level-stream-id-never-read-by-decode")
                .setSequence(1L)
                .setTimestampMillis(1_700_000_000_000L)
                .setModelId("test-model")
                .setModelVersion("v1")
                .addObjects(fullWireObjectState())
                .addObjects(minimalWireObjectState())
                .build();

        DetectionResult result = DetectionFrameCodec.decode(STREAM_ID, response);
        List<ObjectState> objects = result.objects();
        assertEquals(2, objects.size(),
                "both wire ObjectStates must survive decode (neither is malformed): " + objects);

        String fullJson = JSON.writeValueAsString(ObjectStateResponse.from(objects.get(0)));
        String minimalJson = JSON.writeValueAsString(ObjectStateResponse.from(objects.get(1)));
        String actualJson = "{\"full\":" + fullJson + ",\"minimal\":" + minimalJson + "}";

        JsonNode actual = JSON.readTree(actualJson);
        JsonNode expected = JSON.readTree(Files.readString(FIXTURE_PATH, StandardCharsets.UTF_8));

        // Compared as parsed JSON trees (JsonNode#equals on an ObjectNode compares its backing
        // Map, which is order-independent) so a harmless field-ordering difference never fails
        // this test -- only an actual content difference does. On a mismatch we do NOT overwrite
        // the fixture; we write the produced JSON beside it so regenerating is one `cp` away.
        if (!expected.equals(actual)) {
            Files.writeString(ACTUAL_PATH, actualJson, StandardCharsets.UTF_8);
            fail("Produced JSON does not match the committed fixture " + FIXTURE_PATH.toAbsolutePath()
                    + " -- wrote the actual output to " + ACTUAL_PATH.toAbsolutePath()
                    + ". If this difference is the intended new shape, regenerate with "
                    + "`cp " + ACTUAL_PATH + " " + FIXTURE_PATH + "` and re-run.\nexpected: " + expected
                    + "\nactual:   " + actual);
        }

        // (f) the minimal object's JSON must carry none of the seven optional group keys -- an
        // absent group is a missing key, never a zeroed/null placeholder (the honesty rule this
        // whole wave exists to prove, ObjectState's own class javadoc).
        JsonNode minimalNode = actual.get("minimal");
        for (String group : List.of("identity", "kinematics", "belief", "provenance", "memory", "lock", "timing")) {
            assertFalse(minimalNode.has(group),
                    "minimal object must have no '" + group + "' key: " + minimalNode);
        }
    }

    /**
     * Every field of every group set to a distinct value -- no two numbers equal anywhere in this
     * message, so a mapper that crosses two fields (e.g. swaps {@code belief.existence} for {@code
     * memory.matchDistance}, both {@code [0,1]}-ranged) fails this test. All seven optional groups
     * are present, all four {@code Kinematics} boxes are present, {@code identity} carries two
     * {@code LabelCandidate}s, lifecycle is {@code DORMANT}, evidence source is {@code REUPDATE}.
     *
     * <p>Every {@code [0,1]}-range field across the whole message (16 box coordinates + 3 {@code
     * Belief} fields + 2 {@code Memory} fields = 21 fields) is a distinct multiple of {@code 1/32},
     * chosen because {@code k/32} is exactly representable in both {@code float} (the wire type)
     * and {@code double} (every domain/DTO type here) -- the float-to-double widening the codec
     * performs on every one of these introduces no rounding, so the committed fixture's decimal
     * literals are exact, not an artifact of a lossy conversion.
     */
    private static com.drones.vision.proto.v1.ObjectState fullWireObjectState() {
        com.drones.vision.proto.v1.ObjectState.Identity identity = com.drones.vision.proto.v1.ObjectState.Identity
                .newBuilder()
                .setLabel("elected-label")
                .setLabelRaw("raw-label")
                .addCandidates(com.drones.vision.proto.v1.ObjectState.LabelCandidate.newBuilder()
                        .setLabel("cand-a").setWeight(20.5f).build())
                .addCandidates(com.drones.vision.proto.v1.ObjectState.LabelCandidate.newBuilder()
                        .setLabel("cand-b").setWeight(21.25f).build())
                .setStability(102)
                .build();

        com.drones.vision.proto.v1.ObjectState.Kinematics kinematics = com.drones.vision.proto.v1.ObjectState.Kinematics
                .newBuilder()
                .setBox(box(0.03125f, 0.0625f, 0.09375f, 0.125f))
                .setDetectorBox(box(0.15625f, 0.1875f, 0.21875f, 0.25f))
                .setTrackerBox(box(0.28125f, 0.3125f, 0.34375f, 0.375f))
                .setPredictedBox(box(0.40625f, 0.4375f, 0.46875f, 0.5f))
                .setHorizonMs(203L)
                .setVelocityX(10.5f)
                .setVelocityY(11.25f)
                .setDisplacementX(12.75f)
                .setDisplacementY(13.125f)
                .setMotionCompensated(true)
                .build();

        com.drones.vision.proto.v1.ObjectState.Belief belief = com.drones.vision.proto.v1.ObjectState.Belief
                .newBuilder()
                .setConfidenceRaw(0.53125f)
                .setConfidenceSmoothed(0.5625f)
                .setExistence(0.59375f)
                .setSinceConfirmedMs(304L)
                .build();

        com.drones.vision.proto.v1.ObjectState.Provenance provenance = com.drones.vision.proto.v1.ObjectState.Provenance
                .newBuilder()
                .setSource(com.drones.vision.proto.v1.EvidenceSource.EVIDENCE_SOURCE_REUPDATE)
                .addContributors("contributor-alpha")
                .addContributors("contributor-beta")
                .setAssocCost(14.625f)
                .setReupdated(true)
                .build();

        com.drones.vision.proto.v1.ObjectState.Memory memory = com.drones.vision.proto.v1.ObjectState.Memory
                .newBuilder()
                .setRecovered(true)
                .setIdentityConfidence(0.625f)
                .setDormantMs(405L)
                .setGalleryMatches(106)
                .setMatchDistance(0.65625f)
                .build();

        com.drones.vision.proto.v1.ObjectState.Lock lock = com.drones.vision.proto.v1.ObjectState.Lock.newBuilder()
                .setLocked(true)
                .setLockSeqApplied(507L)
                .build();

        com.drones.vision.proto.v1.ObjectState.Timing timing = com.drones.vision.proto.v1.ObjectState.Timing
                .newBuilder()
                .setFirstSeenMs(608L)
                .setLastSeenMs(709L)
                .setLastConfirmedMs(810L)
                .setAgeFrames(111)
                .setHits(112)
                .setMisses(113)
                .build();

        return com.drones.vision.proto.v1.ObjectState.newBuilder()
                .setId(101L)
                .setLifecycle(com.drones.vision.proto.v1.ObjectLifecycle.OBJECT_LIFECYCLE_DORMANT)
                .setStreamId("full-object-wire-stream-id-never-read-by-decode")
                .setIdentity(identity)
                .setKinematics(kinematics)
                .setBelief(belief)
                .setProvenance(provenance)
                .setMemory(memory)
                .setLock(lock)
                .setTiming(timing)
                .build();
    }

    /**
     * Only the required top-level fields -- no group set at all. Proves an absent group decodes to
     * a genuinely missing JSON key, not a zeroed object (assertion (f) in the test method above).
     */
    private static com.drones.vision.proto.v1.ObjectState minimalWireObjectState() {
        return com.drones.vision.proto.v1.ObjectState.newBuilder()
                .setId(999L)
                .setLifecycle(com.drones.vision.proto.v1.ObjectLifecycle.OBJECT_LIFECYCLE_TENTATIVE)
                .setStreamId("minimal-object-wire-stream-id-never-read-by-decode")
                .build();
    }

    private static com.drones.vision.proto.v1.BoundingBox box(float x, float y, float width, float height) {
        return com.drones.vision.proto.v1.BoundingBox.newBuilder()
                .setX(x).setY(y).setWidth(width).setHeight(height)
                .build();
    }
}
