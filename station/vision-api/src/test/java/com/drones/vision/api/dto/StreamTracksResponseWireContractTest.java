package com.drones.vision.api.dto;

import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionRate;
import com.drones.vision.perception.domain.model.DetectionSource;
import com.drones.vision.perception.domain.model.DetectionState;
import com.drones.vision.perception.domain.model.DetectorReason;
import com.drones.vision.perception.domain.model.DetectionEventId;
import com.drones.vision.perception.domain.model.FollowState;
import com.drones.vision.perception.domain.model.FollowStatus;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.perception.domain.model.ObjectLifecycle;
import com.drones.vision.perception.domain.model.ObjectState;
import com.drones.vision.perception.domain.model.PipelineLatency;
import com.drones.vision.perception.domain.model.RenderTier;
import com.drones.vision.perception.domain.model.TrackRef;
import com.drones.vision.perception.domain.model.TrackState;
import com.drones.vision.perception.domain.model.TrackedObject;
import com.drones.vision.perception.domain.model.TrackingMode;
import com.drones.vision.perception.domain.model.TrackingStats;
import com.drones.vision.perception.domain.model.TracksSnapshot;
import com.drones.vision.perception.domain.model.WorldObject;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The TypeScript-mirror acceptance test for CV-ORCHESTRATION wave W9 (decision E25 -- the {@code
 * tracks:} SSE topic and {@code GET /api/streams/{streamId}/tracks} now build from one assembly):
 * {@link StreamTracksResponse#from} serialized and checked byte-for-byte (as a parsed JSON tree, so
 * key order is irrelevant) against the fixture committed at {@code
 * station/vision-web/src/app/core/api/__fixtures__/stream-tracks.wire.json}. A vitest spec ({@code
 * station/vision-web/src/app/core/api/stream-tracks.wire.contract.spec.ts}, wave W9.2) loads the
 * same fixture and proves it satisfies the frontend's {@code StreamTracksResponse} TypeScript type,
 * key-for-key -- same idiom as {@code CvTraceResponseWireContractTest}.
 *
 * <p>Exercises {@link StreamTracksResponse#from(TracksSnapshot, Instant)} itself, not the record's
 * bare constructor: that factory is now the one place every gating rule (stats/latency/rate/
 * detectionState/follow omission, {@code lockedTrackId} hoisting) lives, consumed by both {@code
 * StreamController#tracks} and {@code LiveUpdateRegistry#flushPending} -- proving its output once,
 * on a full and a minimal example, covers both transports.
 *
 * <p>The {@code full} example populates every optional field (a booked track, stats, latency, rate,
 * detectionState, an active follow lock, and one world object) so every key the frontend type
 * declares actually appears at least once. The {@code minimal} example is {@link
 * TracksSnapshot#empty}, the same "never errors" shape {@code StreamController#tracks} itself falls
 * back to for an unknown or stopped stream: {@code tracks}/{@code objects} present as empty arrays,
 * {@code stats}/{@code latency}/{@code rate}/{@code detectionState}/{@code follow} all omitted,
 * {@code lockedTrackId: 0}.
 *
 * <p>A bare {@code JsonMapper} is not dodging Spring's real Jackson config: {@code
 * @JsonInclude(NON_NULL)} is an annotation on the record itself, not a mapper-level toggle Spring
 * applies afterward, so {@code new JsonMapper()} here serializes byte-identically to whatever {@code
 * JsonMapper} bean the running application wires up (same idiom as {@code
 * WorldObjectResponseWireContractTest}/{@code CvTraceResponseWireContractTest}).
 */
class StreamTracksResponseWireContractTest {

    private static final JsonMapper JSON = new JsonMapper();

    /**
     * {@code vision-web} is a sibling module of {@code vision-api} under {@code station/}, and
     * Surefire's working directory for a forked test JVM defaults to the module's own {@code
     * basedir} -- so this relative path reaches the committed fixture without any build-time
     * resource copy between modules.
     */
    private static final Path FIXTURE_PATH = Path.of(
            "..", "vision-web", "src", "app", "core", "api", "__fixtures__", "stream-tracks.wire.json");

    /**
     * Deliberately NOT a sibling of {@link #FIXTURE_PATH} -- a sibling would land inside {@code
     * vision-web}'s tracked source tree, where nothing ignores it, so a failing run could leave an
     * untracked file a later {@code git add -A} could commit. {@code target/} is this module's own
     * untracked build-output directory, safe for exactly this kind of scratch artifact; {@code cp}
     * regeneration still works off the absolute path the failure message prints.
     */
    private static final Path ACTUAL_PATH = Path.of("target", "stream-tracks.wire.actual.json");

    /** Fixed, not {@link StreamId#random()}: the fixture is a committed file and must be produced
     *  identically on every run. */
    private static final StreamId STREAM_ID = StreamId.of("ffffffff-ffff-ffff-ffff-ffffffffffff");

    /** Fixed instants so every timestamp/derived age in the fixture is deterministic. */
    private static final Instant FIRST_SEEN = Instant.parse("2026-08-11T10:22:31.104Z");
    private static final Instant LAST_SEEN = Instant.parse("2026-08-11T10:22:40.671Z");
    private static final Instant NOW = Instant.parse("2026-08-11T10:22:45.671Z");

    @Test
    void streamTracksResponseMatchesTheCommittedFixture() throws IOException {
        StreamTracksResponse full = StreamTracksResponse.from(fullSnapshot(), NOW);
        StreamTracksResponse minimal = StreamTracksResponse.from(TracksSnapshot.empty(STREAM_ID), NOW);

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

    private static TracksSnapshot fullSnapshot() {
        Detection detection = new Detection("car", 0.82, new BoundingBox(0.31, 0.44, 0.09, 0.07),
                new ModelRef("yolo26n.pt", "latest"),
                new TrackRef(7L, TrackState.CONFIRMED, DetectionSource.TRACKER, 0.012, -0.001, 143, true, 0.0, 0L));
        TrackedObject track = new TrackedObject(7L, detection, FIRST_SEEN, LAST_SEEN);
        TrackingStats stats = new TrackingStats(TrackingMode.FOLLOW, "lk", Duration.ofSeconds(30), 12, 348, 0.034,
                0.4, 0.9, DetectorReason.CADENCE, 7L, Map.of(TrackState.CONFIRMED, 3, TrackState.COASTING, 1));
        PipelineLatency latency = new PipelineLatency(Duration.ofSeconds(30), 12L, 45.0, 78.0, 120.0, 100.0, 9.8);
        DetectionRate rate = new DetectionRate(Duration.ofSeconds(30), 24.0, 10.0, 18.0, 7.5, 15L, 5L, 0L, 3L);
        FollowStatus follow = new FollowStatus(FollowState.HOLDING, 7L, "car", FIRST_SEEN, LAST_SEEN,
                detection.box(), false, 0L, 0.0);
        WorldObject worldObject = new WorldObject(minimalState(7L), new WorldObject.Operator(true, false,
                FollowState.HOLDING), new WorldObject.EventLink(
                        DetectionEventId.of("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")),
                new WorldObject.Render(RenderTier.T1));
        return new TracksSnapshot(STREAM_ID, java.util.List.of(track), Optional.of(stats), Optional.of(latency),
                Optional.of(rate), Optional.of(DetectionState.RUNNING), Optional.of(follow),
                java.util.List.of(worldObject));
    }

    private static ObjectState minimalState(long id) {
        return new ObjectState(id, ObjectLifecycle.CONFIRMED, STREAM_ID, null, null, null, null, null, null, null);
    }
}
