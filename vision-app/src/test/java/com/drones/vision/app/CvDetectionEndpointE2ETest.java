package com.drones.vision.app;

import com.drones.vision.app.devsupport.DevPrincipal;
import com.drones.vision.application.AssetService;
import com.drones.vision.application.AssetSpec;
import com.drones.vision.application.DeviceRegistration;
import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.proto.v1.BoundingBox;
import com.drones.vision.proto.v1.Detection;
import com.drones.vision.proto.v1.DetectionResponse;
import com.drones.vision.proto.v1.FrameRequest;
import com.drones.vision.proto.v1.InferenceGrpc;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * End-to-end proof for docs/MVP1-PLAN.md §C8 bullet 3's API-side done criterion: with a real (in-test,
 * loopback-TCP) {@code Inference/DetectStream} gRPC server that actually returns non-empty {@code
 * Detection}s (unlike {@link CvDetectionE2ETest}'s echo servicer, which never populates {@code
 * detections}), a {@code sim}-device stream's sampled frames flow all the way through {@link
 * com.drones.vision.adapter.cvgrpc.GrpcDetectionPort} &rarr; {@code StreamPipeline} &rarr; {@link
 * com.drones.vision.domain.port.out.DetectionRepositoryPort} &rarr; {@code GET
 * /api/streams/{streamId}/detections}, closing the loop on the API side of this feature.
 *
 * <p>Reuses {@link FileSimulationSmokeTest.RecordingPublisherConfig}/{@link
 * FileSimulationSmokeTest.RecordingStreamPublisher} and the same "create via {@link AssetService}
 * directly, hit the real endpoint via a hand-built {@link MockMvc}" style as {@link
 * CvDetectionE2ETest}/{@link FileSimulationSmokeTest} (no {@code @AutoConfigureMockMvc} on this
 * Spring Boot 4 classpath — see vision-app/MODULE.md's Gotchas).
 */
@SpringBootTest(properties = "vision.publish.enabled=false")
@Import(FileSimulationSmokeTest.RecordingPublisherConfig.class)
class CvDetectionEndpointE2ETest {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    private static Server server;

    @BeforeAll
    static void startDetectingServer() throws IOException {
        server = ServerBuilder.forPort(0).addService(new DetectingServicer()).build().start();
    }

    @AfterAll
    static void stopDetectingServer() {
        if (server != null) {
            server.shutdownNow();
        }
    }

    @DynamicPropertySource
    static void cvProperties(DynamicPropertyRegistry registry) {
        registry.add("vision.cv.enabled", () -> "true");
        registry.add("vision.cv.endpoint", () -> "localhost:" + server.getPort());
    }

    @Autowired
    private AssetService assetService;

    @Autowired
    private FileSimulationSmokeTest.RecordingStreamPublisher recordingStreamPublisher;

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void detectionsEndpointReturnsMappedRowsOnceFramesHaveFlowedThroughARealDetectingServer() throws Exception {
        DeviceRegistration videoDevice = new DeviceRegistration(
                "cv-endpoint-e2e-sim-camera", Set.of(Capability.VIDEO),
                new StreamDescriptor("sim", URI.create("sim://cv-endpoint-e2e-video"), Map.of()));

        Asset asset = assetService.create(new AssetSpec("CV Endpoint E2E Test Drone",
                        new CategoryId("drone"), Map.of(), List.of(videoDevice)),
                DevPrincipal.OWNERSHIP, DevPrincipal.USER_ID);

        StreamId streamId = assetService.startStream(asset.id(), null, PipelineConfig.defaults());
        try {
            boolean receivedFrame = recordingStreamPublisher.awaitFirstFrame(10, TimeUnit.SECONDS);
            assertTrue(receivedFrame, "expected at least one frame to reach StreamPublisherPort within 10s");

            String responseJson = awaitNonEmptyDetections(streamId);

            assertEquals(streamId.value().toString(), JsonPath.read(responseJson, "$[0].streamId"));
            assertEquals("person", JsonPath.read(responseJson, "$[0].detections[0].label"));
            assertEquals("yolo", JsonPath.read(responseJson, "$[0].detections[0].modelId"));
            assertEquals("latest", JsonPath.read(responseJson, "$[0].detections[0].modelVersion"));
            Number confidence = JsonPath.read(responseJson, "$[0].detections[0].confidence");
            assertEquals(0.87, confidence.doubleValue(), 0.001);
            Number boxX = JsonPath.read(responseJson, "$[0].detections[0].box.x");
            assertEquals(0.1, boxX.doubleValue(), 0.001);
        } finally {
            assetService.stopStream(asset.id());
        }
    }

    /**
     * Polls {@code GET /api/streams/{streamId}/detections} until it returns a non-empty array
     * (detection results are saved asynchronously off the gRPC response, so this can't be a single
     * assertion right after {@code startStream} returns), returning the raw response body once it
     * is non-empty, or whatever the last poll returned once {@link #AWAIT_TIMEOUT} elapses.
     */
    private String awaitNonEmptyDetections(StreamId streamId) throws Exception {
        Instant deadline = Instant.now().plus(AWAIT_TIMEOUT);
        String lastBody = "[]";
        while (Instant.now().isBefore(deadline)) {
            lastBody = mockMvc.perform(get("/api/streams/{streamId}/detections", streamId.value()))
                    .andReturn().getResponse().getContentAsString();
            List<Object> parsed = JsonPath.read(lastBody, "$");
            if (!parsed.isEmpty()) {
                return lastBody;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        return lastBody;
    }

    /** Always responds with exactly one non-empty {@code Detection}, unlike {@link CvDetectionE2ETest}'s echo servicer. */
    private static final class DetectingServicer extends InferenceGrpc.InferenceImplBase {

        @Override
        public StreamObserver<FrameRequest> detectStream(StreamObserver<DetectionResponse> responseObserver) {
            return new StreamObserver<>() {
                @Override
                public void onNext(FrameRequest request) {
                    Detection detection = Detection.newBuilder()
                            .setLabel("person")
                            .setConfidence(0.87f)
                            .setBox(BoundingBox.newBuilder().setX(0.1f).setY(0.2f).setWidth(0.3f).setHeight(0.4f).build())
                            .build();
                    responseObserver.onNext(DetectionResponse.newBuilder()
                            .setStreamId(request.getStreamId())
                            .setSequence(request.getSequence())
                            .setTimestampMillis(request.getTimestampMillis())
                            .setModelId(request.getModelId())
                            .setModelVersion(request.getModelVersion())
                            .addDetections(detection)
                            .setInferenceMillis(3)
                            .build());
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
    }
}
