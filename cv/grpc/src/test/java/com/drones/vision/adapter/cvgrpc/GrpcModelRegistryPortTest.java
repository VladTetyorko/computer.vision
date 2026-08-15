package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.proto.v1.Ack;
import com.drones.vision.proto.v1.ModelInfo;
import com.drones.vision.proto.v1.ModelList;
import com.drones.vision.proto.v1.ModelRefMsg;
import com.drones.vision.proto.v1.TrainingGrpc;
import com.google.protobuf.Empty;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrpcModelRegistryPortTest {

    private final List<ManagedChannel> channels = new java.util.ArrayList<>();
    private final List<Server> servers = new java.util.ArrayList<>();

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

    private GrpcModelRegistryPort newPort(TrainingGrpc.TrainingImplBase service) throws Exception {
        String name = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(name).addService(service).build().start();
        servers.add(server);
        ManagedChannel channel = InProcessChannelBuilder.forName(name).build();
        channels.add(channel);
        return new GrpcModelRegistryPort(channel);
    }

    @Test
    void modelsMapsEveryEntryOfTheModelList() throws Exception {
        GrpcModelRegistryPort port = newPort(new FixedRosterServicer());

        List<ModelRef> models = port.models();

        assertEquals(2, models.size());
        assertEquals(new ModelRef("yolo11n", "v3"), models.get(0));
        assertEquals(new ModelRef("yolo11n", "v4-rc1"), models.get(1));
    }

    @Test
    void modelsReturnsEmptyListWhenRosterIsEmpty() throws Exception {
        GrpcModelRegistryPort port = newPort(new EmptyRosterServicer());

        assertTrue(port.models().isEmpty());
    }

    @Test
    void activeReturnsTheStageActiveEntry() throws Exception {
        GrpcModelRegistryPort port = newPort(new FixedRosterServicer());

        assertEquals(java.util.Optional.of(new ModelRef("yolo11n", "v3")), port.active());
    }

    @Test
    void activeIsEmptyWhenRosterIsEmpty() throws Exception {
        GrpcModelRegistryPort port = newPort(new EmptyRosterServicer());

        assertTrue(port.active().isEmpty());
    }

    @Test
    void modelsPropagatesTransportFailureRatherThanDegradingToEmpty() throws Exception {
        GrpcModelRegistryPort port = newPort(new FailingServicer());

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, port::models);
        assertEquals(Status.Code.UNAVAILABLE, ex.getStatus().getCode());
    }

    @Test
    void promoteSendsTheModelRefOnTheWire() throws Exception {
        RecordingPromoteServicer servicer = new RecordingPromoteServicer(true, "");
        GrpcModelRegistryPort port = newPort(servicer);
        ModelRef ref = new ModelRef("yolo11n", "v4-rc1");

        port.promote(ref);

        ModelRefMsg sent = servicer.received.get();
        assertEquals("yolo11n", sent.getId());
        assertEquals("v4-rc1", sent.getVersion());
    }

    @Test
    void promoteThrowsIllegalStateExceptionWhenCvServiceRefuses() throws Exception {
        GrpcModelRegistryPort port = newPort(new RecordingPromoteServicer(false, "unknown model id"));
        ModelRef ref = new ModelRef("nope", "v1");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> port.promote(ref));
        assertTrue(ex.getMessage().contains("unknown model id"),
                "message should surface cv-service's refusal reason: " + ex.getMessage());
    }

    @Test
    void promotePropagatesTransportFailure() throws Exception {
        GrpcModelRegistryPort port = newPort(new FailingServicer());
        ModelRef ref = new ModelRef("yolo11n", "v3");

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, () -> port.promote(ref));
        assertEquals(Status.Code.UNAVAILABLE, ex.getStatus().getCode());
    }

    @Test
    void promoteRejectsNullRef() throws Exception {
        GrpcModelRegistryPort port = newPort(new RecordingPromoteServicer(true, ""));

        assertThrows(NullPointerException.class, () -> port.promote(null));
    }

    @Test
    void constructorRejectsNullChannel() {
        assertThrows(NullPointerException.class, () -> new GrpcModelRegistryPort(null));
    }

    @Test
    void modelsOnHungServicerFailsAfterCallDeadlineInsteadOfBlockingForever() throws Exception {
        GrpcModelRegistryPort port = newPort(new SilentServicer());

        long startNanos = System.nanoTime();
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, port::models);
        assertEquals(Status.Code.DEADLINE_EXCEEDED, ex.getStatus().getCode());

        long elapsedSeconds = Duration.ofNanos(System.nanoTime() - startNanos).toSeconds();
        assertTrue(elapsedSeconds < GrpcModelRegistryPort.CALL_TIMEOUT_SECONDS + 5,
                "expected the call deadline to bound the wait, not block indefinitely; took " + elapsedSeconds + "s");
    }

    // -- test servicers -------------------------------------------------

    /** Answers ListModels with a fixed two-entry roster; PromoteModel is unimplemented (unused by these tests). */
    private static final class FixedRosterServicer extends TrainingGrpc.TrainingImplBase {
        @Override
        public void listModels(Empty request, StreamObserver<ModelList> responseObserver) {
            responseObserver.onNext(ModelList.newBuilder()
                    .addModels(ModelInfo.newBuilder().setId("yolo11n").setVersion("v3").setStage("active").build())
                    .addModels(ModelInfo.newBuilder().setId("yolo11n").setVersion("v4-rc1").setStage("available").build())
                    .build());
            responseObserver.onCompleted();
        }
    }

    /** Answers ListModels with an empty roster (a live cv-service with nothing registered yet). */
    private static final class EmptyRosterServicer extends TrainingGrpc.TrainingImplBase {
        @Override
        public void listModels(Empty request, StreamObserver<ModelList> responseObserver) {
            responseObserver.onNext(ModelList.getDefaultInstance());
            responseObserver.onCompleted();
        }
    }

    /** Fails every RPC with UNAVAILABLE, simulating cv-service being down. */
    private static final class FailingServicer extends TrainingGrpc.TrainingImplBase {
        @Override
        public void listModels(Empty request, StreamObserver<ModelList> responseObserver) {
            responseObserver.onError(Status.UNAVAILABLE.withDescription("simulated cv-service outage").asRuntimeException());
        }

        @Override
        public void promoteModel(ModelRefMsg request, StreamObserver<Ack> responseObserver) {
            responseObserver.onError(Status.UNAVAILABLE.withDescription("simulated cv-service outage").asRuntimeException());
        }
    }

    /** Never responds to any RPC -- simulates a hung cv-service so the call deadline must kick in. */
    private static final class SilentServicer extends TrainingGrpc.TrainingImplBase {
        // both RPCs deliberately left unimplemented-but-never-erroring: the client's own deadline
        // must be what ends the call, not a server-side response of any kind.
        @Override
        public void listModels(Empty request, StreamObserver<ModelList> responseObserver) {
            // deliberately never responds
        }
    }

    /** Records the exact ModelRefMsg it receives and answers PromoteModel with a fixed Ack. */
    private static final class RecordingPromoteServicer extends TrainingGrpc.TrainingImplBase {
        final AtomicReference<ModelRefMsg> received = new AtomicReference<>();
        private final boolean ok;
        private final String message;

        RecordingPromoteServicer(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }

        @Override
        public void promoteModel(ModelRefMsg request, StreamObserver<Ack> responseObserver) {
            received.set(request);
            responseObserver.onNext(Ack.newBuilder().setOk(ok).setMessage(message).build());
            responseObserver.onCompleted();
        }
    }
}
