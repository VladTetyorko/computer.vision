package com.drones.vision.api.dto;

import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.DetectionEventId;
import com.drones.vision.perception.domain.model.FollowState;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The TypeScript-mirror acceptance test for CV-ORCHESTRATION wave W2.8 ({@code WorldObject} reaches
 * the wire): {@link WorldObjectResponse#from} serialized and checked byte-for-byte (as a parsed JSON
 * tree, so key order is irrelevant) against the fixture committed at {@code
 * station/vision-web/src/app/core/api/__fixtures__/world-object.wire.json}. A vitest spec ({@code
 * station/vision-web/src/app/core/api/world-object.wire.contract.spec.ts}) loads the same fixture
 * and proves it satisfies the frontend's {@code WorldObject}/{@code RenderTier}/{@code FollowState}
 * TypeScript types, key-for-key — same idiom as {@code ObjectStateRoundTripTest}'s own javadoc,
 * except {@link WorldObject} is never wire-decoded from a proto message (it is this platform's own
 * in-process fold over {@code ObjectState}, {@code WorldModel}'s own javadoc), so there is no codec
 * to round-trip through: this test builds the domain record directly.
 *
 * <p>Declared in {@code vision-api} itself, unlike {@code ObjectStateRoundTripTest} (which needed
 * {@code vision-app} only to reach {@code adapter-cv-grpc}'s package-private decoder) — {@link
 * WorldObjectResponse#from} is this module's own mapping, nothing else is required.
 *
 * <p>A bare {@code JsonMapper} is not dodging Spring's real Jackson config: {@code
 * @JsonInclude(NON_NULL)} is an annotation on the record itself, not a mapper-level toggle Spring
 * applies afterward, so {@code new JsonMapper()} here serializes byte-identically to whatever {@code
 * JsonMapper} bean the running application wires up (same idiom as {@code ObjectStateRoundTripTest}/
 * {@code OnboardingWireContractTest}).
 */
class WorldObjectResponseWireContractTest {

    private static final JsonMapper JSON = new JsonMapper();

    /**
     * {@code vision-web} is a sibling module of {@code vision-api} under {@code station/}, and
     * Surefire's working directory for a forked test JVM defaults to the module's own {@code
     * basedir} -- so this relative path reaches the committed fixture without any build-time
     * resource copy between modules.
     */
    private static final Path FIXTURE_PATH = Path.of(
            "..", "vision-web", "src", "app", "core", "api", "__fixtures__", "world-object.wire.json");

    /**
     * Deliberately NOT a sibling of {@link #FIXTURE_PATH} (unlike {@code ObjectStateRoundTripTest}'s
     * own convention) — a sibling would land inside {@code vision-web}'s tracked source tree, where
     * nothing ignores it, so a failing run would leave an untracked file a later {@code git add -A}
     * could commit. {@code target/} is this module's own untracked build-output directory, safe for
     * exactly this kind of scratch artifact; {@code cp} regeneration still works off the absolute
     * path the failure message prints.
     */
    private static final Path ACTUAL_PATH = Path.of("target", "world-object.wire.actual.json");

    /** Fixed, not {@link StreamId#random()}: the fixture is a committed file and must be produced
     *  identically on every run. */
    private static final StreamId STREAM_ID = StreamId.of("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void worldObjectResponseMatchesTheCommittedFixture() throws IOException {
        WorldObject full = new WorldObject(minimalState(9L),
                new WorldObject.Operator(true, false, FollowState.HOLDING),
                new WorldObject.EventLink(DetectionEventId.of("cccccccc-cccc-cccc-cccc-cccccccccccc")),
                new WorldObject.Render(RenderTier.T2));
        // The "absence of a relation" half (CLAUDE.md rule 10): no follow, no open event -- both
        // keys must be MISSING, never null/zeroed. render.tier is never nullable (WorldObject.Render's
        // own compact constructor forbids it), so HIDDEN stands in as this object's "nothing to
        // render" answer instead.
        WorldObject minimal = new WorldObject(minimalState(10L), new WorldObject.Operator(false, false, null),
                new WorldObject.EventLink(null), new WorldObject.Render(RenderTier.HIDDEN));

        String fullJson = JSON.writeValueAsString(WorldObjectResponse.from(full));
        String minimalJson = JSON.writeValueAsString(WorldObjectResponse.from(minimal));
        String actualJson = "{\"full\":" + fullJson + ",\"minimal\":" + minimalJson + "}";

        JsonNode actual = JSON.readTree(actualJson);
        JsonNode expected = JSON.readTree(Files.readString(FIXTURE_PATH, StandardCharsets.UTF_8));

        // Compared as parsed JSON trees (order-independent) so a harmless field-ordering difference
        // never fails this test -- only an actual content difference does. On a mismatch we do NOT
        // overwrite the fixture; we write the produced JSON to ACTUAL_PATH (this module's own
        // target/, not beside the fixture -- see that field's own javadoc for why) so regenerating
        // is still one `cp` away.
        if (!expected.equals(actual)) {
            Files.writeString(ACTUAL_PATH, actualJson, StandardCharsets.UTF_8);
            fail("Produced JSON does not match the committed fixture " + FIXTURE_PATH.toAbsolutePath()
                    + " -- wrote the actual output to " + ACTUAL_PATH.toAbsolutePath()
                    + ". If this difference is the intended new shape, regenerate with "
                    + "`cp " + ACTUAL_PATH + " " + FIXTURE_PATH + "` and re-run.\nexpected: " + expected
                    + "\nactual:   " + actual);
        }

        JsonNode minimalNode = actual.get("minimal");
        assertFalse(minimalNode.get("operator").has("follow"),
                "an object with no FOLLOW lock must have no 'follow' key: " + minimalNode);
        assertFalse(minimalNode.get("event").has("openEventId"),
                "an object with no open event must have no 'openEventId' key: " + minimalNode);
    }

    private static ObjectState minimalState(long id) {
        return new ObjectState(id, ObjectLifecycle.CONFIRMED, STREAM_ID, null, null, null, null, null, null, null);
    }
}
