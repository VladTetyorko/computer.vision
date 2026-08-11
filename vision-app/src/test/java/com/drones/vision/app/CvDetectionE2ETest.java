package com.drones.vision.app;

import com.drones.vision.app.devsupport.DevPrincipal;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetSpec;
import com.drones.vision.warehouse.application.device.DeviceRegistration;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.proto.v1.DetectionResponse;
import com.drones.vision.proto.v1.FrameRequest;
import com.drones.vision.proto.v1.InferenceGrpc;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end echo round trip for docs/plans/done/MVP1-PLAN.md §C7 bullet 4: with {@code
 * vision.cv.enabled=true} pointed at a real (in-test, loopback-TCP) {@code Inference/DetectStream}
 * gRPC server, a {@code sim}-device stream's sampled frames actually reach the server as {@code
 * FrameRequest}s (proving {@link com.drones.vision.adapter.cvgrpc.GrpcDetectionPort} is wired all
 * the way from {@link WiringConfiguration} through {@link
 * com.drones.vision.perception.application.pipeline.StreamPipeline}'s inference sampling), while video keeps flowing
 * to {@link com.drones.vision.perception.domain.port.StreamPublisherPort} the whole time. Stopping the
 * stream then proves session cleanup: {@link DetectionSessionCleanupEventPublisher} calls {@code
 * GrpcDetectionPort#streamEnded}, which half-closes the client's request stream, which the server
 * observes as its {@code StreamObserver<FrameRequest>#onCompleted} firing.
 *
 * <p>Unlike {@link com.drones.vision.adapter.cvgrpc.GrpcDetectionPortTest} (which uses an
 * in-process gRPC channel/server pair), this test needs a real network endpoint because it
 * exercises the full production wiring via {@code vision.cv.endpoint} — {@link
 * ServerBuilder#forPort(int)} on an ephemeral port (backed by {@code grpc-netty-shaded}, already
 * on this module's test classpath transitively via adapter-cv-grpc/vision-proto) needs no docker
 * and no Python cv-service.
 *
 * <p>Reuses {@link FileSimulationSmokeTest.RecordingPublisherConfig}/{@link
 * FileSimulationSmokeTest.RecordingStreamPublisher} rather than duplicating them, same as {@link
 * RtspSimulationDockerE2ETest}/{@link MjpegSimulationSmokeTest}, and creates its asset/stream
 * directly through {@link AssetService} rather than the HTTP layer, same as {@link
 * SimStreamSmokeTest}.
 */
@SpringBootTest(properties = "vision.publish.enabled=false")
@Import(FileSimulationSmokeTest.RecordingPublisherConfig.class)
class CvDetectionE2ETest {

    private static Server server;
    private static RecordingServicer servicer;

    @BeforeAll
    static void startEchoServer() throws IOException {
        servicer = new RecordingServicer();
        server = ServerBuilder.forPort(0).addService(servicer).build().start();
    }

    @AfterAll
    static void stopEchoServer() {
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

    @Test
    void sampledFramesReachTheGrpcServerAndSessionEndsOnStop() throws InterruptedException {
        DeviceRegistration videoDevice = new DeviceRegistration(
                "cv-e2e-sim-camera", Set.of(Capability.VIDEO),
                new StreamDescriptor("sim", URI.create("sim://cv-e2e-video"), Map.of()));

        Asset asset = assetService.create(new AssetSpec("CV E2E Test Drone",
                        new CategoryId("drone"), Map.of(), List.of(videoDevice)),
                DevPrincipal.OWNERSHIP, DevPrincipal.USER_ID);

        StreamId streamId = assetService.startStream(asset.id(), null, PipelineConfig.defaults());
        try {
            boolean receivedFrame = recordingStreamPublisher.awaitFirstFrame(10, TimeUnit.SECONDS);
            assertTrue(receivedFrame, "expected at least one frame to reach StreamPublisherPort within 10s");
            assertFalse(recordingStreamPublisher.frames().isEmpty());

            boolean sawRequest = servicer.firstRequest.await(15, TimeUnit.SECONDS);
            assertTrue(sawRequest,
                    "expected the echo gRPC server to receive >=1 FrameRequest within 15s of "
                            + "sampled frames flowing through GrpcDetectionPort");
            assertFalse(servicer.received.isEmpty());
            assertTrue(servicer.received.stream()
                            .allMatch(request -> request.getStreamId().equals(streamId.value().toString())),
                    "every FrameRequest observed by the server must carry this stream's id");
        } finally {
            assetService.stopStream(asset.id());
        }

        boolean sessionEnded = servicer.requestStreamCompleted.await(10, TimeUnit.SECONDS);
        assertTrue(sessionEnded,
                "expected stopping the stream to call GrpcDetectionPort#streamEnded (wired via "
                        + "DetectionSessionCleanupEventPublisher on STREAM_STOPPED), half-closing "
                        + "the request stream -- observed here as the server's onCompleted firing");
    }

    /** Echoes each {@code FrameRequest} back and records every request it sees. */
    private static final class RecordingServicer extends InferenceGrpc.InferenceImplBase {

        final List<FrameRequest> received = new CopyOnWriteArrayList<>();
        final CountDownLatch firstRequest = new CountDownLatch(1);
        final CountDownLatch requestStreamCompleted = new CountDownLatch(1);

        @Override
        public StreamObserver<FrameRequest> detectStream(StreamObserver<DetectionResponse> responseObserver) {
            return new StreamObserver<>() {
                @Override
                public void onNext(FrameRequest request) {
                    received.add(request);
                    firstRequest.countDown();
                    responseObserver.onNext(DetectionResponse.newBuilder()
                            .setStreamId(request.getStreamId())
                            .setSequence(request.getSequence())
                            .setTimestampMillis(request.getTimestampMillis())
                            .setModelId(request.getModelId())
                            .setModelVersion(request.getModelVersion())
                            .setInferenceMillis(0)
                            .build());
                }

                @Override
                public void onError(Throwable t) {
                    // test double: nothing to clean up
                }

                @Override
                public void onCompleted() {
                    responseObserver.onCompleted();
                    requestStreamCompleted.countDown();
                }
            };
        }
    }
}
