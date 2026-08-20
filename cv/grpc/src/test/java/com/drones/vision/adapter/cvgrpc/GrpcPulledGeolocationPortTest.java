package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.VisualFix;
import com.drones.vision.perception.domain.model.GeoPrior;
import com.drones.vision.perception.domain.model.GeoSessionConfig;
import com.drones.vision.proto.v1.GeoControl;
import com.drones.vision.proto.v1.GeoEvidence;
import com.drones.vision.proto.v1.GeoFix;
import com.drones.vision.proto.v1.GeoStatus;
import com.drones.vision.proto.v1.GeolocationGrpc;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link GrpcPulledGeolocationPort}/{@link GeolocationSession} against an in-process {@code
 * Geolocation/LocalizeStream} fake — mirrors {@link GrpcPulledDetectionPortTest}'s structure and
 * reasoning: a control-plane RPC (small {@code GeoControl}/{@code GeoFix} messages), no real TCP
 * needed.
 */
class GrpcPulledGeolocationPortTest {

    private static final int AWAIT_SECONDS = 5;
    private static final GeoSessionConfig CONFIG = new GeoSessionConfig("kyiv-pozniaky", 5.0f, null);

    private final List<ManagedChannel> channels = new ArrayList<>();
    private final List<Server> servers = new ArrayList<>();
    private final List<CvChannelSupervisor> supervisors = new ArrayList<>();

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

    private GrpcPulledGeolocationPort newPort(BindableService service) throws Exception {
        String name = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(name).addService(service).build().start();
        servers.add(server);
        ManagedChannel channel = InProcessChannelBuilder.forName(name).build();
        channels.add(channel);
        return new GrpcPulledGeolocationPort(channel, availableSupervisor(channel));
    }

    /** A stub {@link CvChannelSupervisor} whose gate reports open regardless of the underlying channel's state. */
    private CvChannelSupervisor availableSupervisor(ManagedChannel channel) {
        CvChannelSupervisor supervisor = new CvChannelSupervisor(channel,
                GrpcCvSettings.defaults().withReconnectInitialBackoff(Duration.ofMillis(100)));
        supervisors.add(supervisor);
        return supervisor;
    }

    private static Telemetry telemetry(double latitude, double longitude) {
        return new Telemetry(DeviceId.random(), Instant.now(), latitude, longitude, null, null, null, Map.of());
    }

    // -- open()/CvUnavailableException: closed gate fails fast, synchronously ------------------

    @Test
    void openThrowsCvUnavailableExceptionSynchronouslyWhenTheSupervisorGateIsClosed() throws Exception {
        RecordingServicer servicer = new RecordingServicer();
        String name = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(name).addService(servicer).build().start();
        servers.add(server);
        ManagedChannel channel = InProcessChannelBuilder.forName(name).build();
        channels.add(channel);

        CvChannelSupervisor closedGate = closedGateSupervisor();
        GrpcPulledGeolocationPort port = new GrpcPulledGeolocationPort(channel, closedGate);

        assertThrows(CvUnavailableException.class,
                () -> port.open(StreamId.random(), URI.create("rtsp://localhost:8554/x"), CONFIG),
                "a closed supervisor gate must fail open() synchronously, before any session/IO is created");
        assertTrue(servicer.received().isEmpty(), "no GeoControl should ever have been sent");
    }

