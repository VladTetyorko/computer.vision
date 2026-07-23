package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.domain.model.Detection;
import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.PixelFormat;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.proto.v1.DetectionResponse;
import com.drones.vision.proto.v1.FrameRequest;
import com.drones.vision.proto.v1.InferenceGrpc;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrpcDetectionPortTest {

    private final List<GrpcDetectionPort> ports = new ArrayList<>();
    private final List<Server> servers = new ArrayList<>();

    @AfterEach
    void tearDown() throws InterruptedException {
        for (GrpcDetectionPort port : ports) {
            port.close();
        }
        for (Server server : servers) {
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private GrpcDetectionPort newPort(BindableService service) throws Exception {
        String name = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(name).addService(service).build().start();
        servers.add(server);
        ManagedChannel channel = InProcessChannelBuilder.forName(name).build();
        GrpcDetectionPort port = new GrpcDetectionPort(channel);
        ports.add(port);
        return port;
    }

    private static VideoFrame frame(StreamId streamId, long sequence, PixelFormat format) {
        // truncated to millis: FrameRequest/DetectionResponse carry timestamp_millis, so any
        // sub-millisecond precision on capturedAt would be lost on the round trip -- truncating
        // here keeps the round-trip equality assertions in these tests exact.
        Instant capturedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new VideoFrame(streamId, sequence, capturedAt, 640, 480, format, ByteBuffer.wrap(new byte[]{1, 2, 3, 4}));
    }

    @Test
    void detectWithEchoServicerCompletesWithMappedEmptyResult() throws Exception {
        GrpcDetectionPort port = newPort(new EchoServicer());
        StreamId streamId = StreamId.random();
        VideoFrame frame = frame(streamId, 7, PixelFormat.JPEG);

        DetectionResult result = port.detect(frame, PipelineConfig.defaults())
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(streamId, result.streamId());
        assertEquals(7L, result.frameSequence());
        assertEquals(frame.capturedAt(), result.capturedAt());
        assertTrue(result.detections().isEmpty());
        assertEquals(Duration.ZERO, result.inferenceLatency());
    }

    @Test
    void detectWithFullDetectionsResponseMapsAllFields() throws Exception {
        GrpcDetectionPort port = newPort(new FixedDetectionServicer());
        StreamId streamId = StreamId.random();
        VideoFrame frame = frame(streamId, 3, PixelFormat.BGR24);

        DetectionResult result = port.detect(frame, PipelineConfig.defaults())
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(streamId, result.streamId());
        assertEquals(3L, result.frameSequence());
        assertEquals(frame.capturedAt(), result.capturedAt());
        assertEquals(Duration.ofMillis(42), result.inferenceLatency());
        assertEquals(1, result.detections().size());

        Detection detection = result.detections().get(0);
        assertEquals("person", detection.label());
        assertEquals(0.87, detection.confidence(), 1e-6);
        assertEquals(0.1, detection.box().x(), 1e-6);
        assertEquals(0.2, detection.box().y(), 1e-6);
        assertEquals(0.3, detection.box().width(), 1e-6);
        assertEquals(0.4, detection.box().height(), 1e-6);
        assertEquals("yolo11n", detection.model().id());
        assertEquals("v3", detection.model().version());
    }

    @Test
    void detectCorrelatesInterleavedResponsesBySequence() throws Exception {
        RecordingServicer servicer = new RecordingServicer(3);
        GrpcDetectionPort port = newPort(servicer);
        StreamId streamId = StreamId.random();
        PipelineConfig config = PipelineConfig.defaults();

        CompletionStage<DetectionResult> future0 = port.detect(frame(streamId, 0, PixelFormat.BGR24), config);
        CompletionStage<DetectionResult> future1 = port.detect(frame(streamId, 1, PixelFormat.BGR24), config);
        CompletionStage<DetectionResult> future2 = port.detect(frame(streamId, 2, PixelFormat.BGR24), config);

        assertTrue(servicer.allReceived.await(5, TimeUnit.SECONDS), "server did not receive all 3 requests in time");

        // Respond out of order -- each caller's future must still resolve to its own sequence.
        servicer.respond(2);
        servicer.respond(0);
        servicer.respond(1);

        assertEquals(2L, future2.toCompletableFuture().get(5, TimeUnit.SECONDS).frameSequence());
        assertEquals(0L, future0.toCompletableFuture().get(5, TimeUnit.SECONDS).frameSequence());
        assertEquals(1L, future1.toCompletableFuture().get(5, TimeUnit.SECONDS).frameSequence());
    }

    @Test
    void detectFailsPendingFuturesFastOnTransportErrorThenReopensAndRecovers() throws Exception {
        GrpcDetectionPort port = newPort(new FlakyOnceServicer());
        StreamId streamId = StreamId.random();
        PipelineConfig config = PipelineConfig.defaults();

        long startNanos = System.nanoTime();
        CompletionStage<DetectionResult> first = port.detect(frame(streamId, 0, PixelFormat.BGR24), config);
        // Bounded well under RESPONSE_TIMEOUT_SECONDS: this must fail via onError, not via the timeout.
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> first.toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertInstanceOf(StatusRuntimeException.class, ex.getCause());
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        assertTrue(elapsedMillis < TimeUnit.SECONDS.toMillis(GrpcDetectionPort.RESPONSE_TIMEOUT_SECONDS),
                "onError should fail the future promptly, not via the timeout; took " + elapsedMillis + "ms");

        // The dropped stream entry means this next call transparently reopens a fresh gRPC call.
        DetectionResult recovered = port.detect(frame(streamId, 1, PixelFormat.BGR24), config)
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1L, recovered.frameSequence());
    }

    @Test
    void detectOnUnresponsiveServicerTimesOutAfterResponseTimeout() throws Exception {
        GrpcDetectionPort port = newPort(new SilentServicer());
        long startNanos = System.nanoTime();

        CompletionStage<DetectionResult> stage =
                port.detect(frame(StreamId.random(), 0, PixelFormat.BGR24), PipelineConfig.defaults());
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> stage.toCompletableFuture().get(GrpcDetectionPort.RESPONSE_TIMEOUT_SECONDS + 5, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, ex.getCause());

        long elapsedSeconds = Duration.ofNanos(System.nanoTime() - startNanos).toSeconds();
        assertTrue(elapsedSeconds >= GrpcDetectionPort.RESPONSE_TIMEOUT_SECONDS,
                "expected a timeout after ~" + GrpcDetectionPort.RESPONSE_TIMEOUT_SECONDS + "s, took " + elapsedSeconds + "s");
    }

    @Test
    void detectOnUnsupportedPixelFormatFailsFastWithoutGrpcCall() throws Exception {
        EchoServicer servicer = new EchoServicer();
        GrpcDetectionPort port = newPort(servicer);

        CompletionStage<DetectionResult> stage =
                port.detect(frame(StreamId.random(), 0, PixelFormat.RGB24), PipelineConfig.defaults());
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> stage.toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertEquals(0, servicer.callsStarted.get(), "no gRPC call should have been made");
    }

    @Test
    void streamEndedFailsPendingFutureImmediatelyAndIsIdempotent() throws Exception {
        GrpcDetectionPort port = newPort(new SilentServicer());
        StreamId streamId = StreamId.random();

        CompletionStage<DetectionResult> stage =
                port.detect(frame(streamId, 0, PixelFormat.BGR24), PipelineConfig.defaults());
        port.streamEnded(streamId);

        // CompletableFuture.get() reports a CancellationException-completed future by throwing it
        // directly (unlike other exceptional completions, which get() wraps in ExecutionException).
        assertThrows(CancellationException.class, () -> stage.toCompletableFuture().get(1, TimeUnit.SECONDS));

        assertDoesNotThrow(() -> port.streamEnded(streamId));
    }

    @Test
    void closeIsIdempotentAndFailsSubsequentDetectCallsFast() throws Exception {
        GrpcDetectionPort port = newPort(new EchoServicer());

        assertDoesNotThrow(port::close);
        assertDoesNotThrow(port::close);

        CompletionStage<DetectionResult> stage =
                port.detect(frame(StreamId.random(), 0, PixelFormat.BGR24), PipelineConfig.defaults());
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> stage.toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }

    @Test
    void concurrentDetectCallsForSameStreamCompleteWithoutCorruption() throws Exception {
        GrpcDetectionPort port = newPort(new EchoServicer());
        StreamId streamId = StreamId.random();
        PipelineConfig config = PipelineConfig.defaults();
        int total = 50;

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<CompletionStage<DetectionResult>>> submitted = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                long sequence = i;
                submitted.add(executor.submit(() -> port.detect(frame(streamId, sequence, PixelFormat.BGR24), config)));
            }

            List<Long> observedSequences = new ArrayList<>();
            for (Future<CompletionStage<DetectionResult>> submission : submitted) {
                DetectionResult result = submission.get(5, TimeUnit.SECONDS)
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                observedSequences.add(result.frameSequence());
            }

            assertEquals(total, observedSequences.stream().collect(Collectors.toSet()).size(),
                    "every one of the " + total + " concurrent calls must resolve to its own distinct sequence");
        } finally {
            executor.shutdownNow();
        }
    }

    // -- test servicers -------------------------------------------------

    /** Echoes stream/sequence/timestamp/model back with an empty detections list. */
    private static final class EchoServicer extends InferenceGrpc.InferenceImplBase {
        final AtomicInteger callsStarted = new AtomicInteger();

        @Override
        public StreamObserver<FrameRequest> detectStream(StreamObserver<DetectionResponse> responseObserver) {
            callsStarted.incrementAndGet();
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

    /** Always responds with one fixed, fully-populated detection. */
    private static final class FixedDetectionServicer extends InferenceGrpc.InferenceImplBase {
        @Override
        public StreamObserver<FrameRequest> detectStream(StreamObserver<DetectionResponse> responseObserver) {
            return new StreamObserver<>() {
                @Override
                public void onNext(FrameRequest request) {
                    com.drones.vision.proto.v1.BoundingBox box = com.drones.vision.proto.v1.BoundingBox.newBuilder()
                            .setX(0.1f).setY(0.2f).setWidth(0.3f).setHeight(0.4f)
                            .build();
                    com.drones.vision.proto.v1.Detection detection = com.drones.vision.proto.v1.Detection.newBuilder()
                            .setLabel("person").setConfidence(0.87f).setBox(box)
                            .build();
                    responseObserver.onNext(DetectionResponse.newBuilder()
                            .setStreamId(request.getStreamId())
                            .setSequence(request.getSequence())
                            .setTimestampMillis(request.getTimestampMillis())
                            .setModelId("yolo11n")
                            .setModelVersion("v3")
                            .addDetections(detection)
                            .setInferenceMillis(42)
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

    /** Records every request it receives and lets the test trigger responses in any order/timing it chooses. */
    private static final class RecordingServicer extends InferenceGrpc.InferenceImplBase {
        final Map<Long, FrameRequest> received = new ConcurrentHashMap<>();
        final CountDownLatch allReceived;
        volatile StreamObserver<DetectionResponse> responseObserver;

        RecordingServicer(int expectedRequestCount) {
            this.allReceived = new CountDownLatch(expectedRequestCount);
        }

        @Override
        public StreamObserver<FrameRequest> detectStream(StreamObserver<DetectionResponse> responseObserver) {
            this.responseObserver = responseObserver;
            return new StreamObserver<>() {
                @Override
                public void onNext(FrameRequest request) {
                    received.put(request.getSequence(), request);
                    allReceived.countDown();
                }

                @Override
                public void onError(Throwable t) {
                    // test double: nothing to clean up
                }

                @Override
                public void onCompleted() {
                    // test double: no response needed
                }
            };
        }

        void respond(long sequence) {
            FrameRequest request = received.get(sequence);
            responseObserver.onNext(DetectionResponse.newBuilder()
                    .setStreamId(request.getStreamId())
                    .setSequence(request.getSequence())
                    .setTimestampMillis(request.getTimestampMillis())
                    .setModelId(request.getModelId())
                    .setModelVersion(request.getModelVersion())
                    .build());
        }
    }

    /** Never responds -- simulates a hung CV service so callers hit the response timeout. */
    private static final class SilentServicer extends InferenceGrpc.InferenceImplBase {
        @Override
        public StreamObserver<FrameRequest> detectStream(StreamObserver<DetectionResponse> responseObserver) {
            return new StreamObserver<>() {
                @Override
                public void onNext(FrameRequest request) {
                    // deliberately never responds
                }

                @Override
                public void onError(Throwable t) {
                    // test double: nothing to clean up
                }

                @Override
                public void onCompleted() {
                    // test double: nothing to clean up
                }
            };
        }
    }

    /** Fails the first call's stream right after the first request; every later call succeeds like an echo. */
    private static final class FlakyOnceServicer extends InferenceGrpc.InferenceImplBase {
        private final AtomicInteger callIndex = new AtomicInteger();

        @Override
        public StreamObserver<FrameRequest> detectStream(StreamObserver<DetectionResponse> responseObserver) {
            boolean firstCall = callIndex.getAndIncrement() == 0;
            return new StreamObserver<>() {
                @Override
                public void onNext(FrameRequest request) {
                    if (firstCall) {
                        responseObserver.onError(
                                Status.UNAVAILABLE.withDescription("simulated CV service outage").asRuntimeException());
                        return;
                    }
                    responseObserver.onNext(DetectionResponse.newBuilder()
                            .setStreamId(request.getStreamId())
                            .setSequence(request.getSequence())
                            .setTimestampMillis(request.getTimestampMillis())
                            .setModelId(request.getModelId())
                            .setModelVersion(request.getModelVersion())
                            .build());
                }

                @Override
                public void onError(Throwable t) {
                    // test double: nothing to clean up
                }

                @Override
                public void onCompleted() {
                    if (!firstCall) {
                        responseObserver.onCompleted();
                    }
                }
            };
        }
    }
}
