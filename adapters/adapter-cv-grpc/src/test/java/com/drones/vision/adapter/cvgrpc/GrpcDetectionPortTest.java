package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.EventRuleConfig;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.perception.domain.model.PixelFormat;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.TargetLock;
import com.drones.vision.perception.domain.model.TrackingConfig;
import com.drones.vision.perception.domain.model.TrackingMode;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.proto.v1.DetectionResponse;
import com.drones.vision.proto.v1.FrameRequest;
import com.drones.vision.proto.v1.ImageEncoding;
import com.drones.vision.proto.v1.InferenceGrpc;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        return newPort(service, GrpcCvSettings.defaults());
    }

    private GrpcDetectionPort newPort(BindableService service, int detectWidth, float jpegQuality) throws Exception {
        return newPort(service, GrpcCvSettings.defaults().withDetectWidth(detectWidth).withJpegQuality(jpegQuality)
                .withWireFormat(WireFormat.JPEG));
    }

    /**
     * A port pinned to JPEG. An in-process channel reports {@code localhost} as its authority, so
     * under the shipped {@link WireFormat#AUTO} default these tests would otherwise exercise the raw
     * path — correct behaviour, but not what a test named "…ToJpeg…" is asserting. Pinning states
     * which encoding is under test instead of depending on how gRPC names a test channel.
     */
    private GrpcDetectionPort newJpegPort(BindableService service) throws Exception {
        return newPort(service, GrpcCvSettings.defaults().withWireFormat(WireFormat.JPEG));
    }

    private GrpcDetectionPort newPort(BindableService service, GrpcCvSettings settings) throws Exception {
        String name = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(name).addService(service).build().start();
        servers.add(server);
        ManagedChannel channel = InProcessChannelBuilder.forName(name).build();
        GrpcDetectionPort port = new GrpcDetectionPort(channel, settings);
        ports.add(port);
        return port;
    }

    /** An ephemeral TCP port with nothing bound to it, for simulating a cv-service that is entirely down. */
    private static int findFreeTcpPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
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
        assertTrue(elapsedMillis < TimeUnit.SECONDS.toMillis(GrpcCvSettings.RESPONSE_TIMEOUT_SECONDS),
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
                () -> stage.toCompletableFuture().get(GrpcCvSettings.RESPONSE_TIMEOUT_SECONDS + 5, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, ex.getCause());

        long elapsedSeconds = Duration.ofNanos(System.nanoTime() - startNanos).toSeconds();
        assertTrue(elapsedSeconds >= GrpcCvSettings.RESPONSE_TIMEOUT_SECONDS,
                "expected a timeout after ~" + GrpcCvSettings.RESPONSE_TIMEOUT_SECONDS + "s, took " + elapsedSeconds + "s");
    }

    @Test
    void detectOnDeadTcpEndpointFailsPromptlyWithoutHangingForever() throws Exception {
        // A real TCP port with NOTHING listening -- what a session opened while the cv-service is
        // down entirely looks like over a real transport (an in-process channel with no registered
        // server fails immediately and cleanly, which does not reproduce the interesting case: against
        // a real dead endpoint, the first call was empirically observed to fail via this adapter's own
        // RESPONSE_TIMEOUT_SECONDS timeout, with no transport onError ever delivered at all). Whichever
        // way it fails, it must fail -- not hang past this bound.
        int tcpPort = findFreeTcpPort();
        GrpcDetectionPort grpcPort = new GrpcDetectionPort("localhost", tcpPort, GrpcCvSettings.defaults());
        ports.add(grpcPort);

        CompletionStage<DetectionResult> stage =
                grpcPort.detect(frame(StreamId.random(), 0, PixelFormat.BGR24), PipelineConfig.defaults());
        assertThrows(ExecutionException.class,
                () -> stage.toCompletableFuture().get(GrpcCvSettings.RESPONSE_TIMEOUT_SECONDS + 5, TimeUnit.SECONDS));
    }

    @Test
    void detectRecoversAfterRealConnectFailureOnceServerStarts() throws Exception {
        // The literal "nothing listening, then a server starts on the same channel" scenario that
        // used to be untestable in this module (see the previous test's comment for the full
        // mechanism): with io.grpc:grpc-core pinned to ${grpc.version} at the root pom (verified via
        // `mvn -pl adapters/adapter-cv-grpc dependency:tree -Dverbose` -- grpc-core and grpc-inprocess
        // both resolve to 1.64.0, no "version managed from" skew), a real TCP connect failure no
        // longer hits the NoSuchMethodError that used to wedge the channel in CONNECTING forever.
        int tcpPort = findFreeTcpPort();
        GrpcDetectionPort grpcPort = new GrpcDetectionPort("localhost", tcpPort, GrpcCvSettings.defaults());
        ports.add(grpcPort);
        StreamId streamId = StreamId.random();
        PipelineConfig config = PipelineConfig.defaults();

        // Nothing listening yet -- this must fail (via connect failure or this adapter's own
        // response timeout, either is acceptable), not hang.
        CompletionStage<DetectionResult> first = grpcPort.detect(frame(streamId, 0, PixelFormat.BGR24), config);
        assertThrows(ExecutionException.class,
                () -> first.toCompletableFuture().get(GrpcCvSettings.RESPONSE_TIMEOUT_SECONDS + 10, TimeUnit.SECONDS));

        // Now start a real server on that exact port and prove the *same* GrpcDetectionPort/channel
        // recovers, exactly like a cv-service coming up after the laptop already tried to reach it.
        Server server = ServerBuilder.forPort(tcpPort).addService(new EchoServicer()).build().start();
        servers.add(server);

        // A fail-fast RPC against a channel already in TRANSIENT_FAILURE returns near-instantly
        // (it does not block waiting for a connection), so recovery depends entirely on the
        // channel's own background reconnect-backoff timer (default: starts at 1s, x1.6 per
        // attempt) actually firing and succeeding now that something is listening -- an explicit
        // sleep between attempts is required to give that timer real wall-clock time to run, not
        // just more retries in a tight loop.
        DetectionResult recovered = null;
        Throwable lastFailure = null;
        for (int attempt = 1; attempt <= 10 && recovered == null; attempt++) {
            try {
                recovered = grpcPort.detect(frame(streamId, attempt, PixelFormat.BGR24), config)
                        .toCompletableFuture().get(GrpcCvSettings.RESPONSE_TIMEOUT_SECONDS + 3, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                lastFailure = e.getCause();
            }
            if (recovered == null) {
                Thread.sleep(1000);
            }
        }

        assertTrue(recovered != null,
                "expected detect() to recover once a real server started listening on the previously-dead "
                        + "endpoint; last failure: " + lastFailure);
        assertEquals(streamId, recovered.streamId());
    }

    @Test
    void detectAfterTimeoutTearsDownSessionSoNextDetectRecoversOnFreshCall() throws Exception {
        // Real TCP transport (not in-process), server already listening -- deliberately does NOT
        // reuse detectOnDeadTcpEndpointFailsPromptlyWithoutHangingForever's "nothing listening, then a
        // server starts" shape for the recovery assertion below. That shape cannot be driven to a
        // deterministic recovery in this repo's current dependency state: io.grpc:grpc-core resolves
        // to 1.80.0 here (both via grpc-inprocess, and -- confirmed with
        // `mvn dependency:tree -Dverbose` -- transitively via grpc-netty-shaded itself, overridden by
        // spring-boot-dependencies' imported grpc-bom; the same override reaches vision-app's real
        // compile classpath too, per `mvn -pl vision-app dependency:tree -Dincludes=io.grpc:grpc-core`),
        // while grpc-netty-shaded's compiled bytecode (${grpc.version}=1.64.0) expects 1.64.0's API
        // shape. A real *connect failure* hits a NoSuchMethodError deep in Netty's transport-shutdown
        // notification (io.grpc.internal.ManagedClientTransport$Listener.transportShutdown) that gets
        // silently swallowed, so the channel never learns the attempt failed and is permanently wedged
        // in CONNECTING -- no session-level fix can make a channel recover from a real ECONNREFUSED
        // here (verified empirically: even 10 retries a second apart, well past any reconnect backoff,
        // never recovered). Fixing that is a pom.xml dependencyManagement change outside this module
        // (grpc-core isn't pinned to ${grpc.version} at the root -- see also this module's Gotchas),
        // out of this task's adapter-cv-grpc-only scope. A session that connects fine but never gets a
        // response exercises the exact same StreamSession timeout/teardown code this bug is about,
        // without ever touching that broken notification path, and is what this test does instead.
        int tcpPort = findFreeTcpPort();
        SilentThenEchoServicer servicer = new SilentThenEchoServicer();
        Server server = ServerBuilder.forPort(tcpPort).addService(servicer).build().start();
        servers.add(server);
        GrpcDetectionPort grpcPort = new GrpcDetectionPort("localhost", tcpPort, GrpcCvSettings.defaults());
        ports.add(grpcPort);
        StreamId streamId = StreamId.random();
        PipelineConfig config = PipelineConfig.defaults();

        CompletionStage<DetectionResult> first = grpcPort.detect(frame(streamId, 0, PixelFormat.BGR24), config);
        assertThrows(ExecutionException.class,
                () -> first.toCompletableFuture().get(GrpcCvSettings.RESPONSE_TIMEOUT_SECONDS + 5, TimeUnit.SECONDS));

        // Emulates StreamPipeline's outage-recovery probes: a handful of attempts with short waits.
        // Before the fix this keeps reusing the same dead session and every attempt fails; after the
        // fix, the timeout above already tore the session down, so this opens a fresh call the
        // (otherwise perfectly healthy) server does answer.
        DetectionResult recovered = null;
        Throwable lastFailure = null;
        for (int attempt = 1; attempt <= 3 && recovered == null; attempt++) {
            try {
                recovered = grpcPort.detect(frame(streamId, attempt, PixelFormat.BGR24), config)
                        .toCompletableFuture().get(GrpcCvSettings.RESPONSE_TIMEOUT_SECONDS + 3, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                lastFailure = e.getCause();
            }
        }

        assertTrue(recovered != null,
                "expected detect() to recover within a few probes once the dead session was torn down; last failure: "
                        + lastFailure);
        assertEquals(streamId, recovered.streamId());
    }

    @Test
    void timeoutRacingTransportErrorTearsDownSessionOnceAndFailsPendingSiblingsFast() throws Exception {
        RacingServicer servicer = new RacingServicer();
        GrpcDetectionPort port = newPort(servicer);
        StreamId streamId = StreamId.random();
        PipelineConfig config = PipelineConfig.defaults();

        CompletionStage<DetectionResult> first = port.detect(frame(streamId, 0, PixelFormat.BGR24), config);
        // Let frame 0 be well in flight (its own timeout and the server's racing transport error are
        // both scheduled around RESPONSE_TIMEOUT_SECONDS from here) before frame 1 joins the same session.
        Thread.sleep(500);
        long secondSubmittedNanos = System.nanoTime();
        CompletionStage<DetectionResult> second = port.detect(frame(streamId, 1, PixelFormat.BGR24), config);

        assertThrows(ExecutionException.class,
                () -> first.toCompletableFuture().get(GrpcCvSettings.RESPONSE_TIMEOUT_SECONDS + 3, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class,
                () -> second.toCompletableFuture().get(GrpcCvSettings.RESPONSE_TIMEOUT_SECONDS + 3, TimeUnit.SECONDS));

        long secondElapsedMillis = Duration.ofNanos(System.nanoTime() - secondSubmittedNanos).toMillis();
        assertTrue(secondElapsedMillis < TimeUnit.SECONDS.toMillis(GrpcCvSettings.RESPONSE_TIMEOUT_SECONDS),
                "sibling frame should fail once the session is torn down, not by waiting out its own "
                        + GrpcCvSettings.RESPONSE_TIMEOUT_SECONDS + "s timeout; took " + secondElapsedMillis + "ms");

        // Torn down exactly once despite the timeout/transport-error race (no exception escaped
        // above): the next detect() opens a brand-new call and completes normally.
        DetectionResult recovered = port.detect(frame(streamId, 2, PixelFormat.BGR24), config)
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(2L, recovered.frameSequence());
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

    @Test
    void detectDownscalesWideBgr24FrameToJpegAtMaxDetectWidth() throws Exception {
        CapturingServicer servicer = new CapturingServicer();
        GrpcDetectionPort port = newJpegPort(servicer);
        StreamId streamId = StreamId.random();
        int width = 1280;
        int height = 720;
        byte[] pixels = new byte[width * height * 3];
        new Random(1).nextBytes(pixels);
        VideoFrame frame = new VideoFrame(streamId, 0, Instant.now().truncatedTo(ChronoUnit.MILLIS),
                width, height, PixelFormat.BGR24, ByteBuffer.wrap(pixels));

        port.detect(frame, PipelineConfig.defaults()).toCompletableFuture().get(5, TimeUnit.SECONDS);

        FrameRequest sent = servicer.received.get(0L);
        int expectedHeight = Math.round((float) height * GrpcCvSettings.MAX_DETECT_WIDTH / width);
        assertEquals(ImageEncoding.IMAGE_ENCODING_JPEG, sent.getEncoding());
        assertEquals(GrpcCvSettings.MAX_DETECT_WIDTH, sent.getWidth());
        assertEquals(expectedHeight, sent.getHeight());

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(sent.getData().toByteArray()));
        assertEquals(GrpcCvSettings.MAX_DETECT_WIDTH, decoded.getWidth(), "captured JPEG must decode to the scaled width");
        assertEquals(expectedHeight, decoded.getHeight(), "captured JPEG must decode to the scaled height");
    }

    @Test
    void detectSendsTheDownscaledFrameRawWhenTheWireFormatIsBgr24() throws Exception {
        // docs/plans/active/CV-RATE-CONTROL-PLAN.md wave R3: the same downscale, without the JPEG encode
        // here or the matching decode inside cv-service -- together most of the ~25ms of
        // non-inference round trip measured in docs/conclusions/CV-RATE-BUDGET.md 3.
        CapturingServicer servicer = new CapturingServicer();
        GrpcDetectionPort port = newPort(servicer, GrpcCvSettings.defaults().withWireFormat(WireFormat.BGR24));
        StreamId streamId = StreamId.random();
        int width = 1280;
        int height = 720;
        byte[] pixels = new byte[width * height * 3];
        new Random(4).nextBytes(pixels);
        VideoFrame frame = new VideoFrame(streamId, 0, Instant.now().truncatedTo(ChronoUnit.MILLIS),
                width, height, PixelFormat.BGR24, ByteBuffer.wrap(pixels));

        port.detect(frame, PipelineConfig.defaults()).toCompletableFuture().get(5, TimeUnit.SECONDS);

        FrameRequest sent = servicer.received.get(0L);
        int expectedHeight = Math.round((float) height * GrpcCvSettings.MAX_DETECT_WIDTH / width);
        assertEquals(ImageEncoding.IMAGE_ENCODING_BGR24, sent.getEncoding());
        assertEquals(GrpcCvSettings.MAX_DETECT_WIDTH, sent.getWidth());
        assertEquals(expectedHeight, sent.getHeight());
        assertEquals(GrpcCvSettings.MAX_DETECT_WIDTH * expectedHeight * 3, sent.getData().size(),
                "raw BGR24 is exactly three bytes per pixel of the SCALED frame, with no row padding");
    }

    @Test
    void autoSendsRawToALoopbackEndpointWithNoWireFormatConfigured() throws Exception {
        // The shipped default, exercised end to end rather than asserted on the enum alone: an
        // in-process channel's authority is `localhost`, which is exactly the case AUTO exists for.
        CapturingServicer servicer = new CapturingServicer();
        GrpcDetectionPort port = newPort(servicer, GrpcCvSettings.defaults());
        StreamId streamId = StreamId.random();
        byte[] pixels = new byte[1280 * 720 * 3];
        VideoFrame frame = new VideoFrame(streamId, 0, Instant.now().truncatedTo(ChronoUnit.MILLIS),
                1280, 720, PixelFormat.BGR24, ByteBuffer.wrap(pixels));

        port.detect(frame, PipelineConfig.defaults()).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(WireFormat.AUTO, GrpcCvSettings.defaults().wireFormat(), "AUTO really is the default");
        assertEquals(ImageEncoding.IMAGE_ENCODING_BGR24, servicer.received.get(0L).getEncoding());
    }

    @Test
    void detectPassesBgr24FrameAtMaxDetectWidthThroughByteIdentical() throws Exception {
        CapturingServicer servicer = new CapturingServicer();
        GrpcDetectionPort port = newPort(servicer);
        StreamId streamId = StreamId.random();
        int width = GrpcCvSettings.MAX_DETECT_WIDTH; // exactly at the threshold -- not downscaled (only ">" triggers it)
        int height = 480;
        byte[] pixels = new byte[width * height * 3];
        new Random(2).nextBytes(pixels);
        VideoFrame frame = new VideoFrame(streamId, 0, Instant.now().truncatedTo(ChronoUnit.MILLIS),
                width, height, PixelFormat.BGR24, ByteBuffer.wrap(pixels));

        port.detect(frame, PipelineConfig.defaults()).toCompletableFuture().get(5, TimeUnit.SECONDS);

        FrameRequest sent = servicer.received.get(0L);
        assertEquals(ImageEncoding.IMAGE_ENCODING_BGR24, sent.getEncoding());
        assertEquals(width, sent.getWidth());
        assertEquals(height, sent.getHeight());
        assertArrayEquals(pixels, sent.getData().toByteArray());
    }

    @Test
    void detectDownscalesUsingConfiguredDetectWidthInsteadOfDefault() throws Exception {
        // Proves the (channel, detectWidth, jpegQuality) constructor's detectWidth actually drives
        // the downscale threshold/target width, not just the default MAX_DETECT_WIDTH constant.
        int configuredDetectWidth = 320;
        CapturingServicer servicer = new CapturingServicer();
        GrpcDetectionPort port = newPort(servicer, configuredDetectWidth, GrpcCvSettings.JPEG_QUALITY);
        StreamId streamId = StreamId.random();
        int width = 640; // wider than configuredDetectWidth, narrower than the default MAX_DETECT_WIDTH
        int height = 360;
        byte[] pixels = new byte[width * height * 3];
        new Random(3).nextBytes(pixels);
        VideoFrame frame = new VideoFrame(streamId, 0, Instant.now().truncatedTo(ChronoUnit.MILLIS),
                width, height, PixelFormat.BGR24, ByteBuffer.wrap(pixels));

        port.detect(frame, PipelineConfig.defaults()).toCompletableFuture().get(5, TimeUnit.SECONDS);

        FrameRequest sent = servicer.received.get(0L);
        int expectedHeight = Math.round((float) height * configuredDetectWidth / width);
        assertEquals(ImageEncoding.IMAGE_ENCODING_JPEG, sent.getEncoding());
        assertEquals(configuredDetectWidth, sent.getWidth());
        assertEquals(expectedHeight, sent.getHeight());
    }

    // Boundary-rejection tests for detectWidth/jpegQuality (constructorRejectsDetectWidthBelowMinimum,
    // constructorRejectsNonPositiveJpegQuality, constructorRejectsJpegQualityAboveOne), the keepalive
    // constant sanity check (keepaliveConstantsAreSaneForAFlakyLink), and
    // hostPortConstructorWithTuningKnobsDelegatesValidationToCanonicalConstructor moved to
    // GrpcCvSettingsTest: that record now owns detectWidth/jpegQuality validation and the keepalive
    // defaults, and a settings object can no longer be constructed invalid in the first place, which
    // is what the old "host/port ctor delegates validation to the canonical one" test proved -- there
    // is only one place left to validate. The two "accepts the boundary value" tests below stay here,
    // proving the *port* still works end-to-end at each boundary once GrpcCvSettings accepts it.

    @Test
    void constructorAcceptsDetectWidthAtMinimum() throws Exception {
        GrpcDetectionPort port = newPort(new EchoServicer(),
                GrpcCvSettings.defaults().withDetectWidth(GrpcCvSettings.MIN_DETECT_WIDTH));

        assertDoesNotThrow(() -> port.detect(frame(StreamId.random(), 0, PixelFormat.JPEG), PipelineConfig.defaults())
                .toCompletableFuture().get(5, TimeUnit.SECONDS));
    }

    @Test
    void constructorAcceptsJpegQualityAtUpperBound() throws Exception {
        GrpcDetectionPort port = newPort(new EchoServicer(), GrpcCvSettings.defaults().withJpegQuality(1.0f));

        assertDoesNotThrow(() -> port.detect(frame(StreamId.random(), 0, PixelFormat.JPEG), PipelineConfig.defaults())
                .toCompletableFuture().get(5, TimeUnit.SECONDS));
    }

    @Test
    void detectPassesJpegFrameThroughByteIdentical() throws Exception {
        CapturingServicer servicer = new CapturingServicer();
        GrpcDetectionPort port = newPort(servicer);
        StreamId streamId = StreamId.random();
        byte[] jpegBytes = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3, 4, 5, (byte) 0xFF, (byte) 0xD9};
        VideoFrame frame = new VideoFrame(streamId, 0, Instant.now().truncatedTo(ChronoUnit.MILLIS),
                640, 480, PixelFormat.JPEG, ByteBuffer.wrap(jpegBytes));

        port.detect(frame, PipelineConfig.defaults()).toCompletableFuture().get(5, TimeUnit.SECONDS);

        FrameRequest sent = servicer.received.get(0L);
        assertEquals(ImageEncoding.IMAGE_ENCODING_JPEG, sent.getEncoding());
        assertArrayEquals(jpegBytes, sent.getData().toByteArray());
    }

    @Test
    void trackingConfigAndTargetLockCapturedExactlyOnTheWire() throws Exception {
        // docs/plans/done/TRACKING-PLAN.md §4.A/§4.B, T4: PipelineConfig.tracking() -> FrameRequest.tracking,
        // including the TargetLock and the redetectIouPercent (int, [0,100]) -> redetect_iou_threshold
        // (float ratio) unit conversion. Asserted against what the server actually received, not
        // just that encode() ran without throwing.
        CapturingServicer servicer = new CapturingServicer();
        GrpcDetectionPort port = newPort(servicer);
        StreamId streamId = StreamId.random();
        TargetLock lock = new TargetLock(3, 7L, null, null, false);
        TrackingConfig tracking = new TrackingConfig(TrackingMode.FOLLOW, "lk", 1500, 20, 45, 25, 4, lock);
        PipelineConfig config = new PipelineConfig(new ModelRef("yolo26n.pt", "latest"), 0.4, 10, 2, true,
                java.util.Set.of(), EventRuleConfig.defaults(), true, true, tracking);

        port.detect(frame(streamId, 0, PixelFormat.BGR24), config).toCompletableFuture().get(5, TimeUnit.SECONDS);

        FrameRequest sent = servicer.received.get(0L);
        assertTrue(sent.hasTracking());
        com.drones.vision.proto.v1.TrackingConfig wireTracking = sent.getTracking();
        assertEquals(com.drones.vision.proto.v1.TrackingMode.TRACKING_MODE_FOLLOW, wireTracking.getMode());
        assertEquals("lk", wireTracking.getEngineId());
        assertEquals(1500, wireTracking.getVerifyEveryMillis());
        assertEquals(0.45f, wireTracking.getRedetectIouThreshold(), 1e-6f);
        assertEquals(25, wireTracking.getMaxAgeFrames());
        assertEquals(4, wireTracking.getMinHits());
        assertTrue(wireTracking.hasLock());
        com.drones.vision.proto.v1.TargetLock wireLock = wireTracking.getLock();
        assertEquals(3L, wireLock.getLockSeq());
        assertEquals(7L, wireLock.getTrackId());
        assertFalse(wireLock.getRelease());
        // followFps is Java-side sampler rate only, never a cv-service knob (TRACKING-ORCHESTRATION
        // §4.3) -- there is no wire field for it at all, so nothing to assert here.
    }

    @Test
    void trackingConfigWithNoLockLeavesWireLockAbsent() throws Exception {
        CapturingServicer servicer = new CapturingServicer();
        GrpcDetectionPort port = newPort(servicer);
        StreamId streamId = StreamId.random();

        // PipelineConfig.defaults() carries TrackingConfig.defaults() as of docs/plans/done/TRACKING-PLAN.md
        // wave T8 -- mode ASSOCIATE, and still no lock, since a lock names a track that cannot
        // exist before the stream has produced one.
        port.detect(frame(streamId, 0, PixelFormat.BGR24), PipelineConfig.defaults())
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        FrameRequest sent = servicer.received.get(0L);
        assertTrue(sent.hasTracking());
        assertEquals(com.drones.vision.proto.v1.TrackingMode.TRACKING_MODE_ASSOCIATE, sent.getTracking().getMode());
        assertFalse(sent.getTracking().hasLock());
    }

    @Test
    void malformedTrackFieldFailsOnlyThatFrameAndSubsequentGoodFrameOnSameStreamSucceeds() throws Exception {
        // Unlike detectConversionFailureFails...(below), which fails before any session/gRPC call is
        // made, this proves the per-response failure contract *inside* an already-open
        // DetectionStreamSession: DetectionFrameCodec.decode() throwing (a non-finite velocity, here)
        // is caught per-response by DetectionStreamSession#onResponse and fails only that frame's
        // future -- the session itself, and every subsequent frame on it, is unaffected.
        GrpcDetectionPort port = newPort(new MalformedTrackFieldThenGoodServicer());
        StreamId streamId = StreamId.random();
        PipelineConfig config = PipelineConfig.defaults();

        CompletionStage<DetectionResult> failed = port.detect(frame(streamId, 0, PixelFormat.BGR24), config);
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> failed.toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());

        DetectionResult recovered = port.detect(frame(streamId, 1, PixelFormat.BGR24), config)
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1L, recovered.frameSequence());
    }

    @Test
    void detectConversionFailureFailsOnlyThatFrameAndSubsequentGoodFrameOnSameStreamSucceeds() throws Exception {
        GrpcDetectionPort port = newPort(new EchoServicer());
        StreamId streamId = StreamId.random();
        PipelineConfig config = PipelineConfig.defaults();

        // Wide enough to trigger the downscale path, but with far too little data to fill
        // width*height*3 bytes -- DetectionFrameCodec.wrapBgr24's bulk ByteBuffer#get(byte[]) throws
        // BufferUnderflowException, which GrpcDetectionPort#detect's catch turns into a failed frame
        // stage *before* any DetectionStreamSession is created for this stream.
        VideoFrame badFrame = new VideoFrame(streamId, 0, Instant.now().truncatedTo(ChronoUnit.MILLIS),
                1280, 720, PixelFormat.BGR24, ByteBuffer.wrap(new byte[]{1, 2, 3, 4}));

        CompletionStage<DetectionResult> failed = port.detect(badFrame, config);
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> failed.toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertInstanceOf(BufferUnderflowException.class, ex.getCause());

        // No session was ever created for the failed frame, so this opens a fresh one and succeeds.
        DetectionResult recovered = port.detect(frame(streamId, 1, PixelFormat.BGR24), config)
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1L, recovered.frameSequence());
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

    /** Echoes back like {@link EchoServicer}, but also records the exact wire request it received, by sequence. */
    private static final class CapturingServicer extends InferenceGrpc.InferenceImplBase {
        final Map<Long, FrameRequest> received = new ConcurrentHashMap<>();

        @Override
        public StreamObserver<FrameRequest> detectStream(StreamObserver<DetectionResponse> responseObserver) {
            return new StreamObserver<>() {
                @Override
                public void onNext(FrameRequest request) {
                    received.put(request.getSequence(), request);
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
                    responseObserver.onCompleted();
                }
            };
        }
    }

    /**
     * Frame 0's response carries one {@code Detection} with a non-finite {@code velocity_x} (a
     * malformed track field: {@link com.drones.vision.perception.domain.model.TrackRef} requires finite
     * velocities) inside an already-open session; every later frame on the same session echoes back
     * a plain, valid response.
     */
    private static final class MalformedTrackFieldThenGoodServicer extends InferenceGrpc.InferenceImplBase {
        @Override
        public StreamObserver<FrameRequest> detectStream(StreamObserver<DetectionResponse> responseObserver) {
            return new StreamObserver<>() {
                @Override
                public void onNext(FrameRequest request) {
                    DetectionResponse.Builder builder = DetectionResponse.newBuilder()
                            .setStreamId(request.getStreamId())
                            .setSequence(request.getSequence())
                            .setTimestampMillis(request.getTimestampMillis())
                            .setModelId(request.getModelId())
                            .setModelVersion(request.getModelVersion());
                    if (request.getSequence() == 0) {
                        com.drones.vision.proto.v1.BoundingBox box = com.drones.vision.proto.v1.BoundingBox.newBuilder()
                                .setX(0.1f).setY(0.1f).setWidth(0.2f).setHeight(0.2f).build();
                        com.drones.vision.proto.v1.Detection malformed = com.drones.vision.proto.v1.Detection.newBuilder()
                                .setLabel("person").setConfidence(0.9f).setBox(box)
                                .setTrackId(5)
                                .setTrackState(com.drones.vision.proto.v1.TrackState.TRACK_STATE_CONFIRMED)
                                .setSource(com.drones.vision.proto.v1.DetectionSource.DETECTION_SOURCE_DETECTOR)
                                .setVelocityX(Float.NaN)
                                .build();
                        builder.addDetections(malformed);
                    }
                    responseObserver.onNext(builder.build());
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

    /** First call: connects fine but never responds; every later call succeeds like a plain echo. */
    private static final class SilentThenEchoServicer extends InferenceGrpc.InferenceImplBase {
        private final AtomicInteger callIndex = new AtomicInteger();

        @Override
        public StreamObserver<FrameRequest> detectStream(StreamObserver<DetectionResponse> responseObserver) {
            boolean firstCall = callIndex.getAndIncrement() == 0;
            return new StreamObserver<>() {
                @Override
                public void onNext(FrameRequest request) {
                    if (firstCall) {
                        return; // deliberately never responds
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

    /**
     * First call: never responds, and races the client's own {@code RESPONSE_TIMEOUT_SECONDS}
     * timeout with a server-side transport error delivered at roughly the same instant (scheduled
     * from the first frame it receives). Every later call succeeds like a plain echo.
     */
    private static final class RacingServicer extends InferenceGrpc.InferenceImplBase {
        private final AtomicInteger callIndex = new AtomicInteger();

        @Override
        public StreamObserver<FrameRequest> detectStream(StreamObserver<DetectionResponse> responseObserver) {
            boolean firstCall = callIndex.getAndIncrement() == 0;
            AtomicBoolean racerScheduled = new AtomicBoolean(false);
            return new StreamObserver<>() {
                @Override
                public void onNext(FrameRequest request) {
                    if (firstCall) {
                        if (racerScheduled.compareAndSet(false, true)) {
                            Thread racer = new Thread(() -> {
                                try {
                                    Thread.sleep(TimeUnit.SECONDS.toMillis(GrpcCvSettings.RESPONSE_TIMEOUT_SECONDS));
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                                try {
                                    responseObserver.onError(Status.UNAVAILABLE
                                            .withDescription("simulated racing outage").asRuntimeException());
                                } catch (RuntimeException ignored) {
                                    // the client may have already cancelled the call; nothing to do
                                }
                            });
                            racer.setDaemon(true);
                            racer.start();
                        }
                        return; // deliberately never responds on the first call
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
