package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.domain.model.JobState;
import com.drones.vision.domain.model.TrainingJobSpec;
import com.drones.vision.domain.model.TrainingProgress;
import com.drones.vision.proto.v1.TrainingGrpc;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrpcTrainingPortTest {

    private static final double DELTA = 1e-6;

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

    private GrpcTrainingPort newPort(TrainingGrpc.TrainingImplBase service) throws Exception {
        String name = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(name).addService(service).build().start();
        servers.add(server);
        ManagedChannel channel = InProcessChannelBuilder.forName(name).build();
        channels.add(channel);
        return new GrpcTrainingPort(channel);
    }

    @Test
    void constructorRejectsNullChannel() {
        assertThrows(NullPointerException.class, () -> new GrpcTrainingPort(null));
    }

    @Test
    void startTrainingRejectsNullSpec() throws Exception {
        GrpcTrainingPort port = newPort(new UnusedServicer());

        assertThrows(NullPointerException.class, () -> port.startTraining(null, progress -> { }));
    }

    @Test
    void startTrainingRejectsNullOnProgress() throws Exception {
        GrpcTrainingPort port = newPort(new UnusedServicer());
        TrainingJobSpec spec = new TrainingJobSpec("yolo11n", "dataset-1", 10);

        assertThrows(NullPointerException.class, () -> port.startTraining(spec, null));
    }

    @Test
    void startTrainingSendsTheExactSpecOnTheWire() throws Exception {
        RecordingServicer servicer = new RecordingServicer(List.of(runningMessage("job-1", 1, 3, 0.5f, 0.1f)));
        GrpcTrainingPort port = newPort(servicer);
        TrainingJobSpec spec = new TrainingJobSpec("yolo11n", "dataset-42", 3);

        port.startTraining(spec, progress -> { });

        com.drones.vision.proto.v1.TrainingJobSpec sent = servicer.received.get();
        assertEquals("yolo11n", sent.getBaseModel());
        assertEquals("dataset-42", sent.getDatasetId());
        assertEquals(3, sent.getEpochs());
    }

    @Test
    void everyStreamedMessageReachesOnProgressInOrderWithCorrectMapping() throws Exception {
        List<com.drones.vision.proto.v1.TrainingProgress> wire = List.of(
                runningMessage("job-1", 1, 3, 0.9f, 0.10f),
                runningMessage("job-1", 2, 3, 0.6f, 0.35f),
                runningMessage("job-1", 3, 3, 0.4f, 0.55f),
                terminalMessage("job-1", 3, 3, com.drones.vision.proto.v1.JobState.SUCCEEDED, "yolo11n-v5"));
        GrpcTrainingPort port = newPort(new RecordingServicer(wire));
        List<TrainingProgress> received = new CopyOnWriteArrayList<>();

        port.startTraining(new TrainingJobSpec("yolo11n", "dataset-1", 3), received::add);

        assertEquals(4, received.size());
        assertProgressMatches(wire.get(0), received.get(0), JobState.RUNNING);
        assertProgressMatches(wire.get(1), received.get(1), JobState.RUNNING);
        assertProgressMatches(wire.get(2), received.get(2), JobState.RUNNING);
        assertProgressMatches(wire.get(3), received.get(3), JobState.SUCCEEDED);
        assertEquals("yolo11n-v5", received.get(3).message());
    }

    @Test
    void terminalFailedIsDeliveredWithItsFailureMessage() throws Exception {
        List<com.drones.vision.proto.v1.TrainingProgress> wire = List.of(
                runningMessage("job-2", 1, 5, 1.2f, 0.0f),
                terminalMessage("job-2", 1, 5, com.drones.vision.proto.v1.JobState.FAILED, "dataset not found"));
        GrpcTrainingPort port = newPort(new RecordingServicer(wire));
        List<TrainingProgress> received = new CopyOnWriteArrayList<>();

        port.startTraining(new TrainingJobSpec("yolo11n", "dataset-missing", 5), received::add);

        assertEquals(2, received.size());
        assertEquals(JobState.FAILED, received.get(1).state());
        assertEquals("dataset not found", received.get(1).message());
    }

    @Test
    void unspecifiedJobStateIsMappedDefensivelyToRunning() throws Exception {
        // Deliberately leave `state` unset on the builder -> wire value 0 -> JOB_STATE_UNSPECIFIED.
        com.drones.vision.proto.v1.TrainingProgress unspecified = com.drones.vision.proto.v1.TrainingProgress.newBuilder()
                .setJobId("job-3")
                .setEpoch(1)
                .setTotalEpochs(1)
                .setLoss(0.5f)
                .setMap50(0.2f)
                .setMessage("")
                .build();
        GrpcTrainingPort port = newPort(new RecordingServicer(List.of(unspecified)));
        List<TrainingProgress> received = new CopyOnWriteArrayList<>();

        port.startTraining(new TrainingJobSpec("yolo11n", "dataset-1", 1), received::add);

        assertEquals(1, received.size());
        assertEquals(JobState.RUNNING, received.get(0).state());
    }

    @Test
    void cancellationEndsTheStreamWithNoTerminalMessageAndReturnsNormally() throws Exception {
        // Server completes the call after only RUNNING messages -- no SUCCEEDED/FAILED -- mirroring
        // cv-service's documented "cancellation ends the stream with no terminal message" behavior.
        List<com.drones.vision.proto.v1.TrainingProgress> wire = List.of(
                runningMessage("job-4", 1, 10, 1.0f, 0.0f),
                runningMessage("job-4", 2, 10, 0.9f, 0.05f));
        GrpcTrainingPort port = newPort(new RecordingServicer(wire));
        List<TrainingProgress> received = new CopyOnWriteArrayList<>();

        port.startTraining(new TrainingJobSpec("yolo11n", "dataset-1", 10), received::add);

        assertEquals(2, received.size());
        assertTrue(received.stream().noneMatch(p -> p.state() != JobState.RUNNING),
                "no terminal state should have been synthesized for an early-ended stream");
    }

    @Test
    void midStreamTransportErrorPropagatesAfterDeliveringPriorMessages() throws Exception {
        List<com.drones.vision.proto.v1.TrainingProgress> before = List.of(
                runningMessage("job-5", 1, 4, 2.0f, 0.0f));
        GrpcTrainingPort port = newPort(new FailingMidStreamServicer(before));
        List<TrainingProgress> received = new CopyOnWriteArrayList<>();

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> port.startTraining(new TrainingJobSpec("yolo11n", "dataset-1", 4), received::add));

        assertEquals(Status.Code.UNAVAILABLE, ex.getStatus().getCode());
        assertEquals(1, received.size());
        assertEquals("job-5", received.get(0).jobId());
    }

    @Test
    void startTrainingPropagatesFailureToStart() throws Exception {
        GrpcTrainingPort port = newPort(new FailImmediatelyServicer());

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> port.startTraining(new TrainingJobSpec("yolo11n", "dataset-1", 1), progress -> { }));

        assertEquals(Status.Code.UNAVAILABLE, ex.getStatus().getCode());
    }

    @Test
    void longRunningJobIsNotKilledByAnyPerCallDeadline() throws Exception {
        // GrpcModelRegistryPort bounds its (short, control-plane) RPCs to CALL_TIMEOUT_SECONDS=10s.
        // Training must not inherit anything like that: this servicer sleeps past that bound between
        // messages, proving no deadline was armed on this RPC. Costs >10s of wall time by design --
        // the whole point is to exercise real elapsed time, not simulate it.
        long sleepSeconds = GrpcModelRegistryPort.CALL_TIMEOUT_SECONDS + 2;
        GrpcTrainingPort port = newPort(new SlowServicer(sleepSeconds));
        List<TrainingProgress> received = new CopyOnWriteArrayList<>();

        long startNanos = System.nanoTime();
        port.startTraining(new TrainingJobSpec("yolo11n", "dataset-1", 1), received::add);
        long elapsedSeconds = Duration.ofNanos(System.nanoTime() - startNanos).toSeconds();

        assertTrue(elapsedSeconds >= sleepSeconds,
                "expected the call to survive past the control-plane deadline bound; took only " + elapsedSeconds + "s");
        assertEquals(2, received.size());
        assertEquals(JobState.SUCCEEDED, received.get(1).state());
    }

    // -- helpers ----------------------------------------------------------

    private static void assertProgressMatches(com.drones.vision.proto.v1.TrainingProgress wire,
                                                TrainingProgress domain, JobState expectedState) {
        assertEquals(wire.getJobId(), domain.jobId());
        assertEquals(wire.getEpoch(), domain.epoch());
        assertEquals(wire.getTotalEpochs(), domain.totalEpochs());
        assertEquals(wire.getLoss(), domain.loss(), DELTA);
        assertEquals(wire.getMap50(), domain.map50(), DELTA);
        assertEquals(expectedState, domain.state());
    }

    private static com.drones.vision.proto.v1.TrainingProgress runningMessage(
            String jobId, int epoch, int totalEpochs, float loss, float map50) {
        return com.drones.vision.proto.v1.TrainingProgress.newBuilder()
                .setJobId(jobId)
                .setEpoch(epoch)
                .setTotalEpochs(totalEpochs)
                .setLoss(loss)
                .setMap50(map50)
                .setState(com.drones.vision.proto.v1.JobState.RUNNING)
                .setMessage("")
                .build();
    }

    private static com.drones.vision.proto.v1.TrainingProgress terminalMessage(
            String jobId, int epoch, int totalEpochs, com.drones.vision.proto.v1.JobState state, String message) {
        return com.drones.vision.proto.v1.TrainingProgress.newBuilder()
                .setJobId(jobId)
                .setEpoch(epoch)
                .setTotalEpochs(totalEpochs)
                .setLoss(0f)
                .setMap50(0f)
                .setState(state)
                .setMessage(message)
                .build();
    }

    // -- test servicers -----------------------------------------------------

    /** Never invoked -- used for tests where validation must reject before any RPC is made. */
    private static final class UnusedServicer extends TrainingGrpc.TrainingImplBase {
    }

    /** Streams a fixed sequence of messages then completes normally; records the request it received. */
    private static final class RecordingServicer extends TrainingGrpc.TrainingImplBase {
        final AtomicReference<com.drones.vision.proto.v1.TrainingJobSpec> received = new AtomicReference<>();
        private final List<com.drones.vision.proto.v1.TrainingProgress> messages;

        RecordingServicer(List<com.drones.vision.proto.v1.TrainingProgress> messages) {
            this.messages = messages;
        }

        @Override
        public void startTraining(com.drones.vision.proto.v1.TrainingJobSpec request,
                                   StreamObserver<com.drones.vision.proto.v1.TrainingProgress> responseObserver) {
            received.set(request);
            messages.forEach(responseObserver::onNext);
            responseObserver.onCompleted();
        }
    }

    /** Streams a fixed prefix, then fails the call with UNAVAILABLE instead of completing. */
    private static final class FailingMidStreamServicer extends TrainingGrpc.TrainingImplBase {
        private final List<com.drones.vision.proto.v1.TrainingProgress> before;

        FailingMidStreamServicer(List<com.drones.vision.proto.v1.TrainingProgress> before) {
            this.before = before;
        }

        @Override
        public void startTraining(com.drones.vision.proto.v1.TrainingJobSpec request,
                                   StreamObserver<com.drones.vision.proto.v1.TrainingProgress> responseObserver) {
            before.forEach(responseObserver::onNext);
            responseObserver.onError(Status.UNAVAILABLE.withDescription("simulated training host outage").asRuntimeException());
        }
    }

    /** Fails the call immediately, before ever streaming anything. */
    private static final class FailImmediatelyServicer extends TrainingGrpc.TrainingImplBase {
        @Override
        public void startTraining(com.drones.vision.proto.v1.TrainingJobSpec request,
                                   StreamObserver<com.drones.vision.proto.v1.TrainingProgress> responseObserver) {
            responseObserver.onError(Status.UNAVAILABLE.withDescription("simulated training host outage").asRuntimeException());
        }
    }

    /** Sends one RUNNING message, sleeps past a would-be control-plane deadline, then a terminal SUCCEEDED. */
    private static final class SlowServicer extends TrainingGrpc.TrainingImplBase {
        private final long sleepSeconds;

        SlowServicer(long sleepSeconds) {
            this.sleepSeconds = sleepSeconds;
        }

        @Override
        public void startTraining(com.drones.vision.proto.v1.TrainingJobSpec request,
                                   StreamObserver<com.drones.vision.proto.v1.TrainingProgress> responseObserver) {
            responseObserver.onNext(runningMessage("job-slow", 1, 1, 1.0f, 0.0f));
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(sleepSeconds));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                responseObserver.onError(Status.CANCELLED.withCause(e).asRuntimeException());
                return;
            }
            responseObserver.onNext(terminalMessage("job-slow", 1, 1,
                    com.drones.vision.proto.v1.JobState.SUCCEEDED, "yolo11n-v9"));
            responseObserver.onCompleted();
        }
    }
}
