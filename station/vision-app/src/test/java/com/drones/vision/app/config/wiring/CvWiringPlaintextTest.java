package com.drones.vision.app.config.wiring;

import com.drones.vision.app.config.properties.VisionCvProperties;
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
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit test (no Spring context, mirroring {@link SimulationResumeWiringConfigurationTest}'s
 * "call the {@code @Bean} method directly" reasoning) proving {@code vision.cv.plaintext} actually
 * changes what {@link CvWiring#cvGrpcChannel} builds (docs/plans/active/CV-RECONNECT-PLAN.md §3.3a item 1) —
 * before this wave, {@code .usePlaintext()} was called unconditionally regardless of the property,
 * so {@code plaintext: false} silently left the connection unencrypted.
 *
 * <p>{@link ManagedChannel} exposes no getter for its own TLS/plaintext negotiation mode, so the
 * only honest way to prove the property took effect is behavioral: open a real plaintext (h2c, no
 * TLS) in-test gRPC server — the same {@code ServerBuilder.forPort(0)} pattern {@link
 * com.drones.vision.app.CvDetectionE2ETest} uses — and drive a real bidi call through a channel
 * {@link CvWiring#cvGrpcChannel} built. With {@code plaintext=true} (the default) the client speaks
 * plaintext HTTP/2 and the call completes normally. With {@code plaintext=false} the client attempts
 * a TLS handshake against a server that only speaks plaintext HTTP/2 — the handshake cannot succeed,
 * so the call fails (bounded here by an explicit call deadline rather than left to hang).
 */
class CvWiringPlaintextTest {

    private final CvWiring cvWiring = new CvWiring();

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    @Test
    void plaintextTrueReachesAPlainHttp2Server() throws IOException, InterruptedException {
        server = ServerBuilder.forPort(0).addService(new EchoServicer()).build().start();
        channel = cvWiring.cvGrpcChannel(propertiesFor(server.getPort(), true));

        assertTrue(detectOnce(channel),
                "expected a plaintext=true channel to complete a real RPC against a plaintext server");
    }

    @Test
    void plaintextFalseCannotReachThatSamePlainHttp2Server() throws IOException, InterruptedException {
        server = ServerBuilder.forPort(0).addService(new EchoServicer()).build().start();
        channel = cvWiring.cvGrpcChannel(propertiesFor(server.getPort(), false));

        assertFalse(detectOnce(channel),
                "a plaintext=false channel attempts a TLS handshake against a plaintext server -- it "
                        + "must not complete, proving .usePlaintext() is no longer called unconditionally "
                        + "regardless of vision.cv.plaintext");
    }

    /** Sends one {@code FrameRequest} and reports whether a response (not an error) came back within a bound. */
    private static boolean detectOnce(ManagedChannel channel) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        InferenceGrpc.InferenceStub stub =
                InferenceGrpc.newStub(channel).withDeadlineAfter(2, TimeUnit.SECONDS);
        StreamObserver<FrameRequest> requestObserver = stub.detectStream(new StreamObserver<>() {
            @Override
            public void onNext(DetectionResponse value) {
                latch.countDown();
            }

            @Override
            public void onError(Throwable t) {
                error.set(t);
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        });
        requestObserver.onNext(FrameRequest.newBuilder().setStreamId("plaintext-test").setSequence(1).build());
        boolean signaled = latch.await(3, TimeUnit.SECONDS);
        return signaled && error.get() == null;
    }

    private static VisionCvProperties propertiesFor(int port, boolean plaintext) {
        return new VisionCvProperties(true, "localhost:" + port, 640, 0.8f, "auto", "push",
                Duration.ofSeconds(2), Duration.ofSeconds(20), Duration.ofSeconds(5), true, Duration.ofSeconds(5),
                plaintext, null, null, null);
    }

    /** Echoes each {@code FrameRequest} straight back — same shape as {@code CvDetectionE2ETest}'s own double. */
    private static final class EchoServicer extends InferenceGrpc.InferenceImplBase {
        @Override
        public StreamObserver<FrameRequest> detectStream(StreamObserver<DetectionResponse> responseObserver) {
            return new StreamObserver<>() {
                @Override
                public void onNext(FrameRequest request) {
                    responseObserver.onNext(DetectionResponse.newBuilder()
                            .setStreamId(request.getStreamId())
                            .setSequence(request.getSequence())
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
