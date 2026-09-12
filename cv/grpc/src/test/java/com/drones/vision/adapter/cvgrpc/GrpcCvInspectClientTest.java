package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.proto.v1.InferenceGrpc;
import com.drones.vision.proto.v1.InspectRequest;
import com.drones.vision.proto.v1.InspectResponse;
import com.drones.vision.proto.v1.ProcessFacts;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Same in-process-gRPC harness as {@link GrpcModelRegistryPortTest} — see that class's own
 * javadoc for why a real (if in-process) server, not a mock, exercises this class's stub wiring
 * honestly.
 */
class GrpcCvInspectClientTest {

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

    private GrpcCvInspectClient newClient(InferenceGrpc.InferenceImplBase service) throws Exception {
        String name = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(name).addService(service).build().start();
        servers.add(server);
        ManagedChannel channel = InProcessChannelBuilder.forName(name).build();
        channels.add(channel);
        return new GrpcCvInspectClient(channel);
    }

    private static final class FixedFactsServicer extends InferenceGrpc.InferenceImplBase {
        private final AtomicReference<InspectRequest> lastRequest = new AtomicReference<>();

        @Override
        public void inspect(InspectRequest request, StreamObserver<InspectResponse> responseObserver) {
            lastRequest.set(request);
            ProcessFacts facts = ProcessFacts.newBuilder()
                    .setDetectorClient("local")
                    .setSessions(3)
                    .setGatePermits(2)
                    .setLedgerRing(64)
                    .addStreamIds("stream-a")
                    .addStreamIds("stream-b")
                    .build();
            responseObserver.onNext(InspectResponse.newBuilder().setProcess(facts).setFound(true).build());
            responseObserver.onCompleted();
        }
    }

    private static final class FailingServicer extends InferenceGrpc.InferenceImplBase {
        @Override
        public void inspect(InspectRequest request, StreamObserver<InspectResponse> responseObserver) {
            responseObserver.onError(Status.UNAVAILABLE.withDescription("cv-service down").asRuntimeException());
        }
    }

    @Test
    void processFactsRoundTripsEveryProcessFactsField() throws Exception {
        GrpcCvInspectClient client = newClient(new FixedFactsServicer());

        ProcessFacts facts = client.processFacts().getProcess();

        assertEquals("local", facts.getDetectorClient());
        assertEquals(3, facts.getSessions());
        assertEquals(2, facts.getGatePermits());
        assertEquals(64, facts.getLedgerRing());
        assertEquals(List.of("stream-a", "stream-b"), facts.getStreamIdsList());
    }

    @Test
    void processFactsSendsAnEmptyStreamIdAndZeroFramesForTheProcessWideQuery() throws Exception {
        FixedFactsServicer servicer = new FixedFactsServicer();
        GrpcCvInspectClient client = newClient(servicer);

        client.processFacts();

        assertEquals("", servicer.lastRequest.get().getStreamId());
        assertEquals(0, servicer.lastRequest.get().getFrames());
    }

    @Test
    void inspectSendsTheNamedStreamIdAndFrameCount() throws Exception {
        FixedFactsServicer servicer = new FixedFactsServicer();
        GrpcCvInspectClient client = newClient(servicer);

        client.inspect("stream-a", 10);

        assertEquals("stream-a", servicer.lastRequest.get().getStreamId());
        assertEquals(10, servicer.lastRequest.get().getFrames());
    }

    @Test
    void processFactsPropagatesTransportFailureRatherThanDegradingSilently() throws Exception {
        GrpcCvInspectClient client = newClient(new FailingServicer());

        StatusRuntimeException e = assertThrows(StatusRuntimeException.class, client::processFacts);
        assertTrue(e.getStatus().getCode() == Status.Code.UNAVAILABLE);
    }
}
