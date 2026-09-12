package com.drones.vision.app;

import com.drones.vision.app.devsupport.DevPrincipal;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.perception.application.stream.AssetStreamService;
import com.drones.vision.warehouse.application.asset.AssetSpec;
import com.drones.vision.warehouse.application.device.DeviceRegistration;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.proto.v1.BoundingBox;
import com.drones.vision.proto.v1.Detection;
import com.drones.vision.proto.v1.DetectionResponse;
import com.drones.vision.proto.v1.DetectionSource;
import com.drones.vision.proto.v1.DetectorReason;
import com.drones.vision.proto.v1.FrameRequest;
import com.drones.vision.proto.v1.InferenceGrpc;
import com.drones.vision.proto.v1.TrackState;
import com.drones.vision.proto.v1.TrackingMode;
import com.jayway.jsonpath.JsonPath;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The end-to-end proof of docs/plans/done/TRACKING-PLAN.md wave T8: with tracking on by default, a stream's
 * detections carry a <strong>stable {@code trackId} across frames</strong>, and {@code GET
 * /api/streams/{streamId}/tracks} reports each id <em>once</em> — as one track observed repeatedly,
 * not as a new anonymous box per frame.
 *
 * <p>This is the test that exercises the whole chain at once — proto &rarr; {@code
 * DetectionFrameCodec} &rarr; {@code Detection.track()} &rarr; {@code TrackBook}/{@code
 * TrackingStatsWindow} &rarr; {@code StreamController} — against a real (in-test, loopback-TCP)
 * {@code Inference/DetectStream} server, in the same shape as {@link CvDetectionE2ETest}/{@link
 * CvDetectionEndpointE2ETest}. Every other wave proved one link of it; nothing until now proved
 * identity survives all of them.
 *
 * <h2>Why this test cannot pass with the default flipped back</h2>
 * {@link TrackingServicer} emits track-bearing detections <b>only</b> for a {@link FrameRequest}
 * that actually states {@code TRACKING_MODE_ASSOCIATE}, and untracked ones otherwise. So the two
 * halves of the flip are both load-bearing here: {@code PipelineConfig.defaults()} (vision-domain)
 * and {@code vision.tracking.default-mode} (vision-app, folded over it at every start path). Revert
 * either one and this test reports an empty track list rather than quietly passing on a technicality
 * — which is the whole reason the servicer branches on the request instead of always sending ids.
 *
 * <h2>What "stable" is asserted to mean</h2>
 * <ol>
 *   <li>The server sent ids {@code 7} and {@code 8} on <b>at least three</b> distinct frames, with a
 *       box that moves between them (so this is a sequence, not one frame observed three times).</li>
 *   <li>The endpoint reports <b>exactly two</b> tracks. Identity dropped anywhere in the chain gives
 *       an empty list ({@code track == null} is filtered out); identity <em>churned</em> gives one
 *       entry per frame. Two is only reachable if every layer carried the same id through.</li>
 *   <li>Each track's {@code firstSeen} is strictly before its {@code lastSeen} — the book merged
 *       observations from different frames into one entry rather than replacing it.</li>
 * </ol>
 */
@SpringBootTest(properties = "vision.publish.enabled=false")
@Import(FileSimulationSmokeTest.RecordingPublisherConfig.class)
class TrackingAssociateE2ETest {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    /** The two ids the servicer holds across every frame it answers. */
    private static final long CAR_TRACK_ID = 7L;
    private static final long PERSON_TRACK_ID = 8L;

    private static Server server;
    private static TrackingServicer servicer;

    @BeforeAll
    static void startTrackingServer() throws IOException {
        servicer = new TrackingServicer();
        server = ServerBuilder.forPort(0).addService(servicer).build().start();
    }

    @AfterAll
    static void stopTrackingServer() {
        if (server != null) {
            server.shutdownNow();
        }
    }

    @DynamicPropertySource
    static void cvProperties(DynamicPropertyRegistry registry) {
        registry.add("vision.cv.enabled", () -> "true");
        registry.add("vision.cv.endpoint", () -> "localhost:" + server.getPort());
        // docs/plans/done/CV-DEMAND-PLAN.md §3.7: this test proves tracking identity survives the
        // whole chain, not that a viewer/poller kept demand alive throughout -- the 3-ASSOCIATE-frame
        // wait below never opens the SSE detections:<assetId> topic or polls GET .../detections (only
        // GET .../tracks, which is not a demand signal), so the real demand-poll task would otherwise
        // race it. Disabling the gate is the documented escape hatch for exactly this.
        registry.add("vision.cv.demand.enabled", () -> "false");
    }

