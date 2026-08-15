package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.perception.domain.model.CameraAttitude;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.EventRuleConfig;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.TrackingConfig;
import com.drones.vision.proto.v1.DetectionResponse;
import com.drones.vision.proto.v1.InferenceGrpc;
import com.drones.vision.proto.v1.PullControl;
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

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link GrpcPulledDetectionPort} against an in-process {@code Inference/DetectPulled} fake —
 * no real TCP needed, same reasoning {@link GrpcModelRegistryPortTest}/{@link GrpcTrainingPortTest}
 * give for their own in-process doubles: this is a control-plane RPC (small {@code PullControl}
 * messages and small {@code DetectionResponse}s), not the payload-shrinking/keepalive-sensitive path
 * {@code GrpcDetectionPortTest} exercises over real TCP for {@code DetectStream}.
 */
class GrpcPulledDetectionPortTest {

    private static final int AWAIT_SECONDS = 5;

    private final List<ManagedChannel> channels = new ArrayList<>();
    private final List<Server> servers = new ArrayList<>();

    @AfterEach
    void tearDown() throws InterruptedException {
        for (ManagedChannel channel : channels) {
            channel.shutdownNow();
        }
        for (Server server : servers) {
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private GrpcPulledDetectionPort newPort(BindableService service) throws Exception {
        String name = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(name).addService(service).build().start();
        servers.add(server);
        ManagedChannel channel = InProcessChannelBuilder.forName(name).build();
        channels.add(channel);
        return new GrpcPulledDetectionPort(channel, GrpcCvSettings.defaults());
    }

    private static PipelineConfig config(String modelId, String modelVersion, double confidence, int fps) {
        return new PipelineConfig(new ModelRef(modelId, modelVersion), confidence, fps, 2, true, Set.of(),
                EventRuleConfig.defaults(), true, true, TrackingConfig.defaults());
    }

    private static CameraAttitude attitude(double yaw) {
        return new CameraAttitude(yaw, 0, 0, 60, 34, Instant.now());
    }

    // -- first-message-only source_url ----------------------------------

    @Test
    void sourceUrlIsSentOnTheFirstMessageOnlyAndNeverRestated() throws Exception {
        RecordingServicer servicer = new RecordingServicer();
        GrpcPulledDetectionPort port = newPort(servicer);
        StreamId id = StreamId.random();
        URI sourceUrl = URI.create("rtsp://localhost:8554/" + id.value());

        port.open(id, sourceUrl, config("yolo26n.pt", "latest", 0.4, 10));
        awaitAtLeast(servicer, 1);
        port.reconfigure(id, config("yolo26n.pt", "v2", 0.5, 15));
        awaitAtLeast(servicer, 2);

        List<PullControl> received = servicer.received();
        assertEquals(sourceUrl.toString(), received.get(0).getSourceUrl(),
                "the first PullControl must carry source_url");
        assertTrue(received.get(1).getSourceUrl().isEmpty(),
                "a later PullControl must not restate source_url");
    }

    @Test
    void streamIdIsSentOnEveryMessage() throws Exception {
        RecordingServicer servicer = new RecordingServicer();
        GrpcPulledDetectionPort port = newPort(servicer);
        StreamId id = StreamId.random();

        port.open(id, URI.create("rtsp://localhost:8554/" + id.value()), config("m", "v1", 0.4, 10));
        port.attitude(id, attitude(12.0));
        awaitAtLeast(servicer, 2);

        for (PullControl control : servicer.received()) {
            assertEquals(id.value().toString(), control.getStreamId());
        }
    }

    // -- hot restatement on reconfigure/attitude -------------------------

    @Test
    void reconfigureRestatesHotFieldsAndCarriesTheLastKnownAttitudeAlong() throws Exception {
        RecordingServicer servicer = new RecordingServicer();
        GrpcPulledDetectionPort port = newPort(servicer);
        StreamId id = StreamId.random();

        port.open(id, URI.create("rtsp://localhost:8554/" + id.value()), config("m", "v1", 0.4, 10));
        port.attitude(id, attitude(45.0));
        awaitAtLeast(servicer, 2);
        port.reconfigure(id, config("m", "v2", 0.6, 20));
        awaitAtLeast(servicer, 3);

        PullControl afterReconfigure = servicer.received().get(2);
        assertEquals("v2", afterReconfigure.getModelVersion());
        assertEquals(0.6f, afterReconfigure.getConfidenceThreshold(), 1e-6);
        assertEquals(20f, afterReconfigure.getTargetFps(), 1e-6);
        assertTrue(afterReconfigure.hasCameraPose(), "the last-known attitude must ride along on a reconfigure");
        assertEquals(45f, afterReconfigure.getCameraPose().getYawDegrees(), 1e-3);
    }

    @Test
    void attitudeRestatesTheLastKnownConfigAlongWithTheNewPose() throws Exception {
        RecordingServicer servicer = new RecordingServicer();
        GrpcPulledDetectionPort port = newPort(servicer);
        StreamId id = StreamId.random();

        port.open(id, URI.create("rtsp://localhost:8554/" + id.value()), config("m", "v3", 0.7, 12));
        awaitAtLeast(servicer, 1);
        port.attitude(id, attitude(-90.0));
        awaitAtLeast(servicer, 2);

        PullControl afterAttitude = servicer.received().get(1);
        assertEquals("v3", afterAttitude.getModelVersion(), "the last-known config must ride along on an attitude update");
        assertEquals(0.7f, afterAttitude.getConfidenceThreshold(), 1e-6);
        assertTrue(afterAttitude.hasCameraPose());
        assertEquals(-90f, afterAttitude.getCameraPose().getYawDegrees(), 1e-3);
    }

    @Test
    void reconfigureForAStreamWithNoOpenPullIsANoOp() throws Exception {
        RecordingServicer servicer = new RecordingServicer();
        GrpcPulledDetectionPort port = newPort(servicer);

        port.reconfigure(StreamId.random(), config("m", "v1", 0.4, 10));
        port.attitude(StreamId.random(), attitude(1.0));

        assertTrue(servicer.received().isEmpty(), "no session was ever opened for either id");
    }

    // -- stop=true on close ------------------------------------------------

    @Test
    void closeSendsStopTrueAndHalfClosesTheCall() throws Exception {
        RecordingServicer servicer = new RecordingServicer();
        GrpcPulledDetectionPort port = newPort(servicer);
        StreamId id = StreamId.random();

        port.open(id, URI.create("rtsp://localhost:8554/" + id.value()), config("m", "v1", 0.4, 10));
        awaitAtLeast(servicer, 1);
        port.close(id);

        assertTrue(servicer.clientHalfClosed().await(AWAIT_SECONDS, TimeUnit.SECONDS),
                "expected the client to half-close after close()");
        PullControl last = servicer.received().get(servicer.received().size() - 1);
        assertTrue(last.getStop(), "the last PullControl before half-close must carry stop=true");
    }

    @Test
    void closeIsIdempotent() throws Exception {
        RecordingServicer servicer = new RecordingServicer();
        GrpcPulledDetectionPort port = newPort(servicer);
        StreamId id = StreamId.random();

        port.open(id, URI.create("rtsp://localhost:8554/" + id.value()), config("m", "v1", 0.4, 10));
        awaitAtLeast(servicer, 1);
        port.close(id);
        assertTrue(servicer.clientHalfClosed().await(AWAIT_SECONDS, TimeUnit.SECONDS));

        // A second close (or one for a never-opened id) must be a no-op, not throw.
        port.close(id);
        port.close(StreamId.random());
    }

    @Test
    void closeForAStreamWithNoOpenPullIsANoOp() throws Exception {
        RecordingServicer servicer = new RecordingServicer();
        GrpcPulledDetectionPort port = newPort(servicer);

        port.close(StreamId.random());

        assertTrue(servicer.received().isEmpty());
    }

    // -- UNAVAILABLE surfaces as onError -----------------------------------

    @Test
    void unavailableFromTheServerSurfacesAsOnErrorRatherThanBeingRetriedInternally() throws Exception {
        UnavailableServicer servicer = new UnavailableServicer();
        GrpcPulledDetectionPort port = newPort(servicer);
        StreamId id = StreamId.random();

        CapturingSubscriber subscriber = new CapturingSubscriber();
        Flow.Publisher<DetectionResult> publisher =
                port.open(id, URI.create("rtsp://localhost:8554/" + id.value()), config("m", "v1", 0.4, 10));
        publisher.subscribe(subscriber);

        assertTrue(subscriber.terminal.await(AWAIT_SECONDS, TimeUnit.SECONDS),
                "expected onError within " + AWAIT_SECONDS + "s");
        StatusRuntimeException ex = assertInstanceOf(StatusRuntimeException.class, subscriber.error.get());
        assertEquals(Status.Code.UNAVAILABLE, ex.getStatus().getCode());
        assertTrue(subscriber.results.isEmpty(), "no results should have been delivered before the failure");
    }

    @Test
    void aResponseDeliveredBeforeTheStreamEndsReachesTheSubscriber() throws Exception {
        RecordingServicer servicer = new RecordingServicer();
        GrpcPulledDetectionPort port = newPort(servicer);
        StreamId id = StreamId.random();

        CapturingSubscriber subscriber = new CapturingSubscriber();
        Flow.Publisher<DetectionResult> publisher =
                port.open(id, URI.create("rtsp://localhost:8554/" + id.value()), config("m", "v1", 0.4, 10));
        publisher.subscribe(subscriber);
        awaitAtLeast(servicer, 1);

        servicer.pushResponse(DetectionResponse.newBuilder()
                .setStreamId(id.value().toString())
                .setSequence(1)
                .setTimestampMillis(Instant.now().toEpochMilli())
                .setModelId("m")
                .setModelVersion("v1")
                .setInferenceMillis(9)
                .build());

        assertTrue(subscriber.atLeastOne.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(1, subscriber.results.get(0).frameSequence());
        assertEquals(1, subscriber.terminal.getCount(), "the publisher must still be open after one response");
    }

    private static void awaitAtLeast(RecordingServicer servicer, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (servicer.received().size() < count) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("expected at least " + count + " PullControl message(s), got "
                        + servicer.received().size());
            }
            Thread.sleep(10);
        }
    }

    // -- test servicers ----------------------------------------------------

    /** Records every PullControl it receives and reports when the client half-closes. */
    private static class RecordingServicer extends InferenceGrpc.InferenceImplBase {
        private final List<PullControl> received = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch clientHalfClosed = new CountDownLatch(1);
        private volatile StreamObserver<DetectionResponse> responseObserver;

        @Override
        public StreamObserver<PullControl> detectPulled(StreamObserver<DetectionResponse> responseObserver) {
            this.responseObserver = responseObserver;
            return new StreamObserver<>() {
                @Override
                public void onNext(PullControl value) {
                    received.add(value);
                }

                @Override
                public void onError(Throwable t) {
                    // unused by these tests
                }

                @Override
                public void onCompleted() {
                    clientHalfClosed.countDown();
                    responseObserver.onCompleted();
                }
            };
        }

        List<PullControl> received() {
            return List.copyOf(received);
        }

        CountDownLatch clientHalfClosed() {
            return clientHalfClosed;
        }

        void pushResponse(DetectionResponse response) {
            responseObserver.onNext(response);
        }
    }

    /** Answers the very first PullControl with UNAVAILABLE, as if the pulled source were unopenable. */
    private static final class UnavailableServicer extends InferenceGrpc.InferenceImplBase {
        @Override
        public StreamObserver<PullControl> detectPulled(StreamObserver<DetectionResponse> responseObserver) {
            return new StreamObserver<>() {
                @Override
                public void onNext(PullControl value) {
                    responseObserver.onError(Status.UNAVAILABLE
                            .withDescription("simulated: pulled source unopenable").asRuntimeException());
                }

                @Override
                public void onError(Throwable t) {
                    // unused
                }

                @Override
                public void onCompleted() {
                    // unused
                }
            };
        }
    }

    /** Collects every delivered {@code DetectionResult} and the terminal signal, requesting unbounded demand. */
    private static final class CapturingSubscriber implements Flow.Subscriber<DetectionResult> {
        final List<DetectionResult> results = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch atLeastOne = new CountDownLatch(1);
        final CountDownLatch terminal = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(DetectionResult item) {
            results.add(item);
            atLeastOne.countDown();
        }

        @Override
        public void onError(Throwable throwable) {
            error.set(throwable);
            terminal.countDown();
        }

        @Override
        public void onComplete() {
            terminal.countDown();
        }
    }
}
