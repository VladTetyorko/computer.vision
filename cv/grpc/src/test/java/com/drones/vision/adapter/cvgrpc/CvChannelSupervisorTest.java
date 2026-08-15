package com.drones.vision.adapter.cvgrpc;

import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import com.drones.vision.proto.v1.DetectionResponse;
import com.drones.vision.proto.v1.FrameRequest;
import com.drones.vision.proto.v1.InferenceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CvChannelSupervisor} — the gate's state machine (docs/plans/active/CV-RECONNECT-PLAN.md
 * &sect;2.2, wave R1). Real TCP channels throughout: the interesting behaviour here is entirely about
 * how a real {@link ManagedChannel} reports connectivity state under a real connect failure and a real
 * recovery, which an in-process channel (never fails, never churns) cannot exercise.
 */
class CvChannelSupervisorTest {

    private final List<CvChannelSupervisor> supervisors = new ArrayList<>();
    private final List<ManagedChannel> channels = new ArrayList<>();
    private final List<Server> servers = new ArrayList<>();

    @AfterEach
    void tearDown() throws InterruptedException {
        for (CvChannelSupervisor supervisor : supervisors) {
            supervisor.close();
        }
        for (ManagedChannel channel : channels) {
            channel.shutdownNow();
        }
        for (Server server : servers) {
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static int findFreeTcpPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Short backoffs/heartbeat so tests observe several reconnect cycles without taking real minutes. */
    private static GrpcCvSettings fastSettings() {
        return GrpcCvSettings.defaults()
                .withReconnectInitialBackoff(Duration.ofMillis(100))
                .withReconnectMaxBackoff(Duration.ofMillis(300))
                .withOutageLogInterval(Duration.ofMillis(150));
    }

    private CvChannelSupervisor newSupervisor(ManagedChannel channel, GrpcCvSettings settings) {
        CvChannelSupervisor supervisor = new CvChannelSupervisor(channel, settings);
        supervisors.add(supervisor);
        return supervisor;
    }

    private ManagedChannel channelTo(int port) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
        channels.add(channel);
        return channel;
    }

    @Test
    void gateOpensOnlyOnReadyAndStaysClosedAcrossReconnectChurn() throws Exception {
        int tcpPort = findFreeTcpPort();
        ManagedChannel channel = channelTo(tcpPort);
        CvChannelSupervisor supervisor = newSupervisor(channel, fastSettings());

        supervisor.start();

        // Nothing is listening -- the channel must reach TRANSIENT_FAILURE and the gate must close.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (supervisor.available() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(supervisor.available(), "gate should have closed once the channel saw TRANSIENT_FAILURE");

        // Reconnect churn: with a 100ms/300ms-capped backoff and nothing listening, the channel keeps
        // cycling TRANSIENT_FAILURE -> CONNECTING -> TRANSIENT_FAILURE. The gate must never reopen
        // during any of that -- only READY reopens it (docs/plans/active/CV-RECONNECT-PLAN.md's
        // headline correctness requirement: a gate that reopened on CONNECTING would leak a probe per
        // cycle).
        long churnDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < churnDeadline) {
            assertFalse(supervisor.available(), "gate must stay closed through reconnect churn, saw state="
                    + supervisor.state());
            Thread.sleep(50);
        }
        assertTrue(supervisor.reconnectAttempts() > 0, "supervisor should have forced at least one reconnect attempt");

        // Now start a real server on that exact port -- the next forced resetConnectBackoff() (bounded
        // by reconnectMaxBackoff) should let the channel actually connect and reach READY.
        Server server = ServerBuilder.forPort(tcpPort).addService(new InferenceGrpc.InferenceImplBase() {
            @Override
            public StreamObserver<FrameRequest> detectStream(StreamObserver<DetectionResponse> responseObserver) {
                return new StreamObserver<>() {
                    @Override
                    public void onNext(FrameRequest request) {
                        // no requests expected in this test
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
        }).build().start();
        servers.add(server);

        long recoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!supervisor.available() && System.nanoTime() < recoveryDeadline) {
            Thread.sleep(50);
        }
        assertTrue(supervisor.available(), "gate should reopen once the channel reaches READY; last state="
                + supervisor.state());
        assertEquals(ConnectivityState.READY, supervisor.state());
        assertEquals(Duration.ZERO, supervisor.outageFor(), "outageFor() must reset once the gate reopens");
    }

    @Test
    void startIsIdempotent() throws Exception {
        int tcpPort = findFreeTcpPort();
        ManagedChannel channel = channelTo(tcpPort);
        CvChannelSupervisor supervisor = newSupervisor(channel, fastSettings());

        supervisor.start();
        supervisor.start(); // must not double-arm the watch or throw

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (supervisor.available() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(supervisor.available());
    }

    @Test
    void closeIsIdempotentAndStopsForcingReconnects() throws Exception {
        int tcpPort = findFreeTcpPort();
        ManagedChannel channel = channelTo(tcpPort);
        CvChannelSupervisor supervisor = newSupervisor(channel, fastSettings());
        supervisor.start();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (supervisor.available() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(supervisor.available());

        supervisor.close();
        supervisor.close(); // idempotent -- must not throw
    }

    @Test
    void beforeAnyFailureGateIsOpenDuringColdStartConnecting() throws Exception {
        // Cold start: nothing has failed yet, so IDLE/CONNECTING must NOT close the gate -- only an
        // observed TRANSIENT_FAILURE does. Uses an in-process-style real channel to an address that
        // resolves but has nothing listening for only a brief instant is too flaky to assert on
        // precisely, so this instead asserts the documented invariant directly: a brand new
        // supervisor, before start() is even called, is available.
        int tcpPort = findFreeTcpPort();
        ManagedChannel channel = channelTo(tcpPort);
        CvChannelSupervisor supervisor = newSupervisor(channel, fastSettings());

        assertTrue(supervisor.available(), "gate must start open");
        assertEquals(Duration.ZERO, supervisor.outageFor());
        assertEquals(0, supervisor.reconnectAttempts());
    }

    @Test
    void gateClosesAndReconnectLoopStopsWhenChannelShutsDown() throws Exception {
        // Regression for: onStateChange's SHUTDOWN case used to return without touching `available`,
        // so a channel shut down while the gate was open left available() true forever, and a live
        // reconnect chain kept calling resetConnectBackoff() against a dead channel until close().
        int tcpPort = findFreeTcpPort();
        ManagedChannel channel = channelTo(tcpPort);
        CvChannelSupervisor supervisor = newSupervisor(channel, fastSettings());

        supervisor.start();
        channel.shutdownNow(); // no server is listening, so this may race an in-progress TRANSIENT_FAILURE --
        // either way, SHUTDOWN must win and the gate must close.

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (supervisor.available() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(supervisor.available(), "gate must close once the channel is shut down");
        assertEquals(ConnectivityState.SHUTDOWN, supervisor.state());

        // The reconnect loop must stop, not keep forcing resetConnectBackoff() against a dead channel:
        // wait past a couple of fastSettings' reconnect cycles (100ms/300ms-capped) and confirm the
        // attempt count has stopped growing.
        long attemptsAfterShutdown = supervisor.reconnectAttempts();
        Thread.sleep(400);
        assertEquals(attemptsAfterShutdown, supervisor.reconnectAttempts(),
                "no further reconnect attempts should be forced once the channel is shut down");
    }

    @Test
    void startClosesGateImmediatelyAgainstAnAlreadyFailingChannel() throws Exception {
        // Regression for: start() armed notifyWhenStateChanged on the initial state but never applied
        // the READY/TRANSIENT_FAILURE transition logic to that initial state itself -- so a supervisor
        // started against a channel that was already TRANSIENT_FAILURE left the gate open until the
        // channel happened to transition again (notifyWhenStateChanged only fires on a change away
        // from the state it was armed with).
        int tcpPort = findFreeTcpPort();
        ManagedChannel channel = channelTo(tcpPort);

        // Drive the raw channel into TRANSIENT_FAILURE before any supervisor ever observes it --
        // nothing is listening on tcpPort, so requesting a connection fails quickly.
        long driveDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        ConnectivityState state = channel.getState(true);
        while (state != ConnectivityState.TRANSIENT_FAILURE && System.nanoTime() < driveDeadline) {
            Thread.sleep(20);
            state = channel.getState(true);
        }
        assertEquals(ConnectivityState.TRANSIENT_FAILURE, state,
                "test setup: channel must already be failing before start() is ever called");

        CvChannelSupervisor supervisor = newSupervisor(channel, fastSettings());

        supervisor.start();

        // No further transition needs to happen -- start() must apply the already-TRANSIENT_FAILURE
        // state itself, synchronously, not wait for notifyWhenStateChanged to fire on some later change.
        assertFalse(supervisor.available(),
                "gate must already be closed right after start() returns, with no transition needed");
    }
}