    /**
     * {@link PipelineConfig#defaults()} with {@code detectionEnabled} true} instead of its own
     * default {@code false} (docs/plans/done/CV-DEMAND-PLAN.md §1, wave D1) -- this test starts its
     * stream directly through {@link AssetStreamService}, bypassing the controller-level {@code
     * vision.cv.detection-default-enabled} deployment default entirely, so detection has to be
     * switched on explicitly here for the servicer to ever see a frame at all.
     */
    private static PipelineConfig detectionEnabledDefaults() {
        PipelineConfig defaults = PipelineConfig.defaults();
        return new PipelineConfig(defaults.model(), defaults.confidenceThreshold(), defaults.inferenceFps(),
                defaults.maxInFlightInferences(), defaults.labelFilter(), defaults.eventRule(),
                true, defaults.tracking(), defaults.labelDenyFilter(), defaults.trace());
    }

    @Autowired
    private AssetService assetService;

    @Autowired
    private AssetStreamService assetStreamService;

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void aStreamStartedOnTheDefaultsTracksAndTheTracksEndpointReportsStableIdsAcrossFrames()
            throws Exception {
        DeviceRegistration videoDevice = new DeviceRegistration(
                "tracking-e2e-sim-camera", Set.of(Capability.VIDEO),
                new StreamDescriptor("sim", URI.create("sim://tracking-e2e-video"), Map.of()));

        Asset asset = assetService.create(new AssetSpec("Tracking E2E Test Drone",
                        new CategoryId("drone"), Map.of(), List.of(videoDevice)),
                DevPrincipal.OWNERSHIP, DevPrincipal.USER_ID);

        StreamId streamId = assetStreamService.startStream(asset.id(), null, detectionEnabledDefaults());
        try {
            assertTrue(servicer.threeTrackedFrames.await(AWAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                    "expected >=3 frames stating TRACKING_MODE_ASSOCIATE within " + AWAIT_TIMEOUT
                            + " -- the flip is what puts the mode on the wire, so a failure here means the "
                            + "default did not reach FrameRequest.tracking at all. Observed: " + servicer.diagnose());

            List<FrameRequest> asked = servicer.trackedRequests();
            assertTrue(asked.size() >= 3, "the servicer answered " + asked.size() + " ASSOCIATE frames");
            assertTrue(asked.stream().mapToLong(FrameRequest::getSequence).distinct().count() >= 3,
                    "three DISTINCT frame sequences, so the ids below span a sequence rather than one repeated frame");

            String json = awaitTwoTracks(streamId);

            assertEquals(streamId.value().toString(), JsonPath.read(json, "$.streamId"));
            List<Integer> ids = JsonPath.read(json, "$.tracks[*].trackId");
            assertEquals(List.of((int) CAR_TRACK_ID, (int) PERSON_TRACK_ID), ids,
                    "exactly one book entry per id, ordered by trackId -- an id churned per frame would "
                            + "read as one entry per frame, and an id dropped anywhere in the chain as none");

            assertEquals("car", JsonPath.read(json, "$.tracks[0].label"));
            assertEquals("CONFIRMED", JsonPath.read(json, "$.tracks[0].state"));
            assertEquals("DETECTOR", JsonPath.read(json, "$.tracks[0].source"));
            Number velocityX = JsonPath.read(json, "$.tracks[0].velocityX");
            assertEquals(0.012, velocityX.doubleValue(), 1e-6, "per-detection track facts survive the whole chain");

            Instant firstSeen = Instant.parse(JsonPath.read(json, "$.tracks[0].firstSeen"));
            Instant lastSeen = Instant.parse(JsonPath.read(json, "$.tracks[0].lastSeen"));
            assertTrue(lastSeen.isAfter(firstSeen),
                    "the same id was booked across frames captured at different instants (" + firstSeen + " -> "
                            + lastSeen + ") -- that IS the identity claim; equal instants would mean the book "
                            + "only ever saw one frame");

            // The stats window is fed off the same responses, so it is the second, independent
            // witness that these frames were duty-cycle frames and not an artifact of one reply.
            assertEquals("ASSOCIATE", JsonPath.read(json, "$.stats.mode"));
            assertEquals("ALWAYS", JsonPath.read(json, "$.stats.lastDetectorReason"));
            Number detectorPasses = JsonPath.read(json, "$.stats.detectorPasses");
            assertTrue(detectorPasses.longValue() >= 3,
                    "expected >=3 detector passes in the window, saw " + detectorPasses);
        } finally {
            assetService.stopStream(asset.id());
        }
    }

    /**
     * Polls {@code GET /api/streams/{streamId}/tracks} until it reports both ids (detection results
     * are booked asynchronously off the gRPC response, so this cannot be a single assertion right
     * after the frames arrive), returning the last body seen once {@link #AWAIT_TIMEOUT} elapses.
     */
    private String awaitTwoTracks(StreamId streamId) throws Exception {
        Instant deadline = Instant.now().plus(AWAIT_TIMEOUT);
        String body = "";
        while (Instant.now().isBefore(deadline)) {
            body = mockMvc.perform(get("/api/streams/{streamId}/tracks", streamId.value().toString()))
                    .andReturn().getResponse().getContentAsString();
            List<Object> tracks = JsonPath.read(body, "$.tracks");
            if (tracks.size() == 2) {
                Instant firstSeen = Instant.parse(JsonPath.read(body, "$.tracks[0].firstSeen"));
                Instant lastSeen = Instant.parse(JsonPath.read(body, "$.tracks[0].lastSeen"));
                if (lastSeen.isAfter(firstSeen)) {
                    return body;
                }
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        return body;
    }

    /**
     * Answers a frame with two tracked detections holding ids {@value #CAR_TRACK_ID}/{@value
     * #PERSON_TRACK_ID} across every frame — <b>but only when the request asked for {@code
     * ASSOCIATE}</b>, which is what makes this test a real check of the default rather than of the
     * servicer's generosity. A frame that states any other mode is answered with the same two boxes
     * carrying no track fields, exactly as a pre-tracking cv-service would.
     */
    private static final class TrackingServicer extends InferenceGrpc.InferenceImplBase {

        private final List<FrameRequest> tracked = new CopyOnWriteArrayList<>();
        private final List<String> everyModeSeen = new CopyOnWriteArrayList<>();
        final CountDownLatch threeTrackedFrames = new CountDownLatch(3);

        List<FrameRequest> trackedRequests() {
            return List.copyOf(tracked);
        }

        /** What the server actually saw, for a failure message that names the cause. */
        String diagnose() {
            return everyModeSeen.size() + " frame(s), modes=" + Set.copyOf(everyModeSeen);
        }

        @Override
        public StreamObserver<FrameRequest> detectStream(StreamObserver<DetectionResponse> responseObserver) {
            return new StreamObserver<>() {
                @Override
                public void onNext(FrameRequest request) {
                    everyModeSeen.add(request.getTracking().getMode().name());
                    boolean associate = request.getTracking().getMode() == TrackingMode.TRACKING_MODE_ASSOCIATE;
                    if (associate) {
                        tracked.add(request);
                        threeTrackedFrames.countDown();
                    }
                    responseObserver.onNext(response(request, associate));
                }

                @Override
                public void onError(Throwable t) {
                    // test double: nothing to clean up
                }

                @Override
                public void onCompleted() {
                    responseObserver.onCompleted();
                }
            };
        }

        private static DetectionResponse response(FrameRequest request, boolean associate) {
            // The box drifts with the frame sequence so successive frames are genuinely different
            // observations of the same id, not one frame echoed back repeatedly.
            double drift = 0.001 * (request.getSequence() % 100);
            DetectionResponse.Builder builder = DetectionResponse.newBuilder()
                    .setStreamId(request.getStreamId())
                    .setSequence(request.getSequence())
                    .setTimestampMillis(request.getTimestampMillis())
                    .setModelId(request.getModelId())
                    .setModelVersion(request.getModelVersion())
                    .setInferenceMillis(3)
                    .addDetections(detection("car", 0.82f, 0.31 + drift, 0.44, CAR_TRACK_ID, associate))
                    .addDetections(detection("person", 0.71f, 0.62 + drift, 0.35, PERSON_TRACK_ID, associate));
            if (associate) {
                builder.setDetectorRan(true)
                        .setDetectorReason(DetectorReason.DETECTOR_REASON_ALWAYS)
                        .setTrackerMillis(1)
                        .setTrackerEngineId("bytetrack");
            }
            return builder.build();
        }

        private static Detection detection(String label, float confidence, double x, double y, long trackId,
                                            boolean associate) {
            Detection.Builder builder = Detection.newBuilder()
                    .setLabel(label)
                    .setConfidence(confidence)
                    .setBox(BoundingBox.newBuilder().setX((float) x).setY((float) y)
                            .setWidth(0.09f).setHeight(0.07f).build());
            if (associate) {
                builder.setTrackId(trackId)
                        .setTrackState(TrackState.TRACK_STATE_CONFIRMED)
                        .setSource(DetectionSource.DETECTION_SOURCE_DETECTOR)
                        .setVelocityX(0.012f)
                        .setVelocityY(-0.001f)
                        .setTrackAgeFrames(4);
            }
            return builder.build();
        }
    }
}