    /** A real {@link CvChannelSupervisor} against a dead TCP port, driven to a closed gate — mirrors
     *  {@code GrpcDetectionPortTest#closedGateSupervisor()}. */
    private CvChannelSupervisor closedGateSupervisor() throws Exception {
        int tcpPort = findFreeTcpPort();
        ManagedChannel deadChannel = ManagedChannelBuilder.forAddress("localhost", tcpPort).usePlaintext().build();
        channels.add(deadChannel);
        GrpcCvSettings settings = GrpcCvSettings.defaults()
                .withReconnectInitialBackoff(Duration.ofMillis(100))
                .withReconnectMaxBackoff(Duration.ofMillis(300))
                .withOutageLogInterval(Duration.ofSeconds(30));
        CvChannelSupervisor supervisor = new CvChannelSupervisor(deadChannel, settings);
        supervisors.add(supervisor);
        supervisor.start();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (supervisor.available() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(supervisor.available(), "test setup: supervisor gate should have closed by now");
        return supervisor;
    }

    private static int findFreeTcpPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // -- source_url: first message only ---------------------------------------------------------

    @Test
    void sourceUrlIsSentOnTheFirstMessageOnlyAndNeverRestated() throws Exception {
        RecordingServicer servicer = new RecordingServicer();
        GrpcPulledGeolocationPort port = newPort(servicer);
        StreamId id = StreamId.random();
        URI sourceUrl = URI.create("rtsp://localhost:8554/" + id.value());

        port.open(id, sourceUrl, CONFIG);
        awaitAtLeast(servicer, 1);
        port.telemetry(id, telemetry(48.5, 32.0));
        awaitAtLeast(servicer, 2);

        List<GeoControl> received = servicer.received();
        assertEquals(sourceUrl.toString(), received.get(0).getSourceUrl());
        assertTrue(received.get(1).getSourceUrl().isEmpty(), "a later GeoControl must not restate source_url");
    }

    // -- stream_id/region_id/target_fps: every message ----------------------------------------

    @Test
    void everyMessageCarriesStreamIdRegionIdAndTargetFps() throws Exception {
        RecordingServicer servicer = new RecordingServicer();
        GrpcPulledGeolocationPort port = newPort(servicer);
        StreamId id = StreamId.random();

        port.open(id, URI.create("rtsp://localhost:8554/" + id.value()), CONFIG);
        port.telemetry(id, telemetry(1.0, 2.0));
        awaitAtLeast(servicer, 2);

        for (GeoControl control : servicer.received()) {
            assertEquals(id.value().toString(), control.getStreamId());
            assertEquals("kyiv-pozniaky", control.getRegionId());
            assertEquals(5.0f, control.getTargetFps(), 1e-6);
        }
    }

    // -- telemetry: restated on the NEXT control message, never a message of its own -----------

    @Test
    void telemetryIsRestatedOnTheNextControlMessageNotSentOnItsOwn() throws Exception {
        RecordingServicer servicer = new RecordingServicer();
        GrpcPulledGeolocationPort port = newPort(servicer);
        StreamId id = StreamId.random();

        port.open(id, URI.create("rtsp://localhost:8554/" + id.value()), CONFIG);
        awaitAtLeast(servicer, 1);
        assertFalse(servicer.received().get(0).hasTelemetry(), "the first message has no telemetry yet");

        port.telemetry(id, telemetry(48.5, 32.0));
        awaitAtLeast(servicer, 2);

        GeoControl second = servicer.received().get(1);
        assertTrue(second.hasTelemetry(), "telemetry() must itself send one restated GeoControl");
        assertEquals(48.5, second.getTelemetry().getLatitude(), 1e-9);

        // A THIRD message (another telemetry sample) must still carry region_id/target_fps/stream_id
        // -- i.e. telemetry restates the FULL desired state, not a bare telemetry-only message.
        port.telemetry(id, telemetry(49.0, 33.0));
        awaitAtLeast(servicer, 3);
        GeoControl third = servicer.received().get(2);
        assertEquals("kyiv-pozniaky", third.getRegionId());
        assertEquals(49.0, third.getTelemetry().getLatitude(), 1e-9);
    }

    @Test
    void telemetryForAStreamWithNoOpenSessionIsANoOp() throws Exception {
        RecordingServicer servicer = new RecordingServicer();
        GrpcPulledGeolocationPort port = newPort(servicer);

        port.telemetry(StreamId.random(), telemetry(1.0, 2.0));

        assertTrue(servicer.received().isEmpty());
    }

    // -- prior: included on the control message once configured ---------------------------------

    @Test
    void priorFromConfigIsIncludedOnEveryMessage() throws Exception {
        RecordingServicer servicer = new RecordingServicer();
        GrpcPulledGeolocationPort port = newPort(servicer);
        StreamId id = StreamId.random();
        GeoSessionConfig configWithPrior = new GeoSessionConfig("", 0f, new GeoPrior(1.0, 2.0, 300.0));

        port.open(id, URI.create("rtsp://localhost:8554/" + id.value()), configWithPrior);
        awaitAtLeast(servicer, 1);

        GeoControl first = servicer.received().get(0);
        assertTrue(first.hasPrior());
        assertEquals(300.0, first.getPrior().getRadiusMeters(), 1e-9);
    }

    // -- close(): stop=true, clean half-close ----------------------------------------------------

    @Test
    void closeSendsStopTrueAndHalfClosesTheCall() throws Exception {
        RecordingServicer servicer = new RecordingServicer();
        GrpcPulledGeolocationPort port = newPort(servicer);
        StreamId id = StreamId.random();

        port.open(id, URI.create("rtsp://localhost:8554/" + id.value()), CONFIG);
        awaitAtLeast(servicer, 1);
        port.close(id);

        assertTrue(servicer.clientHalfClosed().await(AWAIT_SECONDS, TimeUnit.SECONDS),
                "expected the client to half-close after close()");
        GeoControl last = servicer.received().get(servicer.received().size() - 1);
        assertTrue(last.getStop(), "the last GeoControl before half-close must carry stop=true");
    }

    @Test
    void closeIsIdempotentAndANoOpForAStreamWithNoOpenSession() throws Exception {
        RecordingServicer servicer = new RecordingServicer();
        GrpcPulledGeolocationPort port = newPort(servicer);
        StreamId id = StreamId.random();

        port.open(id, URI.create("rtsp://localhost:8554/" + id.value()), CONFIG);
        awaitAtLeast(servicer, 1);
        port.close(id);
        assertTrue(servicer.clientHalfClosed().await(AWAIT_SECONDS, TimeUnit.SECONDS));

        port.close(id);
        port.close(StreamId.random());
    }

    // -- responses: delivered to the subscriber, malformed ones dropped -------------------------

    @Test
    void aFixDeliveredBeforeTheStreamEndsReachesTheSubscriber() throws Exception {
        RecordingServicer servicer = new RecordingServicer();
        GrpcPulledGeolocationPort port = newPort(servicer);
        StreamId id = StreamId.random();

        CapturingSubscriber subscriber = new CapturingSubscriber();
        Flow.Publisher<VisualFix> publisher = port.open(id,
                URI.create("rtsp://localhost:8554/" + id.value()), CONFIG);
        publisher.subscribe(subscriber);
        awaitAtLeast(servicer, 1);

        servicer.pushResponse(fixResponse(id, 48.5, 32.0));

        assertTrue(subscriber.atLeastOne.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(48.5, subscriber.results.get(0).position().latitude(), 1e-9);
        assertEquals(1, subscriber.terminal.getCount(), "the publisher must still be open after one fix");
    }

    @Test
    void aMalformedFixIsDroppedAndTheSessionKeepsRunning() throws Exception {
        RecordingServicer servicer = new RecordingServicer();
        GrpcPulledGeolocationPort port = newPort(servicer);
        StreamId id = StreamId.random();

        CapturingSubscriber subscriber = new CapturingSubscriber();
        Flow.Publisher<VisualFix> publisher = port.open(id,
                URI.create("rtsp://localhost:8554/" + id.value()), CONFIG);
        publisher.subscribe(subscriber);
        awaitAtLeast(servicer, 1);

        // GEO_STATUS_FIX with no latitude/longitude -- fails VisualFix's own compact-constructor
        // validation; must be logged and dropped, not crash the session.
        servicer.pushResponse(GeoFix.newBuilder()
                .setStreamId(id.value().toString())
                .setFrameMillis(1)
                .setStatus(GeoStatus.GEO_STATUS_FIX)
                .setEvidence(GeoEvidence.getDefaultInstance())
                .build());
        // A valid fix right after -- proves the session is still alive.
        servicer.pushResponse(fixResponse(id, 1.0, 2.0));

        assertTrue(subscriber.atLeastOne.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(1, subscriber.results.size(), "only the valid fix should have reached the subscriber");
        assertEquals(1.0, subscriber.results.get(0).position().latitude(), 1e-9);
    }

    // -- transport failure surfaces as onError ---------------------------------------------------

    @Test
    void unavailableFromTheServerSurfacesAsOnError() throws Exception {
        UnavailableServicer servicer = new UnavailableServicer();
        GrpcPulledGeolocationPort port = newPort(servicer);
        StreamId id = StreamId.random();

        CapturingSubscriber subscriber = new CapturingSubscriber();
        Flow.Publisher<VisualFix> publisher = port.open(id,
                URI.create("rtsp://localhost:8554/" + id.value()), CONFIG);
        publisher.subscribe(subscriber);

        assertTrue(subscriber.terminal.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        StatusRuntimeException ex = assertInstanceOf(StatusRuntimeException.class, subscriber.error.get());
        assertEquals(Status.Code.UNAVAILABLE, ex.getStatus().getCode());
        assertTrue(subscriber.results.isEmpty());
    }

    @Test
    void aStreamKilledMidSessionIsReopenableForTheSameStreamIdAndFixesFlowAgain() throws Exception {
        // H8, VISUAL-GEO-V2-PLAN.md §9.11 defect 1: a cv-service bounce must not wedge geolocation.
        // This half of the fix is the adapter's -- GeolocationSession#failAndDrop drops itself from
        // the port's session map, so the caller's next open() for the SAME id builds a fresh session
        // rather than silently reusing (or refusing) the dead one. The composition half, which
        // notices the termination and calls open() again, is VisualGeoRunnerTest's.
        BouncingServicer servicer = new BouncingServicer();
        GrpcPulledGeolocationPort port = newPort(servicer);
        StreamId id = StreamId.random();
        URI sourceUrl = URI.create("rtsp://localhost:8554/" + id.value());

        CapturingSubscriber killed = new CapturingSubscriber();
        port.open(id, sourceUrl, CONFIG).subscribe(killed);
        assertTrue(killed.terminal.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the first stream must be killed");
        assertInstanceOf(StatusRuntimeException.class, killed.error.get());

        CapturingSubscriber reopened = new CapturingSubscriber();
        port.open(id, sourceUrl, CONFIG).subscribe(reopened);
        servicer.awaitSecondStream();
        servicer.pushResponse(fixResponse(id, 50.45, 30.52));

        assertTrue(reopened.atLeastOne.await(AWAIT_SECONDS, TimeUnit.SECONDS),
                "a fix must reach the subscriber of the reopened session for the same stream id");
        assertEquals(50.45, reopened.results.get(0).position().latitude(), 1e-9);
        assertEquals(1, reopened.terminal.getCount(), "the reopened session must still be open");

        // Telemetry now targets the live session, not the dead one -- the port's map holds only the
        // second session, so this must reach the server rather than vanish into a torn-down observer.
        port.telemetry(id, telemetry(50.45, 30.52));
        servicer.awaitSecondStreamControls(2);
    }

    private static GeoFix fixResponse(StreamId id, double latitude, double longitude) {
        return GeoFix.newBuilder()
                .setStreamId(id.value().toString())
                .setFrameMillis(Instant.now().toEpochMilli())
                .setStatus(GeoStatus.GEO_STATUS_FIX)
                .setRegionId("kyiv-pozniaky")
                .setTileId("17/1/1")
                .setLatitude(latitude)
                .setLongitude(longitude)
                .setEvidence(GeoEvidence.getDefaultInstance())
                .build();
    }

    private static void awaitAtLeast(RecordingServicer servicer, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (servicer.received().size() < count) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("expected at least " + count + " GeoControl message(s), got "
                        + servicer.received().size());
            }
            Thread.sleep(10);
        }
    }

    // -- test servicers -----------------------------------------------------------------------

    /** Records every GeoControl it receives and reports when the client half-closes. */
    private static class RecordingServicer extends GeolocationGrpc.GeolocationImplBase {
        private final List<GeoControl> received = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch clientHalfClosed = new CountDownLatch(1);
        private volatile StreamObserver<GeoFix> responseObserver;

        @Override
        public StreamObserver<GeoControl> localizeStream(StreamObserver<GeoFix> responseObserver) {
            this.responseObserver = responseObserver;
            return new StreamObserver<>() {
                @Override
                public void onNext(GeoControl value) {
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

        List<GeoControl> received() {
            return List.copyOf(received);
        }

        CountDownLatch clientHalfClosed() {
            return clientHalfClosed;
        }

        void pushResponse(GeoFix response) {
            responseObserver.onNext(response);
        }
    }

    /**
     * Kills the FIRST {@code LocalizeStream} call with {@code UNAVAILABLE} (a cv-service bounce) and
     * serves every later one normally — the fixture for the reopen test above.
     */
    private static final class BouncingServicer extends GeolocationGrpc.GeolocationImplBase {
        private final AtomicInteger streams = new AtomicInteger();
        private final List<GeoControl> secondStreamControls = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch secondStreamOpened = new CountDownLatch(1);
        private volatile StreamObserver<GeoFix> secondResponseObserver;

        @Override
        public StreamObserver<GeoControl> localizeStream(StreamObserver<GeoFix> responseObserver) {
            boolean first = streams.incrementAndGet() == 1;
            if (!first) {
                secondResponseObserver = responseObserver;
            }
            return new StreamObserver<>() {
                @Override
                public void onNext(GeoControl value) {
                    if (first) {
                        responseObserver.onError(Status.UNAVAILABLE
                                .withDescription("simulated: cv-service bounced").asRuntimeException());
                        return;
                    }
                    secondStreamControls.add(value);
                    secondStreamOpened.countDown();
                }

                @Override
                public void onError(Throwable t) {
                    // unused
                }

                @Override
                public void onCompleted() {
                    responseObserver.onCompleted();
                }
            };
        }

        void awaitSecondStream() throws InterruptedException {
            assertTrue(secondStreamOpened.await(AWAIT_SECONDS, TimeUnit.SECONDS),
                    "the reopened session must reach the server");
        }

        void awaitSecondStreamControls(int count) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
            while (secondStreamControls.size() < count) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("expected at least " + count
                            + " GeoControl message(s) on the reopened stream, got " + secondStreamControls.size());
                }
                Thread.sleep(10);
            }
        }

        void pushResponse(GeoFix response) {
            secondResponseObserver.onNext(response);
        }
    }

    /** Answers the very first GeoControl with UNAVAILABLE. */
    private static final class UnavailableServicer extends GeolocationGrpc.GeolocationImplBase {
        @Override
        public StreamObserver<GeoControl> localizeStream(StreamObserver<GeoFix> responseObserver) {
            return new StreamObserver<>() {
                @Override
                public void onNext(GeoControl value) {
                    responseObserver.onError(Status.UNAVAILABLE
                            .withDescription("simulated: geolocation source unopenable").asRuntimeException());
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

    /** Collects every delivered {@code VisualFix} and the terminal signal, requesting unbounded demand. */
    private static final class CapturingSubscriber implements Flow.Subscriber<VisualFix> {
        final List<VisualFix> results = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch atLeastOne = new CountDownLatch(1);
        final CountDownLatch terminal = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(VisualFix item) {
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
