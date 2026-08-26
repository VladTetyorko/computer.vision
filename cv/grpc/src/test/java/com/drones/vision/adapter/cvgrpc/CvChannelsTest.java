package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.perception.domain.model.PixelFormat;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.proto.v1.DetectionResponse;
import com.drones.vision.proto.v1.FrameRequest;
import com.drones.vision.proto.v1.InferenceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-TCP-loopback tests (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R6) — an in-process
 * channel never fails or reconnects, so proving {@link CvChannels#forTargets}' failover actually works
 * needs a real dead port next to a real listening server, the same technique {@code
 * GrpcDetectionPortTest#detectRecoversAfterRealConnectFailureOnceServerStarts} already uses for the
 * single-target reconnect case.
 */
class CvChannelsTest {

    private final List<GrpcDetectionPort> ports = new ArrayList<>();
    private final List<ManagedChannel> channels = new ArrayList<>();
    private final List<Server> servers = new ArrayList<>();

    @AfterEach
    void tearDown() throws InterruptedException {
        for (GrpcDetectionPort port : ports) {
            port.close();
        }
        for (ManagedChannel channel : channels) {
            channel.shutdownNow();
        }
        for (Server server : servers) {
            server.shutdownNow();
            server.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void forTargetBuildsAChannelThatCompletesARealRoundTrip() throws Exception {
        int port = findFreeTcpPort();
        Server server = ServerBuilder.forPort(port).addService(new EchoServicer()).build().start();
        servers.add(server);

        ManagedChannel channel = CvChannels.forTarget(new CvTarget("localhost", port), GrpcCvSettings.defaults());
        channels.add(channel);
        GrpcDetectionPort detectionPort = new GrpcDetectionPort(channel, GrpcCvSettings.defaults());
        ports.add(detectionPort);

        DetectionResult result = detect(detectionPort, StreamId.random()).toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertTrue(result != null);
    }

    @Test
    void forTargetsWithOneElementBehavesLikeForTarget() throws Exception {
        int port = findFreeTcpPort();
        Server server = ServerBuilder.forPort(port).addService(new EchoServicer()).build().start();
        servers.add(server);

        ManagedChannel channel = CvChannels.forTargets(List.of(new CvTarget("localhost", port)), GrpcCvSettings.defaults());
        channels.add(channel);
        GrpcDetectionPort detectionPort = new GrpcDetectionPort(channel, GrpcCvSettings.defaults());
        ports.add(detectionPort);

        DetectionResult result = detect(detectionPort, StreamId.random()).toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertTrue(result != null);
    }

    @Test
    void forTargetsFailsOverToTheSecondTargetWhenTheFirstIsUnreachable() throws Exception {
        int deadPort = findFreeTcpPort(); // nothing ever listens here
        int livePort = findFreeTcpPort();
        Server server = ServerBuilder.forPort(livePort).addService(new EchoServicer()).build().start();
        servers.add(server);

        ManagedChannel channel = CvChannels.forTargets(
                List.of(new CvTarget("localhost", deadPort), new CvTarget("localhost", livePort)),
                GrpcCvSettings.defaults());
        channels.add(channel);
        GrpcDetectionPort detectionPort = new GrpcDetectionPort(channel, GrpcCvSettings.defaults());
        ports.add(detectionPort);
        StreamId streamId = StreamId.random();

        // pick_first tries the dead target first; a fail-fast RPC issued before it has finished
        // walking to the live one returns near-instantly rather than waiting, so -- same reasoning as
        // GrpcDetectionPortTest's real-reconnect test -- retry with real wall-clock sleeps between
        // attempts rather than looping tight.
        DetectionResult recovered = null;
        Throwable lastFailure = null;
        for (int attempt = 1; attempt <= 10 && recovered == null; attempt++) {
            try {
                recovered = detect(detectionPort, streamId).toCompletableFuture().get(3, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                lastFailure = e.getCause();
            }
            if (recovered == null) {
                Thread.sleep(500);
            }
        }

        assertTrue(recovered != null,
                "expected detect() to fail over to the second (live) target; last failure: " + lastFailure);
        assertEquals(streamId, recovered.streamId());
    }

    @Test
    void forTargetsRejectsAnEmptyList() {
        assertThrows(IllegalArgumentException.class, () -> CvChannels.forTargets(List.of(), GrpcCvSettings.defaults()));
    }

    @Test
    void aMultiTargetChannelsAuthorityIsThePrimaryTarget() {
        ManagedChannel channel = CvChannels.forTargets(
                List.of(new CvTarget("primary-host", 1111), new CvTarget("secondary-host", 2222)),
                GrpcCvSettings.defaults());
        channels.add(channel);
        assertEquals("primary-host:1111", channel.authority());
    }

    private static CompletionStage<DetectionResult> detect(GrpcDetectionPort port, StreamId streamId) {
        VideoFrame frame = new VideoFrame(streamId, 0, Instant.now(), 4, 4, PixelFormat.BGR24,
                ByteBuffer.wrap(new byte[4 * 4 * 3]));
        return port.detect(frame, PipelineConfig.defaults());
    }

    private static int findFreeTcpPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class EchoServicer extends InferenceGrpc.InferenceImplBase {
        @Override
        public StreamObserver<FrameRequest> detectStream(StreamObserver<DetectionResponse> responseObserver) {
            return new StreamObserver<>() {
                @Override
                public void onNext(FrameRequest request) {
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
                }
            };
        }
    }
}
