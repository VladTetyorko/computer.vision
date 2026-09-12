package com.drones.vision.perception.application.pipeline;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.kernel.Capability;
import com.drones.vision.perception.domain.model.CameraAttitude;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.DetectionSource;
import com.drones.vision.perception.domain.model.DetectionState;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.platform.Event;
import com.drones.vision.perception.domain.model.EventRuleConfig;
import com.drones.vision.platform.EventType;
import com.drones.vision.perception.domain.model.FollowState;
import com.drones.vision.perception.domain.model.FrameLedger;
import com.drones.vision.perception.domain.model.FrameLedgerFixtures;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.perception.domain.model.ObjectLifecycle;
import com.drones.vision.perception.domain.model.ObjectState;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.perception.domain.model.PixelFormat;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.perception.domain.model.TargetLock;
import com.drones.vision.perception.domain.model.TrackRef;
import com.drones.vision.perception.domain.model.TrackState;
import com.drones.vision.perception.domain.model.TrackingConfig;
import com.drones.vision.perception.domain.model.TrackingMode;
import com.drones.vision.perception.domain.model.TrackingTelemetry;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.perception.domain.port.DetectionPort;
import com.drones.vision.perception.domain.port.DetectionRepositoryPort;
import com.drones.vision.platform.EventPublisherPort;
import com.drones.vision.perception.domain.port.DetectionLiveUpdatePort;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Flow;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StreamPipelineTest {

    private Device device;
    private StreamId streamId;
    private DetectionPort detectionPort;
    private StreamPublisherPort streamPublisherPort;
    private DetectionRepositoryPort detectionRepositoryPort;
    private EventPublisherPort eventPublisher;

    @BeforeEach
    void setUp() {
        streamId = StreamId.random();
        device = new Device(DeviceId.random(), "cam", Set.of(Capability.VIDEO),
                new StreamDescriptor("sim", URI.create("sim://cam"), Map.of()));
        detectionPort = mock(DetectionPort.class);
        streamPublisherPort = mock(StreamPublisherPort.class);
        detectionRepositoryPort = mock(DetectionRepositoryPort.class);
        eventPublisher = mock(EventPublisherPort.class);
    }

    // docs/plans/done/CV-DEMAND-PLAN.md §1 flipped PipelineConfig.DEFAULT_DETECTION_ENABLED to false; every
    // test in this file exercises the detection machinery itself, so both helpers state
    // detectionEnabled=true explicitly rather than relying on a default this suite never meant to
    // depend on.
    private static PipelineConfig config(int inferenceFps, int maxInFlight) {
        return new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, inferenceFps, maxInFlight, Set.of(),
                EventRuleConfig.defaults(), true);
    }

    private VideoFrame frame(long sequence) {
        return new VideoFrame(streamId, sequence, Instant.now(), 64, 48, PixelFormat.JPEG,
                ByteBuffer.wrap(new byte[]{1, 2, 3}));
    }

    private VideoFrame frameAt(long sequence, Instant capturedAt) {
        return new VideoFrame(streamId, sequence, capturedAt, 64, 48, PixelFormat.JPEG,
                ByteBuffer.wrap(new byte[]{1, 2, 3}));
    }

    private DetectionResult emptyResult(long sequence) {
        return new DetectionResult(streamId, sequence, Instant.now(), List.of(), Duration.ZERO, null, null, List.of(), Optional.empty());
    }

    private DetectionResult resultWithBoxX(long sequence, Instant capturedAt, double boxX) {
        Detection detection = new Detection("person", 0.9, new BoundingBox(boxX, 0.10, 0.20, 0.20),
                new ModelRef("yolo", "latest"));
        return new DetectionResult(streamId, sequence, capturedAt, List.of(detection), Duration.ofMillis(5), null, null, List.of(), Optional.empty());
    }

    private DetectionResult nonEmptyResult(long sequence) {
        Detection detection = new Detection("person", 0.9, new BoundingBox(0.1, 0.1, 0.2, 0.2),
                new ModelRef("yolo", "latest"));
        return new DetectionResult(streamId, sequence, Instant.now(), List.of(detection), Duration.ofMillis(5), null, null, List.of(), Optional.empty());
    }

    /** @see #objectState(long, String) -- a {@code DetectionResult} whose object mirror is non-empty. */
    private DetectionResult resultWithObjects(long sequence, ObjectState... objects) {
        Detection detection = new Detection("person", 0.9, new BoundingBox(0.1, 0.1, 0.2, 0.2),
                new ModelRef("yolo", "latest"));
        return new DetectionResult(streamId, sequence, Instant.now(), List.of(detection), Duration.ofMillis(5), null,
                null, List.of(objects), Optional.empty());
    }

    /** A minimal, validly-populated {@link ObjectState} with an elected {@code label}, for read-model/filter tests. */
    private ObjectState objectState(long id, String label) {
        ObjectState.Identity identity = new ObjectState.Identity(label, label, List.of(), 1);
        return new ObjectState(id, ObjectLifecycle.CONFIRMED, streamId, identity, null, null, null, null, null, null);
    }

    /** @see #objectState(long, String) -- an object with no {@code identity}, e.g. a pure box-only coast. */
    private ObjectState objectStateWithoutIdentity(long id) {
        return new ObjectState(id, ObjectLifecycle.COASTING, streamId, null, null, null, null, null, null, null);
    }

    private static CompletableFuture<DetectionResult> failedFuture(String message) {
        CompletableFuture<DetectionResult> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException(message));
        return future;
    }

    private StreamPipeline pipeline(ScriptedVideoPublisher publisher, PipelineConfig config) {
        return new StreamPipeline(streamId, device, config, publisher, detectionPort, streamPublisherPort,
                detectionRepositoryPort, eventPublisher, StreamPipelineCollaborators.defaults());
    }

    private StreamPipeline pipeline(ScriptedVideoPublisher publisher, PipelineConfig config,
                                     DetectionEventEngine eventEngine) {
        return new StreamPipeline(streamId, device, config, publisher, detectionPort, streamPublisherPort,
                detectionRepositoryPort, eventPublisher, collaborators(Optional.of(eventEngine), Optional.empty(),
                        Optional.empty(), Optional.empty(), System::nanoTime));
    }

    private StreamPipeline pipeline(ScriptedVideoPublisher publisher, PipelineConfig config, AssetId assetId,
                                     DetectionLiveUpdatePort liveUpdatePublisherPort) {
        // ofNullable, not of(): neverAnnouncesLiveUpdatesWhenTheDeviceHasNoOwningAsset and
        // neverTouchesLiveUpdatePublisherWhenNoneIsConfigured deliberately pass a literal null here
        // to exercise StreamPipeline's own "collaborator absent" handling.
        return new StreamPipeline(streamId, device, config, publisher, detectionPort, streamPublisherPort,
                detectionRepositoryPort, eventPublisher, collaborators(Optional.empty(),
                        Optional.ofNullable(assetId), Optional.ofNullable(liveUpdatePublisherPort), Optional.empty(),
                        System::nanoTime));
    }

    /**
     * A pipeline whose cadence clock advances one {@code sourceFps} interval per frame — the seam
     * every "sample every frame" test needs now that sampling is deadline-based rather than a frame
     * stride. Pair it with a {@code config} whose {@code inferenceFps} is at least {@code sourceFps}
     * and every delivered frame serves a deadline; the default {@code System::nanoTime} cannot,
     * because a synchronous publisher delivers its whole script inside one sample interval.
     */
    private StreamPipeline pipeline(ScriptedVideoPublisher publisher, PipelineConfig config, double sourceFps) {
        return new StreamPipeline(streamId, device, config, publisher, detectionPort, streamPublisherPort,
                detectionRepositoryPort, eventPublisher, collaborators(Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), fixedFpsClock(sourceFps)));
    }

    /** @see #pipeline(ScriptedVideoPublisher, PipelineConfig, double) */
    private StreamPipeline pipeline(ScriptedVideoPublisher publisher, PipelineConfig config,
                                     DetectionEventEngine eventEngine, double sourceFps) {
        return new StreamPipeline(streamId, device, config, publisher, detectionPort, streamPublisherPort,
                detectionRepositoryPort, eventPublisher, collaborators(Optional.of(eventEngine), Optional.empty(),
                        Optional.empty(), Optional.empty(), fixedFpsClock(sourceFps)));
    }

    /** @see #pipeline(ScriptedVideoPublisher, PipelineConfig, double) */
    private StreamPipeline pipeline(ScriptedVideoPublisher publisher, PipelineConfig config, AssetId assetId,
                                     DetectionLiveUpdatePort liveUpdatePublisherPort, double sourceFps) {
        return new StreamPipeline(streamId, device, config, publisher, detectionPort, streamPublisherPort,
                detectionRepositoryPort, eventPublisher, collaborators(Optional.empty(), Optional.of(assetId),
                        Optional.of(liveUpdatePublisherPort), Optional.empty(), fixedFpsClock(sourceFps)));
    }

    private StreamPipeline pipeline(ScriptedVideoPublisher publisher, PipelineConfig config, LongSupplier clock) {
        return new StreamPipeline(streamId, device, config, publisher, detectionPort, streamPublisherPort,
                detectionRepositoryPort, eventPublisher, collaborators(Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), clock));
    }

    private StreamPipeline pipeline(ScriptedVideoPublisher publisher, PipelineConfig config,
                                     Supplier<Telemetry> telemetrySupplier) {
        return new StreamPipeline(streamId, device, config, publisher, detectionPort, streamPublisherPort,
                detectionRepositoryPort, eventPublisher, collaborators(Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.of(telemetrySupplier), System::nanoTime));
    }

    /**
     * Test seam for the outage/backoff tests below: drives {@link
     * StreamPipeline#onNext} directly (bypassing {@link
     * StreamPipeline#start()} and the {@code request()}-driven {@link
     * ScriptedVideoPublisher}), so the test can advance {@code clock}
     * precisely between individual frame arrivals -- something a
     * synchronous, recursively-delivering publisher offers no seam for.
     * {@code config}'s {@code inferenceFps} is expected to be large enough
     * (see call sites) that the sampling interval is always exactly 1,
     * decoupling these tests from the unrelated frame-cadence measurement.
     */
    private StreamPipeline manualPipeline(PipelineConfig config, LongSupplier clock) {
        StreamPipeline pipeline = new StreamPipeline(streamId, device, config, NO_OP_SOURCE, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher, collaborators(Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), clock));
        pipeline.onSubscribe(NOOP_SUBSCRIPTION);
        return pipeline;
    }

    /**
     * @see #manualPipeline(PipelineConfig, LongSupplier) -- the wave D1/D2 policy tests below need an
     *      {@code assetId}/{@code liveUpdatePublisherPort} pair to observe the live plane
     *      (publishDetections) independently of the durable plane (detectionRepositoryPort).
     */
    private StreamPipeline manualPipeline(PipelineConfig config, LongSupplier clock, AssetId assetId,
                                           DetectionLiveUpdatePort liveUpdatePublisherPort) {
        StreamPipeline pipeline = new StreamPipeline(streamId, device, config, NO_OP_SOURCE, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher, collaborators(Optional.empty(),
                        Optional.of(assetId), Optional.of(liveUpdatePublisherPort), Optional.empty(), clock));
        pipeline.onSubscribe(NOOP_SUBSCRIPTION);
        return pipeline;
    }

    /**
     * Drives the pipeline with a scripted <b>latency</b> clock (the package-private seam), leaving
     * the cadence clock at its default so the sampling behaviour under test elsewhere is untouched.
     */
    private StreamPipeline latencyPipeline(ScriptedVideoPublisher publisher, PipelineConfig config,
                                            LongSupplier latencyClock) {
        return new StreamPipeline(streamId, device, config, publisher, detectionPort, streamPublisherPort,
                detectionRepositoryPort, eventPublisher,
                new StreamPipelineCollaborators(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        fixedFpsClock(30), StreamPipelineSettings.defaults(), latencyClock, Optional.empty()));
    }

    /**
     * Shared builder for this file's many {@code pipeline(...)} overloads — every one differs only
     * in which of {@link StreamPipelineCollaborators}'s optional fields is populated and what {@code
     * nanoTimeSource} to use; {@code settings}/{@code latencyNanoSource}/{@code pullDetection} stay
     * at {@link StreamPipelineCollaborators#defaults()}'s values throughout this file except in
     * {@link #latencyPipeline}/{@link #attitudePipeline}, which build their own directly.
     */
    private static StreamPipelineCollaborators collaborators(Optional<DetectionEventEngine> eventEngine,
                                                               Optional<AssetId> assetId,
                                                               Optional<DetectionLiveUpdatePort> liveUpdatePublisherPort,
                                                               Optional<Supplier<Telemetry>> telemetrySupplier,
                                                               LongSupplier nanoTimeSource) {
        return new StreamPipelineCollaborators(eventEngine, assetId, liveUpdatePublisherPort, telemetrySupplier,
                nanoTimeSource, StreamPipelineSettings.defaults(), System::nanoTime, Optional.empty());
    }

    // --- camera attitude (docs/conclusions/CV-RATE-BUDGET.md §5, gap 3) ----------------------

    private static StreamPipelineSettings settingsWithHfov(double hfovDegrees) {
        StreamPipelineSettings base = StreamPipelineSettings.defaults();
        return new StreamPipelineSettings(base.assumedSourceFps(), base.measuredFpsEwmaAlpha(),
                base.warmupFrames(), base.minMeasuredFps(), base.maxMeasuredFps(),
                base.detectionBackoffInitialNanos(), base.detectionBackoffMaxNanos(),
                base.sourceReopenBackoffInitialNanos(), base.sourceReopenBackoffMaxNanos(),
                base.extrapolationMaxMillis(), base.extrapolationMatchGate(),
                base.trackingStatsWindow(), base.trackRetention(), base.trackingSeed(), hfovDegrees);
    }

    private StreamPipeline attitudePipeline(ScriptedVideoPublisher publisher, PipelineConfig config,
                                             Supplier<Telemetry> telemetrySupplier,
                                             StreamPipelineSettings settings) {
        return new StreamPipeline(streamId, device, config, publisher, detectionPort, streamPublisherPort,
                detectionRepositoryPort, eventPublisher,
                new StreamPipelineCollaborators(Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.of(telemetrySupplier), fixedFpsClock(30), settings, System::nanoTime,
                        Optional.empty()));
    }

    private static Telemetry telemetryWithHeading(Double headingDegrees) {
        return new Telemetry(DeviceId.random(), Instant.ofEpochMilli(4_242), 50.0, 30.0, 120.0,
                headingDegrees, 80.0, Map.of());
    }

    @Test
    void sendsTheCameraAttitudeWhenAFieldOfViewIsConfigured() {
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(frame(0)));
        when(detectionPort.detect(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(0)));

        attitudePipeline(publisher, config(60, 5), () -> telemetryWithHeading(137.5),
                settingsWithHfov(62.0)).start();

        ArgumentCaptor<CameraAttitude> captor = ArgumentCaptor.forClass(CameraAttitude.class);
        verify(detectionPort).detect(any(), any(), captor.capture());
        CameraAttitude sent = captor.getValue();
        assertEquals(137.5, sent.yawDegrees());
        assertEquals(62.0, sent.hfovDegrees());
        assertEquals(Instant.ofEpochMilli(4_242), sent.at());
        assertTrue(sent.known());
    }

    @Test
    void takesTheTwoArgumentPortCallWhenNoFieldOfViewIsConfigured() {
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(frame(0)));
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(0)));
        AtomicInteger supplierCalls = new AtomicInteger();

        // defaults() carries hfov 0 -- the shipped configuration.
        attitudePipeline(publisher, config(60, 5), () -> {
            supplierCalls.incrementAndGet();
            return telemetryWithHeading(137.5);
        }, StreamPipelineSettings.defaults()).start();

        verify(detectionPort).detect(any(), any());
        verify(detectionPort, never()).detect(any(), any(), any());
        assertEquals(0, supplierCalls.get(),
                "an unconfigured field of view must short-circuit before touching the supplier");
    }

    @Test
    void takesTheTwoArgumentPortCallWhenTelemetryCarriesNoHeading() {
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(frame(0)));
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(0)));

        attitudePipeline(publisher, config(60, 5), () -> telemetryWithHeading(null),
                settingsWithHfov(62.0)).start();

        verify(detectionPort).detect(any(), any());
        verify(detectionPort, never()).detect(any(), any(), any());
    }

    @Test
    void aThrowingTelemetrySupplierIsSwallowedAndDetectionKeepsFlowingWithAnUnknownAttitude() {
        // CRITICAL INVARIANT (docs/plans/done/CV-CLEAN-FEED-PLAN.md D-1): with the telemetry-OSD burn-in
        // gone, cameraAttitude()/readTelemetry() is telemetrySupplier's only remaining consumer --
        // this pins that a failing supplier still cannot break the pipeline (video/detection keep
        // flowing) with nothing overlay-shaped anywhere in the call. CameraAttitude.from(null, ...)
        // returns null rather than a zeroed/"unknown" instance (see its own javadoc), so a failed
        // read falls all the way back to the plain two-argument detect() overload -- exactly the
        // call a port never told about attitude would see.
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(frame(0)));
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(0)));
        Supplier<Telemetry> throwingSupplier = () -> {
            throw new RuntimeException("gps glitch");
        };

        assertDoesNotThrow(() -> attitudePipeline(publisher, config(60, 5), throwingSupplier,
                settingsWithHfov(62.0)).start());

        verify(detectionPort).detect(any(), any());
        verify(detectionPort, never()).detect(any(), any(), any());
    }

    /** Returns each scripted reading in order, then repeats the last one. */
    private static LongSupplier scriptedClock(long... readingsNanos) {
        return new LongSupplier() {
            private int index;

            @Override
            public long getAsLong() {
                long value = readingsNanos[Math.min(index, readingsNanos.length - 1)];
                index++;
                return value;
            }
        };
    }

    @Test
    void recordsTheRoundTripOfEveryCompletedDetection() {
        long ms = 1_000_000L;
        // Two readings per detection (submit, complete): 20 ms of round trip, completions 100 ms apart.
        LongSupplier latencyClock = scriptedClock(
                0L, 20 * ms,
                100 * ms, 120 * ms,
                200 * ms, 220 * ms);
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(frame(0), frame(1), frame(2)));
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(0)));

        // inferenceFps=60 against the assumed 30fps warmup rate -> everyNth = 1, so all three sample.
        StreamPipeline pipeline = latencyPipeline(publisher, config(60, 5), latencyClock);
        pipeline.start();

        PipelineLatency latency = pipeline.pipelineLatency();
        assertEquals(3L, latency.samples(), "every completed detection is recorded");
        assertEquals(20.0, latency.roundTripMillisP50());
        assertEquals(100.0, latency.updateIntervalMillisP50());
        // The whole point of the split: 20 ms of round trip, but a box is 120 ms old at worst
        // because the next one is a full sample interval away.
        assertEquals(120.0, latency.worstBoxAgeMillis(), 1e-9);
    }

    @Test
    void reportsNoLatencyBeforeTheFirstDetection() {
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of());

        PipelineLatency latency = latencyPipeline(publisher, config(60, 5), scriptedClock(0L)).pipelineLatency();

        assertEquals(0L, latency.samples());
        assertEquals(0.0, latency.worstBoxAgeMillis());
    }

    @Test
    void recordsTheRoundTripOfAFailedDetectionToo() {
        long ms = 1_000_000L;
        LongSupplier latencyClock = scriptedClock(0L, 45 * ms);
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(frame(0)));
        when(detectionPort.detect(any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("cv-service down")));

        StreamPipeline pipeline = latencyPipeline(publisher, config(60, 5), latencyClock);
        pipeline.start();

        // A stream in outage is precisely the one whose round trips matter; a failure must not be
        // silently excluded from the window.
        assertEquals(1L, pipeline.pipelineLatency().samples());
        assertEquals(45.0, pipeline.pipelineLatency().roundTripMillisP50());
    }

    private static final Flow.Publisher<VideoFrame> NO_OP_SOURCE = subscriber -> { };

    private static final Flow.Subscription NOOP_SUBSCRIPTION = new Flow.Subscription() {
        @Override
        public void request(long n) {
            // manually-driven tests never rely on re-request; nothing to do
        }

        @Override
        public void cancel() {
            // not asserted on by the manually-driven tests
        }
    };

    /**
     * A clock the test fully controls: it never advances on its own (unlike
     * {@link #fixedFpsClock}) -- only an explicit {@link #advance} call moves
     * it forward, so a test can position "now" exactly relative to a
     * detection-outage backoff deadline between individual {@code onNext}
     * calls.
     */
    private static final class SettableClock implements LongSupplier {
        private long now;

        SettableClock(long initial) {
            this.now = initial;
        }

        @Override
        public long getAsLong() {
            return now;
        }

        void advance(long nanos) {
            now += nanos;
        }
    }

    /**
     * A deterministic synthetic clock advancing by exactly {@code 1/fps}
     * seconds on every call, so a pipeline's frame-cadence measurement
     * converges to precisely {@code fps} instead of depending on real
     * wall-clock timing (which synchronous, in-test frame delivery does not
     * resemble at all).
     */
    private static LongSupplier fixedFpsClock(double fps) {
        long deltaNanos = Math.round(1_000_000_000.0 / fps);
        return new LongSupplier() {
            private long current = 0L;

            @Override
            public long getAsLong() {
                long value = current;
                current += deltaNanos;
                return value;
            }
        };
    }

    @Test
    void requestsExactlyOneFrameAtATime() {
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(frame(0), frame(1), frame(2)));
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(0)));

        pipeline(publisher, config(30, 2)).start();

        assertFalse(publisher.requestAmounts.isEmpty());
        assertTrue(publisher.requestAmounts.stream().allMatch(n -> n == 1L),
                "every request() call must ask for exactly one frame: " + publisher.requestAmounts);
    }

    @Test
    void publishesEveryFrameRegardlessOfSampling() {
        List<VideoFrame> frames = List.of(frame(0), frame(1), frame(2), frame(3));
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(frames);
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(0)));

        pipeline(publisher, config(30, 2)).start();

        for (VideoFrame f : frames) {
            verify(streamPublisherPort).publish(streamId, f);
        }
    }

    @Test
    void latestFrameIsEmptyBeforeAnyFrameHasBeenPublished() {
        StreamPipeline pipeline = pipeline(new ScriptedVideoPublisher(List.of()), config(30, 2));

        assertEquals(Optional.empty(), pipeline.latestFrame());
    }

    @Test
    void latestFrameReflectsTheRawLastPublishedFrameWhenNoOverlayIsConfigured() {
        VideoFrame f0 = frame(0);
        VideoFrame f1 = frame(1);
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(f0, f1));
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(0)));

        StreamPipeline pipeline = pipeline(publisher, config(30, 2));
        pipeline.start();

        assertEquals(Optional.of(f1), pipeline.latestFrame());
    }

    @Test
    void latestRawFrameIsEmptyBeforeAnyFrameHasArrived() {
        StreamPipeline pipeline = pipeline(new ScriptedVideoPublisher(List.of()), config(30, 2));

        assertEquals(Optional.empty(), pipeline.latestRawFrame());
    }

    @Test
    void latestRawFrameReflectsTheRawLastArrivedFrameWhenNoOverlayIsConfigured() {
        VideoFrame f0 = frame(0);
        VideoFrame f1 = frame(1);
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(f0, f1));
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(0)));

        StreamPipeline pipeline = pipeline(publisher, config(30, 2));
        pipeline.start();

        assertEquals(Optional.of(f1), pipeline.latestRawFrame());
    }

    @Test
    void latestRawFrameMirrorsLatestFrameNowThatThereIsNoOverlayStage() {
        // docs/plans/done/CV-CLEAN-FEED-PLAN.md D-1: published video is always clean pixels -- there is no
        // server-side render stage to distinguish a "pre-overlay" instance from a "post-overlay" one
        // anymore. latestRawFrame() is kept as its own named method only because training-sample
        // capture (docs/plans/done/CV-TRAINING-PLAN.md §2/§D) reaches this API by that name specifically; this
        // pins that it now simply mirrors latestFrame().
        VideoFrame f0 = frame(0);
        VideoFrame f1 = frame(1);
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(f0, f1));
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(0)));

        StreamPipeline pipeline = pipeline(publisher, config(30, 2));
        pipeline.start();

        assertEquals(Optional.of(f1), pipeline.latestFrame(), "latestFrame() sees exactly the source's own pixels");
        assertEquals(pipeline.latestFrame(), pipeline.latestRawFrame(),
                "latestRawFrame() must mirror latestFrame() -- there is no separate rendered instance anymore");
    }

    @Test
    void samplesAtTheRequestedRateAgainstASourceRateThatIsAMultipleOfIt() {
        // inferenceFps=10 against a real (constant-cadence) 30fps source ->
        // sample every 3rd frame (sequence % 3 == 0), same outcome the old
        // hardcoded-30fps assumption produced -- but now driven by the
        // *measured* rate via a synthetic 30fps clock (a synchronous test
        // delivering frames back-to-back does not itself run at 30fps in
        // wall-clock time, so the injectable clock seam is required for a
        // deterministic assertion here).
        List<VideoFrame> frames = new ArrayList<>();
        for (long i = 0; i < 9; i++) {
            frames.add(frame(i));
        }
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(frames);
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(0)));

        pipeline(publisher, config(10, 5), fixedFpsClock(30)).start();

        verify(detectionPort, times(3)).detect(any(), any()); // sequences 0, 3, 6
    }

    @Test
    void achievesExactlyTheRequestedRateForASourceRateItIsNotAMultipleOf() {
        // THE regression this sampler exists for (docs/plans/done/CV-RATE-CONTROL-PLAN.md §1, loss L1).
        // A 24fps source asked for 10fps: the integer stride this replaced could only pick
        // round(24/10) = every 2nd frame -- 12fps, a 20% overshoot it had no way to correct, and
        // one frame rate off in the other direction (25fps) would have undershot to 8.3 instead.
        // A deadline is served by exactly one frame, so one second of a 24fps source yields
        // exactly 10 samples whatever the source rate happens to be.
        List<VideoFrame> frames = new ArrayList<>();
        for (long i = 0; i < 24; i++) {
            frames.add(frame(i));
        }
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(frames);
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(0)));

        StreamPipeline pipeline = pipeline(publisher, config(10, 5), fixedFpsClock(24));
        pipeline.start();

        verify(detectionPort, times(10)).detect(any(), any());
        DetectionRate rate = pipeline.detectionRate();
        assertEquals(10L, rate.submitted());
        assertEquals(0L, rate.missedDeadlines(), "a source faster than the target starves no deadline");
    }

    @Test
    void samplesEveryFrameAndCountsMissedDeadlinesWhenTheSourceIsSlowerThanTheRequestedRate() {
        // A 5fps source asked for 10fps. Every frame is sampled -- there is nothing to hold back --
        // and the deadlines no frame arrived to serve are counted rather than silently absorbed,
        // because "the source cannot feed this rate" and "the detector cannot keep up" have
        // different fixes and used to be indistinguishable from the outside.
        List<VideoFrame> frames = new ArrayList<>();
        for (long i = 0; i < 6; i++) {
            frames.add(frame(i));
        }
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(frames);
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(0)));

        StreamPipeline pipeline = pipeline(publisher, config(10, 5), fixedFpsClock(5));
        pipeline.start();

        verify(detectionPort, times(6)).detect(any(), any());
        DetectionRate rate = pipeline.detectionRate();
        assertEquals(6L, rate.submitted());
        assertTrue(rate.missedDeadlines() > 0L, "half the deadlines had no frame to serve them");
        assertEquals(0.0, rate.dropRatio(), "starvation is not a drop -- nothing was thrown away");
    }

    @Test
    void reportsTheAssumedSourceRateUntilTheMeasurementIsTrusted() {
        // The measured source rate no longer DRIVES sampling -- it reports, and bounds what any
        // rate could achieve. The warmup contract it always had still holds: fewer than
        // StreamPipelineSettings.warmupFrames() arrivals and the configured assumption is what
        // gets reported, rather than a rate measured from too few samples to mean anything.
        List<VideoFrame> frames = List.of(frame(0), frame(1), frame(2), frame(3));
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(frames);
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(0)));

        StreamPipeline pipeline = pipeline(publisher, config(10, 5), fixedFpsClock(5));
        pipeline.start();

        assertEquals(StreamPipelineSettings.defaults().assumedSourceFps(),
                pipeline.detectionRate().sourceFps(), 1e-9);
    }

    @Test
    void clampsTheReportedSourceRateToTheConfiguredMaximum() {
        // An absurdly fast synthetic clock (a source far above any real camera, or a replay adapter
        // pushing as fast as it can) must clamp the MEASURED rate to maxMeasuredFps rather than
        // report an unbounded value -- and the sample rate must stay the requested one regardless,
        // which is the whole point of holding a deadline instead of a frame stride.
        List<VideoFrame> frames = new ArrayList<>();
        for (long i = 0; i < 10; i++) {
            frames.add(frame(i));
        }
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(frames);
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(0)));

        StreamPipeline pipeline = pipeline(publisher, config(60, 10), fixedFpsClock(1_000_000));
        pipeline.start();

        assertEquals(StreamPipelineSettings.defaults().maxMeasuredFps(),
                pipeline.detectionRate().sourceFps(), 1e-9);
        // 10 frames spanning 10 microseconds cannot contain a second 60fps deadline.
        verify(detectionPort, times(1)).detect(any(), any());
    }

    @Test
    void nonPositiveClockDeltaDoesNotCorruptSamplingOrThrow() {
        // A clock that never advances (duplicate/backward timestamps, e.g.
        // clock skew) must not divide by zero or otherwise break sampling;
        // the delta is simply ignored and the prior state is retained.
        List<VideoFrame> frames = List.of(frame(0), frame(1), frame(2));
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(frames);
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(0)));
        LongSupplier stuckClock = () -> 42L;

        pipeline(publisher, config(30, 5), stuckClock).start();

        // Still within warmup (3 < WARMUP_FRAMES=5) so the assumed 30fps
        // governs regardless -- everyNth = round(30/30) = 1, every frame
        // sampled -- and nothing threw despite the degenerate clock.
        verify(detectionPort, times(3)).detect(any(), any());
    }

    @Test
    void boundsInFlightInferencesBySkippingRatherThanQueuing() {
        List<VideoFrame> frames = List.of(frame(0), frame(1), frame(2));
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(frames);
        // Never completes, so in-flight count never drains during this test.
        when(detectionPort.detect(any(), any())).thenReturn(new CompletableFuture<>());

        // 30fps clock against inferenceFps=30: every frame serves a deadline, so all three reach
        // the in-flight bound and exactly one gets through it.
        StreamPipeline pipeline = pipeline(publisher, config(30, 1), 30.0);
        pipeline.start();

        verify(detectionPort, times(1)).detect(any(), any());
        DetectionRate rate = pipeline.detectionRate();
        assertEquals(1L, rate.submitted());
        assertEquals(2L, rate.droppedInFlight(), "the two skipped samples are counted, not silently lost");
        assertEquals(3L, rate.due());
        assertEquals(2.0 / 3.0, rate.dropRatio(), 1e-9);
    }

    @Test
    void countsSamplesWithheldDuringADetectionOutageSeparatelyFromInFlightDrops() {
        // The two losses have opposite fixes -- raise maxInFlightInferences vs. bring cv-service
        // back -- so an operator reading a rate shortfall must be able to tell them apart. Before
        // these counters both looked identical from outside: a completion rate below the
        // configured one, with nothing to say why.
        SettableClock clock = new SettableClock(0L);
        when(detectionPort.detect(any(), any())).thenAnswer(invocation -> failedFuture("cv down"));

        StreamPipeline pipeline = manualPipeline(config(10, 5), clock);
        pipeline.onNext(frame(0)); // first failure: enters the outage

        // Well inside the initial backoff, but past several sample deadlines.
        for (long sequence = 1; sequence <= 3; sequence++) {
            clock.advance(100_000_000L); // one 10fps sample interval
            pipeline.onNext(frame(sequence));
        }

        DetectionRate rate = pipeline.detectionRate();
        assertEquals(1L, rate.submitted(), "only the frame that entered the outage was ever sent");
        assertEquals(3L, rate.droppedOutage());
        assertEquals(0L, rate.droppedInFlight(), "an outage skip must never be reported as saturation");
    }

    @Test
    void persistsAndEmitsDetectionEventOnlyForNonEmptyResults() {
        VideoFrame f = frame(0);
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(f));
        DetectionResult result = nonEmptyResult(0);
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(result));

        StreamPipeline pipeline = pipeline(publisher, config(30, 2));
        pipeline.start();

        verify(detectionRepositoryPort).save(result);
        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventPublisher).publish(captor.capture());
        assertEquals(EventType.DETECTION, captor.getValue().type());
        assertEquals(streamId, captor.getValue().streamId());
        assertEquals(result.detections(), pipeline.latestDetections());
    }

    @Test
    void doesNotPersistOrEmitEventForEmptyResultsButStillUpdatesLatest() {
        VideoFrame f = frame(0);
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(f));
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(0)));

        StreamPipeline pipeline = pipeline(publisher, config(30, 2));
        pipeline.start();

        verify(detectionRepositoryPort, never()).save(any());
        verify(eventPublisher, never()).publish(argThat(e -> e.type() == EventType.DETECTION));
        assertTrue(pipeline.latestDetections().isEmpty());
    }

    @Test
    void feedsEveryCompletedResultToTheEventEngineWhenConfigured() {
        // docs/plans/done/MVP2-PLAN.md §E, E-a: DetectionEventEngine needs empty results too (that is exactly
        // what "absent" looks like for its debounce rule), so both must reach accept(), not just
        // the non-empty one that detectionRepositoryPort/eventPublisher care about above.
        VideoFrame f0 = frame(0);
        VideoFrame f1 = frame(1);
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(f0, f1));
        DetectionResult nonEmpty = nonEmptyResult(0);
        DetectionResult empty = emptyResult(1);
        when(detectionPort.detect(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(nonEmpty))
                .thenReturn(CompletableFuture.completedFuture(empty));
        DetectionEventEngine eventEngine = mock(DetectionEventEngine.class);

        pipeline(publisher, config(30, 2), eventEngine, 30.0).start();

        verify(eventEngine).accept(nonEmpty);
        verify(eventEngine).accept(empty);
    }

    @Test
    void neverTouchesTheEventEngineWhenNoneIsConfigured() {
        // Documents/protects the nullable-collaborator contract: every constructor that doesn't
        // mention eventEngine must default it to null without ever NPE-ing on a completed result.
        VideoFrame f = frame(0);
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(f));
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(0)));

        assertDoesNotThrow(() -> pipeline(publisher, config(30, 2)).start());
    }

    @Test
    void announcesEveryCompletedResultAsALiveUpdateWhenConfiguredWithAnOwningAsset() {
        // docs/plans/done/REALTIME-PLAN.md §4: empty results matter here too -- "nothing detected now" is
        // itself useful live information, mirroring the eventEngine precedent above exactly.
        VideoFrame f0 = frame(0);
        VideoFrame f1 = frame(1);
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(f0, f1));
        DetectionResult nonEmpty = nonEmptyResult(0);
        DetectionResult empty = emptyResult(1);
        when(detectionPort.detect(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(nonEmpty))
                .thenReturn(CompletableFuture.completedFuture(empty));
        AssetId assetId = AssetId.random();
        DetectionLiveUpdatePort liveUpdatePublisherPort = mock(DetectionLiveUpdatePort.class);

        pipeline(publisher, config(30, 2), assetId, liveUpdatePublisherPort, 30.0).start();

        verify(liveUpdatePublisherPort).publishDetections(assetId, nonEmpty);
        verify(liveUpdatePublisherPort).publishDetections(assetId, empty);
    }

    @Test
    void neverAnnouncesLiveUpdatesWhenTheDeviceHasNoOwningAsset() {
        // A configured port but no resolved assetId (the device isn't wrapped by any asset yet)
        // must never announce -- mirrors UsageTracker's own "untracked device" convention.
        VideoFrame f = frame(0);
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(f));
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(0)));
        DetectionLiveUpdatePort liveUpdatePublisherPort = mock(DetectionLiveUpdatePort.class);

        pipeline(publisher, config(30, 2), null, liveUpdatePublisherPort).start();

        verifyNoInteractions(liveUpdatePublisherPort);
    }

    @Test
    void neverTouchesLiveUpdatePublisherWhenNoneIsConfigured() {
        // Documents/protects the nullable-collaborator contract, same as neverTouchesTheEventEngine...
        // above: every constructor that doesn't mention liveUpdatePublisherPort must default it to
        // null without ever NPE-ing on a completed result.
        VideoFrame f = frame(0);
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(f));
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(0)));

        assertDoesNotThrow(() -> pipeline(publisher, config(30, 2), AssetId.random(), null).start());
    }

    @Test
    void detectionFailureDoesNotClosePipelineAndVideoKeepsFlowing() {
        // Resilience policy (docs/plans/done/MVP1-PLAN.md §C7): a failing/absent CV
        // service must never kill or degrade the video path. One
        // PIPELINE_ERROR event still marks the outage, but frames keep
        // publishing and the subscription is never cancelled.
        List<VideoFrame> frames = List.of(frame(0), frame(1), frame(2));
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(frames);
        when(detectionPort.detect(any(), any())).thenReturn(failedFuture("boom"));

        pipeline(publisher, config(1000, 2)).start();

        for (VideoFrame f : frames) {
            verify(streamPublisherPort).publish(streamId, f);
        }
        verify(streamPublisherPort, never()).streamEnded(streamId);
        assertFalse(publisher.cancelled, "a detection failure must not cancel the source subscription");
        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventPublisher, atLeastOnce()).publish(captor.capture());
        assertEquals(1, captor.getAllValues().stream().filter(e -> e.type() == EventType.PIPELINE_ERROR).count());
    }

    @Test
    void backoffDoublesOnEachFailedProbeUpToTheCapAndEmitsExactlyOneEvent() {
        // Every sampled frame while in outage either produces no detect()
        // call at all (still backing off) or exactly one (the probe once
        // the deadline passes); a failed probe doubles the backoff, capped
        // at StreamPipeline.MAX_BACKOFF_NANOS -- and however many probes
        // fail, only the very first failure ever raised PIPELINE_ERROR.
        SettableClock clock = new SettableClock(0L);
        when(detectionPort.detect(any(), any())).thenAnswer(invocation -> failedFuture("cv down"));

        StreamPipeline pipeline = manualPipeline(config(1000, 5), clock);

        pipeline.onNext(frame(0)); // first failure: enters the outage, backoff = INITIAL_BACKOFF_NANOS
        int expectedDetectCalls = 1;
        verify(detectionPort, times(expectedDetectCalls)).detect(any(), any());

        long activeBackoff = StreamPipeline.INITIAL_BACKOFF_NANOS;
        long sequence = 1;
        while (activeBackoff < StreamPipeline.MAX_BACKOFF_NANOS) {
            // One nanosecond short of the deadline: still skipped, no new detect() call.
            clock.advance(activeBackoff - 1);
            pipeline.onNext(frame(sequence++));
            verify(detectionPort, times(expectedDetectCalls)).detect(any(), any());

            // Crossing the deadline: exactly one probe, which fails and doubles the backoff (capped).
            clock.advance(1);
            pipeline.onNext(frame(sequence++));
            expectedDetectCalls++;
            verify(detectionPort, times(expectedDetectCalls)).detect(any(), any());

            activeBackoff = Math.min(activeBackoff * 2, StreamPipeline.MAX_BACKOFF_NANOS);
        }

        // Backoff is now capped: one more full cap-length wait still yields exactly one further
        // probe -- never sooner, and the cap never grows past MAX_BACKOFF_NANOS.
        clock.advance(StreamPipeline.MAX_BACKOFF_NANOS - 1);
        pipeline.onNext(frame(sequence++));
        verify(detectionPort, times(expectedDetectCalls)).detect(any(), any());

        clock.advance(1);
        pipeline.onNext(frame(sequence));
        expectedDetectCalls++;
        verify(detectionPort, times(expectedDetectCalls)).detect(any(), any());

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventPublisher, atLeastOnce()).publish(captor.capture());
        assertEquals(1, captor.getAllValues().stream().filter(e -> e.type() == EventType.PIPELINE_ERROR).count());
    }

    @Test
    void recoveringProbeResumesDetectionResetsBackoffAndANewOutageEmitsANewEvent() {
        SettableClock clock = new SettableClock(0L);
        DetectionResult recovered = nonEmptyResult(2);
        when(detectionPort.detect(any(), any()))
                .thenReturn(failedFuture("cv down"))                           // frame(0): enters outage
                .thenReturn(failedFuture("still down"))                       // probe 1: fails, backoff -> 2s
                .thenReturn(CompletableFuture.completedFuture(recovered))     // probe 2: succeeds, recovers
                .thenReturn(CompletableFuture.completedFuture(emptyResult(3))) // resumed normal detection
                .thenReturn(failedFuture("down again"));                     // a brand-new outage

        StreamPipeline pipeline = manualPipeline(config(1000, 5), clock);

        pipeline.onNext(frame(0)); // t=0: fails -> outage begins, backoff=1s, deadline=1s
        clock.advance(StreamPipeline.INITIAL_BACKOFF_NANOS); // t=1s: cross the deadline
        pipeline.onNext(frame(1)); // probe fails -> backoff doubles to 2s, deadline=3s
        clock.advance(2 * StreamPipeline.INITIAL_BACKOFF_NANOS); // t=3s: cross the doubled deadline
        pipeline.onNext(frame(2)); // probe succeeds -> recovers, backoff reset to 1s
        assertEquals(recovered.detections(), pipeline.latestDetections());

        pipeline.onNext(frame(3)); // no clock advance needed: back on the normal (non-outage) path
        verify(detectionPort, times(4)).detect(any(), any());

        pipeline.onNext(frame(4)); // fails again -> a brand-new outage
        // Exactly one *reset* (1s) window later -- a stale leftover 2s backoff would still be
        // short of its deadline here, so this only passes if the reset actually took effect.
        clock.advance(StreamPipeline.INITIAL_BACKOFF_NANOS);
        pipeline.onNext(frame(5));
        verify(detectionPort, times(6)).detect(any(), any());

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventPublisher, times(3)).publish(captor.capture());
        List<EventType> types = captor.getAllValues().stream().map(Event::type).toList();
        assertEquals(List.of(EventType.PIPELINE_ERROR, EventType.DETECTION, EventType.PIPELINE_ERROR), types);
    }

    @Test
    void lateDetectionFailureAfterCloseIsASilentNoOp() {
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(frame(0)));
        CompletableFuture<DetectionResult> pending = new CompletableFuture<>(); // never completes during start()
        when(detectionPort.detect(any(), any())).thenReturn(pending);

        StreamPipeline pipeline = pipeline(publisher, config(1000, 2));
        pipeline.start();
        pipeline.close();

        pending.completeExceptionally(new RuntimeException("late failure"));

        verify(eventPublisher, never()).publish(argThat(e -> e.type() == EventType.PIPELINE_ERROR));
        verify(streamPublisherPort, times(1)).streamEnded(streamId);
    }

    @Test
    void sourceErrorEmitsPipelineErrorAndStopsCleanly() {
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of());
        publisher.errorAfterFrames(new RuntimeException("source dead"));

        pipeline(publisher, config(30, 2)).start();

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventPublisher).publish(captor.capture());
        assertEquals(EventType.PIPELINE_ERROR, captor.getValue().type());
        verify(streamPublisherPort).streamEnded(streamId);
    }

    @Test
    void closeIsIdempotent() {
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of());
        StreamPipeline pipeline = pipeline(publisher, config(30, 2));
        pipeline.start();

        pipeline.close();
        pipeline.close();

        verify(streamPublisherPort, times(1)).streamEnded(streamId);
    }

    // --- docs/plans/done/CV-CONTROL-PLAN.md Wave C: live config update, skip-detect, label-filter enforcement ---

    private DetectionResult resultWithLabels(long sequence, String... labels) {
        List<Detection> detections = new ArrayList<>();
        for (String label : labels) {
            detections.add(new Detection(label, 0.9, new BoundingBox(0.1, 0.1, 0.2, 0.2),
                    new ModelRef("yolo", "latest")));
        }
        return new DetectionResult(streamId, sequence, Instant.now(), detections, Duration.ofMillis(5), null, null, List.of(), Optional.empty());
    }

    @Test
    void configReturnsTheCurrentlyActivePipelineConfigAndReflectsAnUpdate() {
        StreamPipeline pipeline = pipeline(new ScriptedVideoPublisher(List.of()), config(30, 2));

        assertEquals(30, pipeline.config().inferenceFps());

        PipelineConfig next = new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 15, 2, Set.of());
        pipeline.updateConfig(next);

        assertEquals(15, pipeline.config().inferenceFps());
    }

    @Test
    void updateConfigRejectsNull() {
        StreamPipeline pipeline = pipeline(new ScriptedVideoPublisher(List.of()), config(30, 2));

        assertThrows(NullPointerException.class, () -> pipeline.updateConfig(null));
    }

    @Test
    void updateConfigAppliesHotKnobsToTheNextSampledFrameWithoutClearingAnyModelBoundState() {
        DetectionResult firstResult = nonEmptyResult(0);
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(firstResult));
        StreamPipeline pipeline = manualPipeline(config(1000, 5), () -> 0L);

        pipeline.onNext(frame(0));
        assertEquals(firstResult.detections(), pipeline.latestDetections());

        // Same model id ("yolo"): a hot-knob-only patch must never behave like a re-arm. detectionEnabled
        // stated explicitly (docs/plans/done/CV-DEMAND-PLAN.md §1 flipped the convenience-ctor default) since
        // this test's whole point is that detection keeps running across the swap.
        PipelineConfig hotter = new PipelineConfig(new ModelRef("yolo", "latest"), 0.75, 1000, 5, Set.of(),
                EventRuleConfig.defaults(), true);
        pipeline.updateConfig(hotter);

        assertEquals(firstResult.detections(), pipeline.latestDetections(),
                "a non-model config swap must not clear latestDetections the way a model re-arm does");

        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(1)));
        pipeline.onNext(frame(1));

        ArgumentCaptor<PipelineConfig> configCaptor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(detectionPort, times(2)).detect(any(), configCaptor.capture());
        assertEquals(0.4, configCaptor.getAllValues().get(0).confidenceThreshold());
        assertEquals(0.75, configCaptor.getAllValues().get(1).confidenceThreshold(),
                "the very next sampled frame must already carry the swapped-in confidence threshold");
    }

    @Test
    void detectionEnabledFalseSkipsDetectEntirelyWhileVideoKeepsPublishing() {
        List<VideoFrame> frames = List.of(frame(0), frame(1), frame(2));
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(frames);
        PipelineConfig detectionOff = new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 30, 2, Set.of(),
                EventRuleConfig.defaults(), false);
        StreamPipeline pipeline = pipeline(publisher, detectionOff);

        pipeline.start();

        verify(detectionPort, never()).detect(any(), any());
        for (VideoFrame f : frames) {
            verify(streamPublisherPort).publish(streamId, f);
        }
        assertEquals(Optional.of(frames.get(frames.size() - 1)), pipeline.latestFrame(),
                "video must keep flowing/advancing latestFrame while detection is off");
    }

    @Test
    void detectionResumesOnTheNextSampledFrameAfterReEnabling() {
        PipelineConfig detectionOff = new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 1000, 5, Set.of(),
                EventRuleConfig.defaults(), false);
        StreamPipeline pipeline = manualPipeline(detectionOff, () -> 0L);

        pipeline.onNext(frame(0));
        pipeline.onNext(frame(1));
        verify(detectionPort, never()).detect(any(), any());
        verify(streamPublisherPort, times(2)).publish(eq(streamId), any());

        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(2)));
        PipelineConfig detectionOn = new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 1000, 5, Set.of(),
                EventRuleConfig.defaults(), true);
        pipeline.updateConfig(detectionOn);
        pipeline.onNext(frame(2));

        verify(detectionPort, times(1)).detect(any(), any());
    }

    // --- docs/plans/done/CV-DEMAND-PLAN.md §1, §3.2: detection demand -- the second, independent gate ---

    @Test
    void detectionDemandDefaultsToTrueAndReflectsUpdates() {
        StreamPipeline pipeline = pipeline(new ScriptedVideoPublisher(List.of()), config(30, 2));

        assertTrue(pipeline.detectionDemand(),
                "fail-open: a pipeline with no DetectionDemandPort ever wired must stay demanded");

        pipeline.updateDetectionDemand(false);
        assertFalse(pipeline.detectionDemand());

        pipeline.updateDetectionDemand(true);
        assertTrue(pipeline.detectionDemand());
    }

    /**
     * The gate truth table: {@code effective = detectionEnabled && detectionDemand}. {@code
     * enabled=true, demand=true} (the default combination -- {@link #config} defaults
     * {@code detectionEnabled=true} and {@link StreamPipeline#detectionDemand()} initializes {@code
     * true}) is already exercised by every other detection test in this file; the three cells below
     * complete the table.
     */
    @Test
    void detectionRunsWhenBothDetectionEnabledAndDetectionDemandAreTrue() {
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(frame(0)));
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(0)));

        pipeline(publisher, config(30, 2)).start();

        verify(detectionPort).detect(any(), any());
    }

    @Test
    void detectionDemandFalseSkipsDetectEntirelyEvenWhenDetectionEnabledIsTrue() {
        // The mirror of detectionEnabledFalseSkipsDetectEntirelyWhileVideoKeepsPublishing above:
        // detectionEnabled=true alone is not enough once nobody is consuming the output.
        List<VideoFrame> frames = List.of(frame(0), frame(1), frame(2));
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(frames);
        StreamPipeline pipeline = pipeline(publisher, config(30, 2));
        pipeline.updateDetectionDemand(false);

        pipeline.start();

        verify(detectionPort, never()).detect(any(), any());
        for (VideoFrame f : frames) {
            verify(streamPublisherPort).publish(streamId, f);
        }
        assertEquals(Optional.of(frames.get(frames.size() - 1)), pipeline.latestFrame(),
                "video must keep flowing while detection is merely undemanded");
    }

    @Test
    void detectionStaysOffWhenBothDetectionEnabledAndDetectionDemandAreFalse() {
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(frame(0), frame(1)));
        PipelineConfig detectionOff = new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 30, 2, Set.of(),
                EventRuleConfig.defaults(), false);
        StreamPipeline pipeline = pipeline(publisher, detectionOff);
        pipeline.updateDetectionDemand(false);

        pipeline.start();

        verify(detectionPort, never()).detect(any(), any());
    }

    @Test
    void detectionResumesOnTheNextSampledFrameAfterDemandReturns() {
        StreamPipeline pipeline = manualPipeline(config(1000, 5), () -> 0L);
        pipeline.updateDetectionDemand(false);

        pipeline.onNext(frame(0));
        verify(detectionPort, never()).detect(any(), any());

        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(1)));
        pipeline.updateDetectionDemand(true);
        pipeline.onNext(frame(1));

        verify(detectionPort, times(1)).detect(any(), any());
    }

    // --- docs/plans/done/CV-DEMAND-PLAN.md §3.6: DetectionState -- the gate made legible ---

    @Test
    void detectionStateIsRunningWhenBothDetectionEnabledAndDetectionDemandAreTrue() {
        StreamPipeline pipeline = pipeline(new ScriptedVideoPublisher(List.of()), config(30, 2));

        assertEquals(DetectionState.RUNNING, pipeline.detectionState());
    }

    @Test
    void detectionStateIsIdleNoViewersWhenEnabledButDemandIsFalse() {
        StreamPipeline pipeline = pipeline(new ScriptedVideoPublisher(List.of()), config(30, 2));
        pipeline.updateDetectionDemand(false);

        assertEquals(DetectionState.IDLE_NO_VIEWERS, pipeline.detectionState());
    }

    @Test
    void detectionStateIsOffWhenDetectionEnabledIsFalseRegardlessOfDemandAndTakesPrecedenceOverIdleNoViewers() {
        PipelineConfig detectionOff = new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 30, 2, Set.of(),
                EventRuleConfig.defaults(), false);
        StreamPipeline pipeline = pipeline(new ScriptedVideoPublisher(List.of()), detectionOff);

        assertEquals(DetectionState.OFF, pipeline.detectionState(),
                "detectionEnabled=false with demand still at its fail-open true default");

        pipeline.updateDetectionDemand(false);
        assertEquals(DetectionState.OFF, pipeline.detectionState(),
                "OFF beats IDLE_NO_VIEWERS -- the operator's own choice is the more specific truth, "
                        + "so disabling detection must never also read as 'nobody is watching'");
    }

    // --- docs/plans/done/CV-DEMAND-PLAN.md §5/§7: closing the gate clears what it already served,
    // not just what it would have served next -- reversing this task's own first-cut decision that
    // a frozen last result was harmless. It is not: DetectionsStore polls every 2s and only preserves
    // a stale value on an *empty or failed* poll, so an un-cleared frozen result keeps being served,
    // forever, as though it were live. CLAUDE.md §9: "Newest data ... should be used, even if
    // previous is still available." ---

    @Test
    void closingTheDetectionEnabledGateClearsEstablishedBoxesAndTheRateWindowThenReopeningResumesFreshDetection() {
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(0)));
        StreamPipeline pipeline = manualPipeline(config(1000, 5), () -> 0L);

        pipeline.onNext(frame(0));
        assertFalse(pipeline.latestDetections().isEmpty(), "boxes must be established before the gate closes");
        assertEquals(1L, pipeline.detectionRate().submitted());
        assertEquals(1L, pipeline.pipelineLatency().samples());

        // Same model id ("yolo"): this is a hot-knob-only patch except for detectionEnabled, so any
        // clearing observed below is attributable to the gate closing, not to a model re-arm.
        PipelineConfig detectionOff = new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 1000, 5, Set.of(),
                EventRuleConfig.defaults(), false);
        pipeline.updateConfig(detectionOff);

        assertTrue(pipeline.latestDetections().isEmpty(),
                "turning detection off must not leave the last boxes standing -- they would be re-served "
                        + "every poll as though still live");
        assertTrue(pipeline.tracks().isEmpty());
        assertEquals(0L, pipeline.detectionRate().submitted(),
                "the rate window must not keep reporting a stale submitted count once the state reads OFF");
        assertEquals(0L, pipeline.pipelineLatency().samples());

        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(1)));
        PipelineConfig detectionOn = new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 1000, 5, Set.of(),
                EventRuleConfig.defaults(), true);
        pipeline.updateConfig(detectionOn);
        pipeline.onNext(frame(1));

        assertFalse(pipeline.latestDetections().isEmpty(),
                "re-enabling must resume real detection on the next sampled frame, not just stop hiding "
                        + "the old one");
    }

    @Test
    void closingTheDetectionDemandGateClearsEstablishedBoxesAndTheRateWindowThenReopeningResumesFreshDetection() {
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(0)));
        StreamPipeline pipeline = manualPipeline(config(1000, 5), () -> 0L);

        pipeline.onNext(frame(0));
        assertFalse(pipeline.latestDetections().isEmpty(), "boxes must be established before the gate closes");
        assertEquals(1L, pipeline.detectionRate().submitted());

        pipeline.updateDetectionDemand(false);

        assertTrue(pipeline.latestDetections().isEmpty(),
                "IDLE_NO_VIEWERS must not keep re-serving the last viewer's boxes to whoever polls next");
        assertTrue(pipeline.tracks().isEmpty());
        assertEquals(0L, pipeline.detectionRate().submitted());
        assertEquals(0L, pipeline.pipelineLatency().samples());

        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(1)));
        pipeline.updateDetectionDemand(true);
        pipeline.onNext(frame(1));

        assertFalse(pipeline.latestDetections().isEmpty(),
                "a returning viewer must get boxes from fresh inference, not a snapshot from before they left");
    }

    @Test
    void repeatedlyConfirmingDemandIsStillGoneDoesNotCorruptTheEdgeTrackingNeededToReopenLater() {
        // A demand-poll scheduler calls updateDetectionDemand(false) on every tick while nobody is
        // watching, not just once on the transition. handleDetectionGateTransition() must key off the
        // true->false *edge*, not the level, or repeated confirmations would spuriously re-clear on
        // every tick -- harmless to observable state here, but this also guards against a broken edge
        // flag getting stuck and refusing to recognise the eventual true transition below.
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(0)));
        StreamPipeline pipeline = manualPipeline(config(1000, 5), () -> 0L);
        pipeline.onNext(frame(0));

        pipeline.updateDetectionDemand(false);
        pipeline.updateDetectionDemand(false);
        pipeline.updateDetectionDemand(false);
        assertTrue(pipeline.latestDetections().isEmpty());

        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(1)));
        pipeline.updateDetectionDemand(true);
        pipeline.onNext(frame(1));

        assertFalse(pipeline.latestDetections().isEmpty(),
                "repeated false confirmations must not prevent the gate from recognising the later true");
    }

    @Test
    void aResultCompletingAfterTheGateClosedIsDroppedNotResurrectingTheStateTheCloseJustCleared() {
        // The in-flight race: submitDetection()'s CompletableFuture can complete on an arbitrary
        // executor thread after updateConfig/updateDetectionDemand has already closed the gate and
        // cleared state. onDetectionResult() re-checks detectionGateOpen() on entry specifically so a
        // late-arriving result (submitted while open, landing after close) is dropped rather than
        // silently repopulating latestDetections right after handleDetectionGateTransition() emptied it.
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(0)));
        StreamPipeline pipeline = manualPipeline(config(1000, 5), () -> 0L);
        pipeline.onNext(frame(0));
        assertFalse(pipeline.latestDetections().isEmpty(), "boxes must be established before the race is exercised");

        CompletableFuture<DetectionResult> pending = new CompletableFuture<>();
        when(detectionPort.detect(any(), any())).thenReturn(pending);
        pipeline.onNext(frame(1)); // submitted while the gate is still open; its completion is delayed

        PipelineConfig detectionOff = new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 1000, 5, Set.of(),
                EventRuleConfig.defaults(), false);
        pipeline.updateConfig(detectionOff); // gate closes -- clears the boxes established above
        assertTrue(pipeline.latestDetections().isEmpty());

        pending.complete(nonEmptyResult(1)); // the in-flight inference, submitted before the close, lands late

        assertTrue(pipeline.latestDetections().isEmpty(),
                "a result computed before the gate closed must not resurrect the state the close just cleared");
        verify(detectionRepositoryPort, times(1)).save(any());
    }

    // --- docs/plans/active/ALWAYS-ON-FLOW-PLAN.md wave D1/D2: DetectionPolicy.ALWAYS -- a third,
    // independent widener of the inference+durable gate that must never widen the live gate -------

    @Test
    void detectionPolicyAlwaysOnDefaultsToFalseAndIsInertWithoutIt() {
        StreamPipeline pipeline = pipeline(new ScriptedVideoPublisher(List.of()), config(30, 2));

        assertFalse(pipeline.detectionPolicyAlwaysOn(),
                "fail-closed default: a pipeline with no DetectionPolicyPort ever wired must not widen the gate");

        pipeline.updateDetectionDemand(false);
        assertEquals(DetectionState.IDLE_NO_VIEWERS, pipeline.detectionState(),
                "an inert (false) policy must leave the pre-existing demand-only truth table untouched");
    }

    /**
     * The D1 widening itself: {@code ALWAYS} with nobody watching still runs inference and still
     * persists, but must not touch anything the live gate governs -- {@link #latestDetections()}
     * stays empty and {@link DetectionLiveUpdatePort#publishDetections} is never called.
     */
    @Test
    void detectionPolicyAlwaysOnWidensTheInferenceAndDurableGateWhenDemandIsFalse() {
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(0)));
        AssetId assetId = AssetId.random();
        DetectionLiveUpdatePort liveUpdatePublisherPort = mock(DetectionLiveUpdatePort.class);
        StreamPipeline pipeline = manualPipeline(config(1000, 5), () -> 0L, assetId, liveUpdatePublisherPort);
        pipeline.updateDetectionDemand(false);
        pipeline.updateDetectionPolicy(true);

        assertEquals(DetectionState.RUNNING_UNWATCHED, pipeline.detectionState(),
                "ALWAYS with nobody watching is a third, distinct state -- inference runs, but no viewer");

        pipeline.onNext(frame(0));

        verify(detectionPort, times(1)).detect(any(), any());
        verify(detectionRepositoryPort, times(1)).save(any());
        assertTrue(pipeline.latestDetections().isEmpty(),
                "the live gate stayed closed (no demand) -- live read models must not populate for an unwatched stream");
        verify(liveUpdatePublisherPort, never()).publishDetections(any(), any());
    }

    /**
     * The hardest part (docs/plans/active/ALWAYS-ON-FLOW-PLAN.md &sect;4): an {@code ALWAYS} asset's
     * last viewer leaving is the live-gate-only edge -- {@link StreamPipeline#handleDetectionGateTransition}
     * must clear exactly the live-plane read models (mirroring {@link
     * #closingTheDetectionDemandGateClearsEstablishedBoxesAndTheRateWindowThenReopeningResumesFreshDetection}),
     * while leaving {@link #pipelineLatency()}/{@link #detectionRate()} alone and continuing to submit
     * inference and persist -- because inference itself never stopped.
     */
    @Test
    void alwaysPolicyKeepsDetectionRunningAfterTheLastViewerLeavesButClearsOnlyLiveReadModels() {
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(0)));
        AssetId assetId = AssetId.random();
        DetectionLiveUpdatePort liveUpdatePublisherPort = mock(DetectionLiveUpdatePort.class);
        StreamPipeline pipeline = manualPipeline(config(1000, 5), () -> 0L, assetId, liveUpdatePublisherPort);
        pipeline.updateDetectionPolicy(true); // opted into ALWAYS

        pipeline.onNext(frame(0)); // demand still true (the default) -- a viewer is watching
        assertFalse(pipeline.latestDetections().isEmpty(), "boxes must be established while watched, before the edge under test");
        assertEquals(1L, pipeline.detectionRate().submitted());
        assertEquals(1L, pipeline.pipelineLatency().samples());
        verify(liveUpdatePublisherPort, times(1)).publishDetections(eq(assetId), any());

        pipeline.updateDetectionDemand(false); // the last viewer leaves; ALWAYS keeps inference open

        assertEquals(DetectionState.RUNNING_UNWATCHED, pipeline.detectionState(),
                "ALWAYS keeps inference running even though the live gate just closed");
        assertTrue(pipeline.latestDetections().isEmpty(), "live read models clear on the live-only edge");
        assertTrue(pipeline.tracks().isEmpty());
        assertEquals(1L, pipeline.detectionRate().submitted(),
                "detector-health windows are tied to inference, not viewership -- they must survive a live-only close");
        assertEquals(1L, pipeline.pipelineLatency().samples());

        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(1)));
        pipeline.onNext(frame(1));

        verify(detectionPort, times(2)).detect(any(), any());
        verify(detectionRepositoryPort, times(2)).save(any());
        assertTrue(pipeline.latestDetections().isEmpty(),
                "durable keeps saving for the unwatched ALWAYS stream, but live read models stay empty");
        verify(liveUpdatePublisherPort, times(1)).publishDetections(eq(assetId), any());
        // still only once -- the second (post-edge) detection must never reach the live publisher
    }

    /**
     * The mirror of {@link #closingTheDetectionEnabledGateClearsEstablishedBoxesAndTheRateWindowThenReopeningResumesFreshDetection}
     * for the wider D2 gate: with {@code ALWAYS} set, demand alone going false must NOT do the full
     * clear (that would wipe {@link #detectionRate()}/{@link #pipelineLatency()} out from under an
     * inference loop that is still running) -- only {@link PipelineConfig#detectionEnabled()} going
     * false, or {@code ALWAYS} itself being revoked, closes the wider inference gate and triggers the
     * full clear.
     */
    @Test
    void revokingAlwaysPolicyWithDemandAlreadyFalseClosesTheInferenceGateAndDoesTheFullClear() {
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(0)));
        StreamPipeline pipeline = manualPipeline(config(1000, 5), () -> 0L);
        pipeline.updateDetectionPolicy(true);
        pipeline.updateDetectionDemand(false);
        pipeline.onNext(frame(0));
        assertEquals(1L, pipeline.detectionRate().submitted(), "inference must still be running: ALWAYS, no viewer");

        pipeline.updateDetectionPolicy(false); // the operator revokes ALWAYS; demand is already false

        assertEquals(DetectionState.IDLE_NO_VIEWERS, pipeline.detectionState(),
                "with ALWAYS gone and demand already false, the wider gate is now closed too");
        assertEquals(0L, pipeline.detectionRate().submitted(),
                "the inference gate itself closed this time -- the full clear must run, unlike the live-only edge");
        assertEquals(0L, pipeline.pipelineLatency().samples());
    }

    @Test
    void repeatedlyConfirmingAlwaysPolicyIsStillSetDoesNotCorruptTheLiveEdgeTracking() {
        // Mirrors repeatedlyConfirmingDemandIsStillGoneDoesNotCorruptTheEdgeTrackingNeededToReopenLater:
        // a policy-poll scheduler calls updateDetectionPolicy(true) on every tick, not just once on the
        // transition -- handleDetectionGateTransition() must key off the edge for EACH gate, not the level.
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(0)));
        StreamPipeline pipeline = manualPipeline(config(1000, 5), () -> 0L);
        pipeline.updateDetectionPolicy(true);
        pipeline.onNext(frame(0));

        pipeline.updateDetectionDemand(false);
        pipeline.updateDetectionPolicy(true);
        pipeline.updateDetectionPolicy(true);
        pipeline.updateDetectionPolicy(true);
        assertTrue(pipeline.latestDetections().isEmpty(), "the live-only edge already fired once");
        assertEquals(1L, pipeline.detectionRate().submitted(), "repeated true confirmations must not re-clear durable state");

        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(1)));
        pipeline.updateDetectionDemand(true);
        pipeline.onNext(frame(1));

        assertFalse(pipeline.latestDetections().isEmpty(),
                "repeated ALWAYS confirmations must not prevent the live gate from recognising demand's later true");
    }

    /**
     * The live-gate race, independent of the inference-gate race {@link
     * #aResultCompletingAfterTheGateClosedIsDroppedNotResurrectingTheStateTheCloseJustCleared} already
     * covers: a result submitted while a viewer was watching an {@code ALWAYS} stream can complete
     * <em>after</em> that viewer has since left. {@link StreamPipeline#onDetectionResult} must still
     * save it durably (the inference gate never closed) but must not resurrect the live read models
     * the live-only edge already cleared.
     */
    @Test
    void aResultCompletingAfterOnlyTheLiveGateClosedIsStillSavedDurablyButDoesNotResurrectLiveState() {
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(0)));
        AssetId assetId = AssetId.random();
        DetectionLiveUpdatePort liveUpdatePublisherPort = mock(DetectionLiveUpdatePort.class);
        StreamPipeline pipeline = manualPipeline(config(1000, 5), () -> 0L, assetId, liveUpdatePublisherPort);
        pipeline.updateDetectionPolicy(true);
        pipeline.onNext(frame(0));
        assertFalse(pipeline.latestDetections().isEmpty(), "boxes must be established before the race is exercised");

        CompletableFuture<DetectionResult> pending = new CompletableFuture<>();
        when(detectionPort.detect(any(), any())).thenReturn(pending);
        pipeline.onNext(frame(1)); // submitted while the live gate is still open; its completion is delayed

        pipeline.updateDetectionDemand(false); // the viewer leaves -- live-only edge clears latestDetections
        assertTrue(pipeline.latestDetections().isEmpty());

        pending.complete(nonEmptyResult(1)); // the in-flight inference, submitted before the viewer left, lands late

        assertTrue(pipeline.latestDetections().isEmpty(),
                "a result computed before the live-only close must not resurrect the live state that close cleared");
        verify(detectionRepositoryPort, times(2)).save(any());
        verify(liveUpdatePublisherPort, times(1)).publishDetections(eq(assetId), any());
        // still only once -- the durable save for the late result must happen, but never its live counterpart
    }

    @Test
    void emptyLabelFilterKeepsEveryDetection() {
        VideoFrame f = frame(0);
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(f));
        DetectionResult result = resultWithLabels(0, "person", "car");
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(result));

        StreamPipeline pipeline = pipeline(publisher, config(30, 2)); // config(...) helper's labelFilter is Set.of()
        pipeline.start();

        assertEquals(List.of("person", "car"),
                pipeline.latestDetections().stream().map(Detection::label).toList());
    }

    @Test
    void labelFilterDropsNonMatchingDetectionsUniformlyAcrossEveryDownstreamConsumer() {
        VideoFrame f = frame(0);
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(f));
        DetectionResult result = resultWithLabels(0, "person", "car");
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(result));
        DetectionEventEngine eventEngine = mock(DetectionEventEngine.class);
        AssetId assetId = AssetId.random();
        DetectionLiveUpdatePort liveUpdatePublisherPort = mock(DetectionLiveUpdatePort.class);
        PipelineConfig config = new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 30, 2, Set.of("person"),
                EventRuleConfig.defaults(), true);

        StreamPipeline pipeline = new StreamPipeline(streamId, device, config, publisher, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher, collaborators(Optional.of(eventEngine),
                        Optional.of(assetId), Optional.of(liveUpdatePublisherPort), Optional.empty(),
                        System::nanoTime));
        pipeline.start();

        assertEquals(List.of("person"), pipeline.latestDetections().stream().map(Detection::label).toList());

        ArgumentCaptor<DetectionResult> savedCaptor = ArgumentCaptor.forClass(DetectionResult.class);
        verify(detectionRepositoryPort).save(savedCaptor.capture());
        assertEquals(List.of("person"), savedCaptor.getValue().detections().stream().map(Detection::label).toList());

        ArgumentCaptor<DetectionResult> engineCaptor = ArgumentCaptor.forClass(DetectionResult.class);
        verify(eventEngine).accept(engineCaptor.capture());
        assertEquals(List.of("person"), engineCaptor.getValue().detections().stream().map(Detection::label).toList());

        ArgumentCaptor<DetectionResult> liveCaptor = ArgumentCaptor.forClass(DetectionResult.class);
        verify(liveUpdatePublisherPort).publishDetections(eq(assetId), liveCaptor.capture());
        assertEquals(List.of("person"), liveCaptor.getValue().detections().stream().map(Detection::label).toList());
    }

    @Test
    void updateConfigWithADifferentModelIdClearsStaleModelBoundDetectionStateAndAppliesTheNewModelOnTheNextSample() {
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(0)));
        StreamPipeline pipeline = manualPipeline(config(1000, 5), () -> 0L);

        pipeline.onNext(frame(0));
        assertFalse(pipeline.latestDetections().isEmpty());

        // detectionEnabled stated explicitly (docs/plans/done/CV-DEMAND-PLAN.md §1 flipped the convenience-ctor
        // default) since this test's whole point is that the next sampled frame still detects, on
        // the new model.
        PipelineConfig newModel = new PipelineConfig(new ModelRef("orion12l", "latest"), 0.4, 1000, 5, Set.of(),
                EventRuleConfig.defaults(), true);
        pipeline.updateConfig(newModel);

        assertTrue(pipeline.latestDetections().isEmpty(),
                "a model-id change must clear stale detections bound to the old model");

        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(1)));
        pipeline.onNext(frame(1));

        ArgumentCaptor<PipelineConfig> configCaptor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(detectionPort, times(2)).detect(any(), configCaptor.capture());
        assertEquals("orion12l", configCaptor.getAllValues().get(1).model().id(),
                "the very next sampled frame must already carry the new model");
    }

    // --- docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.5, wave W1: latestObjects() read model + object-mirror
    // label filtering ---

    @Test
    void onDetectionResultWritesLatestObjectsAlongsideLatestDetections() {
        ObjectState object = objectState(1L, "person");
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(resultWithObjects(0, object)));
        StreamPipeline pipeline = manualPipeline(config(1000, 5), () -> 0L);

        assertTrue(pipeline.latestObjects().isEmpty(), "no inference has completed yet");
        pipeline.onNext(frame(0));

        assertEquals(List.of(object), pipeline.latestObjects());
    }

    @Test
    void aModelReArmClearsLatestObjectsJustAsItClearsLatestDetections() {
        ObjectState object = objectState(1L, "person");
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(resultWithObjects(0, object)));
        StreamPipeline pipeline = manualPipeline(config(1000, 5), () -> 0L);

        pipeline.onNext(frame(0));
        assertFalse(pipeline.latestObjects().isEmpty());

        // detectionEnabled stated explicitly (docs/plans/done/CV-DEMAND-PLAN.md §1 flipped the convenience-ctor
        // default), same reasoning as the sibling latestDetections test this one mirrors.
        PipelineConfig newModel = new PipelineConfig(new ModelRef("orion12l", "latest"), 0.4, 1000, 5, Set.of(),
                EventRuleConfig.defaults(), true);
        pipeline.updateConfig(newModel);

        assertTrue(pipeline.latestObjects().isEmpty(),
                "a model-id change must clear stale object-mirror state bound to the old model");
    }

    @Test
    void theLiveOnlyEdgeClearsLatestObjectsButDurableInferenceKeepsRunningForAnAlwaysPolicyStream() {
        ObjectState object = objectState(1L, "person");
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(resultWithObjects(0, object)));
        AssetId assetId = AssetId.random();
        DetectionLiveUpdatePort liveUpdatePublisherPort = mock(DetectionLiveUpdatePort.class);
        StreamPipeline pipeline = manualPipeline(config(1000, 5), () -> 0L, assetId, liveUpdatePublisherPort);
        pipeline.updateDetectionPolicy(true); // opted into ALWAYS

        pipeline.onNext(frame(0)); // demand still true (the default) -- a viewer is watching
        assertFalse(pipeline.latestObjects().isEmpty(),
                "object mirror must be established while watched, before the edge under test");

        pipeline.updateDetectionDemand(false); // the last viewer leaves; ALWAYS keeps inference open

        assertEquals(DetectionState.RUNNING_UNWATCHED, pipeline.detectionState(),
                "ALWAYS keeps inference running even though the live gate just closed");
        assertTrue(pipeline.latestObjects().isEmpty(),
                "clearLiveDerivedState must clear the object mirror on the live-only edge, mirroring latestDetections");
    }

    /**
     * The keep-when-{@code identity}-is-{@code null} rule (docs/plans/active/CV-ORCHESTRATION-PLAN.md
     * §4.5): the filter has nothing to match an unidentified object against, so dropping it would
     * invent an answer the platform does not have — only an object whose elected label the deny
     * filter actually names is dropped.
     */
    @Test
    void labelFilterKeepsAnObjectWithNoIdentityButDropsADeniedIdentifiedObject() {
        ObjectState denied = objectState(1L, "person");
        ObjectState unidentified = objectStateWithoutIdentity(2L);
        DetectionResult result = new DetectionResult(streamId, 0, Instant.now(), List.of(), Duration.ofMillis(5),
                null, null, List.of(denied, unidentified), Optional.empty());
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(result));
        PipelineConfig config = new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 1000, 5, Set.of(),
                EventRuleConfig.defaults(), true, TrackingConfig.off(), Set.of("person"), false);
        StreamPipeline pipeline = manualPipeline(config, () -> 0L);

        pipeline.onNext(frame(0));

        assertEquals(List.of(2L), pipeline.latestObjects().stream().map(ObjectState::id).toList(),
                "the denied 'person' identity is dropped; the unidentified object has nothing to match and is kept");
    }

    @Test
    void emptyLabelFiltersKeepEveryObjectRegardlessOfIdentity() {
        ObjectState identified = objectState(1L, "person");
        ObjectState unidentified = objectStateWithoutIdentity(2L);
        DetectionResult result = resultWithObjects(0, identified, unidentified);
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(result));
        StreamPipeline pipeline = manualPipeline(config(1000, 5), () -> 0L); // config(...)'s labelFilter is Set.of()

        pipeline.onNext(frame(0));

        assertEquals(List.of(1L, 2L), pipeline.latestObjects().stream().map(ObjectState::id).toList());
    }

    /**
     * Mirrors {@link #theLabelFilterDropsDetectionsButNeverThePerFrameTrackingTelemetry}: {@code
     * applyLabelFilters} only reconstructs a new {@link DetectionResult} when it actually drops
     * something, and that reconstruction must carry every non-list component forward -- {@link
     * FrameLedger} included -- exactly as {@code pullTelemetry} already does. A frame's warm debug
     * tier is a fact about the frame, not about which boxes an operator chose to see.
     */
    @Test
    void theLabelFilterCarriesTheLedgerThroughReconstructionInsteadOfDroppingIt() {
        Detection kept = new Detection("person", 0.9, new BoundingBox(0.1, 0.1, 0.2, 0.2), new ModelRef("yolo", "latest"));
        Detection dropped = new Detection("car", 0.9, new BoundingBox(0.3, 0.3, 0.2, 0.2), new ModelRef("yolo", "latest"));
        FrameLedger ledger = FrameLedgerFixtures.everyFieldDistinct();
        DetectionResult result = new DetectionResult(streamId, 0, Instant.now(), List.of(kept, dropped),
                Duration.ofMillis(5), null, null, List.of(), Optional.of(ledger));
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(result));
        PipelineConfig filtered = new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 1000, 5,
                Set.of("person"), EventRuleConfig.defaults(), true);
        StreamPipeline pipeline = manualPipeline(filtered, () -> 0L);

        pipeline.onNext(frame(0));

        ArgumentCaptor<DetectionResult> captor = ArgumentCaptor.forClass(DetectionResult.class);
        verify(detectionRepositoryPort).save(captor.capture());
        assertEquals(Optional.of(ledger), captor.getValue().ledger(),
                "the ledger is a per-frame fact, not a per-list one -- filtering detections/objects must not drop it");
    }

    // --- docs/plans/done/TRACKING-PLAN.md §5.D/§5.E, wave T3: track book, stats window, follow sampling ---

    // --- docs/plans/done/CV-RATE-CONTROL-PLAN.md wave R2: the adaptive rate, end to end -------------

    private static StreamPipelineSettings settingsWithAdaptiveRate(AdaptiveRateSettings adaptiveRate) {
        StreamPipelineSettings base = StreamPipelineSettings.defaults();
        return new StreamPipelineSettings(base.assumedSourceFps(), base.measuredFpsEwmaAlpha(),
                base.warmupFrames(), base.minMeasuredFps(), base.maxMeasuredFps(),
                base.detectionBackoffInitialNanos(), base.detectionBackoffMaxNanos(),
                base.sourceReopenBackoffInitialNanos(), base.sourceReopenBackoffMaxNanos(),
                base.extrapolationMaxMillis(), base.extrapolationMatchGate(),
                base.trackingStatsWindow(), base.trackRetention(), base.trackingSeed(),
                base.cameraHfovDegrees(), adaptiveRate);
    }

    /** A result whose single box is small and fast enough to demand far more than 10 fps. */
    private DetectionResult escapingTargetResult(long sequence) {
        Detection detection = new Detection("person", 0.9, new BoundingBox(0.4, 0.4, 0.05, 0.05),
                new ModelRef("yolo", "latest"),
                new TrackRef(1L, TrackState.CONFIRMED, DetectionSource.TRACKER, 2.0, 0.0, 10));
        return new DetectionResult(streamId, sequence, Instant.now(), List.of(detection), Duration.ofMillis(5), null, null, List.of(), Optional.empty());
    }

    private int samplesOverTwoSecondsOfA60FpsSource(AdaptiveRateSettings adaptiveRate) {
        List<VideoFrame> frames = new ArrayList<>();
        for (long i = 0; i < 120; i++) {
            frames.add(frame(i));
        }
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(frames);
        when(detectionPort.detect(any(), any()))
                .thenAnswer(invocation -> CompletableFuture.completedFuture(escapingTargetResult(0)));

        new StreamPipeline(streamId, device, trackingConfig(10, TrackingConfig.defaults()), publisher,
                detectionPort, streamPublisherPort, detectionRepositoryPort, eventPublisher,
                new StreamPipelineCollaborators(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        fixedFpsClock(60), settingsWithAdaptiveRate(adaptiveRate), System::nanoTime,
                        Optional.empty())).start();

        return mockingDetails(detectionPort).getInvocations().size();
    }

    @Test
    void raisesTheSampleRateWhenATrackedTargetIsAboutToLeaveItsAssociationBudget() {
        // The wiring test, not the arithmetic one -- DetectionRateControllerTest owns the formula.
        // A 0.05-wide box crossing at 2 frame widths/s demands ~74fps against its association
        // budget; the ceiling holds it at the configured 30. Two seconds of a 60fps source
        // therefore yields far more than the 20 samples inferenceFps=10 alone would allow.
        int samples = samplesOverTwoSecondsOfA60FpsSource(AdaptiveRateSettings.defaults());

        assertTrue(samples > 40, "expected the rate to be raised well above 10fps, sampled " + samples);
        assertTrue(samples <= 61, "and still capped at the configured 30fps ceiling, sampled " + samples);
    }

    @Test
    void leavesTheSampleRateAtTheConfiguredOneWhenTheAdaptiveLoopIsDisabled() {
        // Same target, same source: with the loop off the operator's 10fps is exactly what runs,
        // which is what makes the previous test evidence of the loop rather than of the clock.
        int samples = samplesOverTwoSecondsOfA60FpsSource(AdaptiveRateSettings.disabled());

        assertEquals(20, samples, 1, "two seconds at exactly the configured 10fps");
    }

    private static PipelineConfig trackingConfig(int inferenceFps, TrackingConfig tracking) {
        return new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, inferenceFps, 5, Set.of(),
                EventRuleConfig.defaults(), true, tracking);
    }

    private static TrackingConfig mode(TrackingMode trackingMode, int followFps) {
        return new TrackingConfig(trackingMode, "lk", 2000, followFps, 30, 30, 3, null);
    }

    private DetectionResult trackedResult(long sequence, long trackId, TrackState state) {
        Detection detection = new Detection("car", 0.9, new BoundingBox(0.3, 0.4, 0.1, 0.1),
                new ModelRef("yolo", "latest"), new TrackRef(trackId, state, DetectionSource.TRACKER));
        return new DetectionResult(streamId, sequence, Instant.parse("2026-08-11T10:00:00Z").plusMillis(sequence * 100),
                List.of(detection), Duration.ofMillis(5),
                new TrackingTelemetry(false, null, Duration.ofNanos(400_000), "lk", trackId), null, List.of(), Optional.empty());
    }

    /** @see #trackedResult(long, long, TrackState) — same shape, but the box comes from a detector pass. */
    private DetectionResult detectorSourcedResult(long sequence, long trackId, TrackState state) {
        Detection detection = new Detection("car", 0.9, new BoundingBox(0.3, 0.4, 0.1, 0.1),
                new ModelRef("yolo", "latest"), new TrackRef(trackId, state, DetectionSource.DETECTOR));
        return new DetectionResult(streamId, sequence, Instant.parse("2026-08-11T10:00:00Z").plusMillis(sequence * 100),
                List.of(detection), Duration.ofMillis(5),
                new TrackingTelemetry(true, com.drones.vision.perception.domain.model.DetectorReason.ALWAYS,
                        Duration.ofNanos(400_000), "lk", trackId), null, List.of(), Optional.empty());
    }

    /** A result carrying tracking telemetry but no detections — for exercising the unbound branch. */
    private DetectionResult trackingTelemetryOnlyResult(long sequence, long lockedTrackId) {
        return new DetectionResult(streamId, sequence, Instant.parse("2026-08-11T10:00:00Z").plusMillis(sequence * 100),
                List.of(), Duration.ofMillis(5),
                new TrackingTelemetry(false, com.drones.vision.perception.domain.model.DetectorReason.NO_LOCK,
                        Duration.ofNanos(400_000), "lk", lockedTrackId), null, List.of(), Optional.empty());
    }

    /** @see #mode(TrackingMode, int) — same shape, plus an explicit lock. */
    private static TrackingConfig withLock(TrackingMode trackingMode, int followFps, TargetLock lock) {
        return new TrackingConfig(trackingMode, "lk", 2000, followFps, 30, 30, 3, lock);
    }

    @Test
    void followRaisesTheEffectiveSampleRateWhileOffAndAssociateDoNot() {
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of());

        assertEquals(10, pipeline(publisher, trackingConfig(10, mode(TrackingMode.OFF, 15))).effectiveInferenceFps());
        assertEquals(10,
                pipeline(publisher, trackingConfig(10, mode(TrackingMode.ASSOCIATE, 15))).effectiveInferenceFps());
        assertEquals(15,
                pipeline(publisher, trackingConfig(10, mode(TrackingMode.FOLLOW, 15))).effectiveInferenceFps());
    }

    @Test
    void followNeverLowersTheSampleRateBelowTheConfiguredInferenceFps() {
        // max(inferenceFps, followFps), not "followFps wins": an operator who asked for 25fps
        // inference must not be quietly slowed to the 15fps follow default.
        StreamPipeline pipeline = pipeline(new ScriptedVideoPublisher(List.of()),
                trackingConfig(25, mode(TrackingMode.FOLLOW, 15)));

        assertEquals(25, pipeline.effectiveInferenceFps());
    }

    /** A nanotime source that advances exactly one 30fps frame interval per read. */
    private static LongSupplier thirtyFpsClock() {
        long[] nanos = {0L};
        return () -> nanos[0] += 33_333_333L;
    }

    private int sampledFramesOver30(TrackingMode trackingMode) {
        DetectionPort port = mock(DetectionPort.class);
        when(port.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(0)));
        StreamPipeline pipeline = new StreamPipeline(streamId, device, trackingConfig(10, mode(trackingMode, 15)),
                NO_OP_SOURCE, port, streamPublisherPort, detectionRepositoryPort, eventPublisher,
                collaborators(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        thirtyFpsClock()));
        pipeline.onSubscribe(NOOP_SUBSCRIPTION);
        for (int i = 0; i < 30; i++) {
            pipeline.onNext(frame(i));
        }
        return mockingDetails(port).getInvocations().size();
    }

    @Test
    void aRaisedEffectiveRateActuallySamplesMoreFramesOfTheSameSource() {
        // 30 frames off a measured-30fps source: inferenceFps 10 samples every 3rd frame, and
        // FOLLOW at 15fps samples every 2nd. Same source, same inferenceFps, only the mode differs.
        assertEquals(10, sampledFramesOver30(TrackingMode.ASSOCIATE));
        assertEquals(15, sampledFramesOver30(TrackingMode.FOLLOW));
    }

    @Test
    void aResultCarryingATrackIdIsBookedAndVisibleThroughTracks() {
        when(detectionPort.detect(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(trackedResult(0, 7, TrackState.CONFIRMED)));
        StreamPipeline pipeline = manualPipeline(trackingConfig(1000, mode(TrackingMode.ASSOCIATE, 15)), () -> 0L);

        pipeline.onNext(frame(0));

        assertEquals(1, pipeline.tracks().size());
        assertEquals(7L, pipeline.tracks().getFirst().trackId());
    }

    @Test
    void resultsAlsoFeedTheStatsWindow() {
        when(detectionPort.detect(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(trackedResult(0, 7, TrackState.CONFIRMED)));
        StreamPipeline pipeline = manualPipeline(trackingConfig(1000, mode(TrackingMode.FOLLOW, 15)), () -> 0L);

        pipeline.onNext(frame(0));

        TrackingStats stats = pipeline.trackingStats();
        assertEquals(TrackingMode.FOLLOW, stats.mode(), "the snapshot is stamped with the live configured mode");
        assertEquals(1L, stats.trackerFrames());
        assertEquals(0L, stats.detectorPasses());
        assertEquals(7L, stats.lockedTrackId());
    }

    @Test
    void anUntrackedStreamKeepsAnEmptyBookAndAnEmptyStatsWindow() {
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(0)));
        StreamPipeline pipeline = manualPipeline(config(1000, 5), () -> 0L);

        pipeline.onNext(frame(0));

        assertTrue(pipeline.tracks().isEmpty());
        assertEquals(0L, pipeline.trackingStats().trackerFrames());
        assertEquals(TrackingMode.OFF, pipeline.trackingStats().mode());
    }

    @Test
    void aModelReArmClearsTheTrackBookAndTheStatsWindowJustAsItClearsTheExtrapolator() {
        when(detectionPort.detect(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(trackedResult(0, 7, TrackState.CONFIRMED)));
        StreamPipeline pipeline = manualPipeline(trackingConfig(1000, mode(TrackingMode.ASSOCIATE, 15)), () -> 0L);
        pipeline.onNext(frame(0));
        assertFalse(pipeline.tracks().isEmpty());

        pipeline.updateConfig(new PipelineConfig(new ModelRef("orion12l", "latest"), 0.4, 1000, 5, Set.of(),
                EventRuleConfig.defaults(), true, mode(TrackingMode.ASSOCIATE, 15)));

        assertTrue(pipeline.tracks().isEmpty(), "track ids are bound to the model that produced them");
        assertEquals(0L, pipeline.trackingStats().trackerFrames());
    }

    @Test
    void aTrackingOnlyConfigChangeClearsNothingBecauseTrackingIsAHotKnob() {
        when(detectionPort.detect(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(trackedResult(0, 7, TrackState.CONFIRMED)));
        StreamPipeline pipeline = manualPipeline(trackingConfig(1000, mode(TrackingMode.ASSOCIATE, 15)), () -> 0L);
        pipeline.onNext(frame(0));

        pipeline.updateConfig(trackingConfig(1000, mode(TrackingMode.FOLLOW, 15)));

        assertEquals(1, pipeline.tracks().size(), "a mode change must never behave like a model re-arm");
        assertEquals(1L, pipeline.trackingStats().trackerFrames());
        assertEquals(TrackingMode.FOLLOW, pipeline.trackingStats().mode());
    }

    @Test
    void theLabelFilterDropsDetectionsButNeverThePerFrameTrackingTelemetry() {
        // Filtering drops boxes; whether the detector ran, and what the tracker cost, are facts
        // about the frame that survive the filter.
        when(detectionPort.detect(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(trackedResult(0, 7, TrackState.CONFIRMED)));
        PipelineConfig filtered = new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 1000, 5,
                Set.of("person"), EventRuleConfig.defaults(), true, mode(TrackingMode.FOLLOW, 15));
        StreamPipeline pipeline = manualPipeline(filtered, () -> 0L);

        pipeline.onNext(frame(0));

        assertTrue(pipeline.latestDetections().isEmpty(), "the 'car' detection is filtered out");
        assertTrue(pipeline.tracks().isEmpty(), "and therefore never booked");
        assertEquals(1L, pipeline.trackingStats().trackerFrames(), "but the frame still counted");
        assertEquals(7L, pipeline.trackingStats().lockedTrackId());
    }

    @Test
    void followStatusReadsEmptyBeforeAnyLockIsIssued() {
        StreamPipeline pipeline = manualPipeline(trackingConfig(1000, mode(TrackingMode.FOLLOW, 15)), () -> 0L);

        assertTrue(pipeline.followStatus().isEmpty());
    }

    @Test
    void updateConfigWithAFreshLockArmsFollowStatusAsRequesting() {
        when(detectionPort.detect(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(trackingTelemetryOnlyResult(0, 0L)));
        StreamPipeline pipeline = manualPipeline(trackingConfig(1000, mode(TrackingMode.FOLLOW, 15)), () -> 0L);

        pipeline.updateConfig(trackingConfig(1000,
                withLock(TrackingMode.FOLLOW, 15, new TargetLock(1, 7L, null, null, false))));
        pipeline.onNext(frame(0));

        assertEquals(FollowState.REQUESTING, pipeline.followStatus().orElseThrow().state());
    }

    @Test
    void aDetectorSourcedBoundResultTransitionsFollowStatusToHolding() {
        when(detectionPort.detect(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(detectorSourcedResult(0, 7, TrackState.CONFIRMED)));
        StreamPipeline pipeline = manualPipeline(trackingConfig(1000,
                withLock(TrackingMode.FOLLOW, 15, new TargetLock(1, 7L, null, null, false))), () -> 0L);

        pipeline.onNext(frame(0));

        assertEquals(FollowState.HOLDING, pipeline.followStatus().orElseThrow().state());
        assertEquals(7L, pipeline.followStatus().orElseThrow().trackId());
        assertEquals("car", pipeline.followStatus().orElseThrow().label());
    }

    @Test
    void aReleasePatchDropsFollowStatusToEmpty() {
        when(detectionPort.detect(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(detectorSourcedResult(0, 7, TrackState.CONFIRMED)));
        StreamPipeline pipeline = manualPipeline(trackingConfig(1000,
                withLock(TrackingMode.FOLLOW, 15, new TargetLock(1, 7L, null, null, false))), () -> 0L);
        pipeline.onNext(frame(0));
        assertEquals(FollowState.HOLDING, pipeline.followStatus().orElseThrow().state());

        pipeline.updateConfig(trackingConfig(1000,
                withLock(TrackingMode.FOLLOW, 15, new TargetLock(2, null, null, null, true))));

        assertTrue(pipeline.followStatus().isEmpty());
    }

    @Test
    void aModelReArmClearsFollowStatusJustAsItClearsTheTrackBook() {
        when(detectionPort.detect(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(detectorSourcedResult(0, 7, TrackState.CONFIRMED)));
        StreamPipeline pipeline = manualPipeline(trackingConfig(1000,
                withLock(TrackingMode.FOLLOW, 15, new TargetLock(1, 7L, null, null, false))), () -> 0L);
        pipeline.onNext(frame(0));
        assertEquals(FollowState.HOLDING, pipeline.followStatus().orElseThrow().state());

        pipeline.updateConfig(new PipelineConfig(new ModelRef("orion12l", "latest"), 0.4, 1000, 5, Set.of(),
                EventRuleConfig.defaults(), true, mode(TrackingMode.FOLLOW, 15)));

        assertTrue(pipeline.followStatus().isEmpty(), "a held lock's trackId is bound to the model that produced it");
    }

    @Test
    void aTrackingOnlyConfigChangeThatDoesNotTouchTheLockLeavesFollowStatusAlone() {
        when(detectionPort.detect(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(detectorSourcedResult(0, 7, TrackState.CONFIRMED)));
        StreamPipeline pipeline = manualPipeline(trackingConfig(1000,
                withLock(TrackingMode.FOLLOW, 15, new TargetLock(1, 7L, null, null, false))), () -> 0L);
        pipeline.onNext(frame(0));
        FollowState before = pipeline.followStatus().orElseThrow().state();

        // Same lock, only followFps changes -- TrackingConfigPatch.foldOnto keeps the lock component
        // (and its lockSeq) untouched when the patch itself carries no lock.
        pipeline.updateConfig(trackingConfig(1000,
                withLock(TrackingMode.FOLLOW, 20, new TargetLock(1, 7L, null, null, false))));

        assertEquals(before, pipeline.followStatus().orElseThrow().state());
    }

    @Test
    void aLockPresentAtConstructionIsHonoredWithoutAnUpdateConfigCall() {
        // DefaultStreamService.start() can fold a requested lock into the very first PipelineConfig
        // handed to this constructor -- updateConfig's own lock-change detection never runs for it,
        // so the constructor itself must seed FollowTracker.
        when(detectionPort.detect(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(trackingTelemetryOnlyResult(0, 0L)));
        StreamPipeline pipeline = manualPipeline(trackingConfig(1000,
                withLock(TrackingMode.FOLLOW, 15, new TargetLock(1, 7L, null, null, false))), () -> 0L);

        pipeline.onNext(frame(0));

        assertEquals(FollowState.REQUESTING, pipeline.followStatus().orElseThrow().state());
    }

    /**
     * Deterministic test double for {@code VideoSourcePort}'s {@code
     * Flow.Publisher}: delivers frames synchronously, one per {@code
     * request()} call, and records every requested amount so tests can
     * assert on request(1)-at-a-time behavior. After all scripted frames are
     * exhausted it stays silent (like a live source with no new data yet)
     * unless {@link #errorAfterFrames} was configured.
     */
    private static final class ScriptedVideoPublisher implements Flow.Publisher<VideoFrame> {
        private final List<VideoFrame> frames;
        private final List<Long> requestAmounts = Collections.synchronizedList(new ArrayList<>());
        private int index = 0;
        private boolean signaled = false;
        private Throwable errorAfterFrames;
        volatile boolean cancelled = false;

        ScriptedVideoPublisher(List<VideoFrame> frames) {
            this.frames = frames;
        }

        void errorAfterFrames(Throwable throwable) {
            this.errorAfterFrames = throwable;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super VideoFrame> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    requestAmounts.add(n);
                    if (index < frames.size()) {
                        VideoFrame next = frames.get(index++);
                        subscriber.onNext(next);
                    } else if (!signaled && errorAfterFrames != null) {
                        signaled = true;
                        subscriber.onError(errorAfterFrames);
                    }
                }

                @Override
                public void cancel() {
                    cancelled = true;
                }
            });
        }
    }
}
