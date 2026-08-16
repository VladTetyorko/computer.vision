package com.drones.vision.perception.application.pipeline;

import com.drones.vision.perception.domain.model.AnnotatedFrame;
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
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.perception.domain.model.PixelFormat;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;
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
import com.drones.vision.perception.domain.port.OverlayPort;
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

    // docs/plans/active/CV-DEMAND-PLAN.md §1 flipped PipelineConfig.DEFAULT_DETECTION_ENABLED to false; every
    // test in this file exercises the detection machinery itself, so both helpers state
    // detectionEnabled=true explicitly rather than relying on a default this suite never meant to
    // depend on.
    private static PipelineConfig config(int inferenceFps, int maxInFlight) {
        return new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, inferenceFps, maxInFlight, true, Set.of(),
                EventRuleConfig.defaults(), PipelineConfig.DEFAULT_OVERLAY_BURN_IN, true);
    }

    private static PipelineConfig config(int inferenceFps, int maxInFlight, boolean overlayBurnIn) {
        return new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, inferenceFps, maxInFlight, true, Set.of(),
                EventRuleConfig.defaults(), overlayBurnIn, true);
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
        return new DetectionResult(streamId, sequence, Instant.now(), List.of(), Duration.ZERO);
    }

    private DetectionResult resultWithBoxX(long sequence, Instant capturedAt, double boxX) {
        Detection detection = new Detection("person", 0.9, new BoundingBox(boxX, 0.10, 0.20, 0.20),
                new ModelRef("yolo", "latest"));
        return new DetectionResult(streamId, sequence, capturedAt, List.of(detection), Duration.ofMillis(5));
    }

    private DetectionResult nonEmptyResult(long sequence) {
        Detection detection = new Detection("person", 0.9, new BoundingBox(0.1, 0.1, 0.2, 0.2),
                new ModelRef("yolo", "latest"));
        return new DetectionResult(streamId, sequence, Instant.now(), List.of(detection), Duration.ofMillis(5));
    }

    private static CompletableFuture<DetectionResult> failedFuture(String message) {
        CompletableFuture<DetectionResult> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException(message));
        return future;
    }

    private StreamPipeline pipeline(ScriptedVideoPublisher publisher, PipelineConfig config) {
        return new StreamPipeline(streamId, device, config, publisher, detectionPort, streamPublisherPort,
                detectionRepositoryPort, eventPublisher);
    }

    private StreamPipeline pipeline(ScriptedVideoPublisher publisher, PipelineConfig config, OverlayPort overlayPort) {
        return new StreamPipeline(streamId, device, config, publisher, detectionPort, streamPublisherPort,
                detectionRepositoryPort, eventPublisher, overlayPort);
    }

    private StreamPipeline pipeline(ScriptedVideoPublisher publisher, PipelineConfig config,
                                     DetectionEventEngine eventEngine) {
        return new StreamPipeline(streamId, device, config, publisher, detectionPort, streamPublisherPort,
                detectionRepositoryPort, eventPublisher, null, eventEngine);
    }

    private StreamPipeline pipeline(ScriptedVideoPublisher publisher, PipelineConfig config, AssetId assetId,
                                     DetectionLiveUpdatePort liveUpdatePublisherPort) {
        return new StreamPipeline(streamId, device, config, publisher, detectionPort, streamPublisherPort,
                detectionRepositoryPort, eventPublisher, null, null, assetId, liveUpdatePublisherPort);
    }

    /**
     * A pipeline whose cadence clock advances one {@code sourceFps} interval per frame — the seam
     * every "sample every frame" test needs now that sampling is deadline-based rather than a frame
     * stride. Pair it with a {@code config} whose {@code inferenceFps} is at least {@code sourceFps}
     * and every delivered frame serves a deadline; the default {@code System::nanoTime} cannot,
     * because a synchronous publisher delivers its whole script inside one sample interval.
     */
    private StreamPipeline pipeline(ScriptedVideoPublisher publisher, PipelineConfig config,
                                     OverlayPort overlayPort, double sourceFps) {
        return new StreamPipeline(streamId, device, config, publisher, detectionPort, streamPublisherPort,
                detectionRepositoryPort, eventPublisher, overlayPort, null, null, null, null,
                fixedFpsClock(sourceFps));
    }

    /** @see #pipeline(ScriptedVideoPublisher, PipelineConfig, OverlayPort, double) */
    private StreamPipeline pipeline(ScriptedVideoPublisher publisher, PipelineConfig config,
                                     DetectionEventEngine eventEngine, double sourceFps) {
        return new StreamPipeline(streamId, device, config, publisher, detectionPort, streamPublisherPort,
                detectionRepositoryPort, eventPublisher, null, eventEngine, null, null, null,
                fixedFpsClock(sourceFps));
    }

    /** @see #pipeline(ScriptedVideoPublisher, PipelineConfig, OverlayPort, double) */
    private StreamPipeline pipeline(ScriptedVideoPublisher publisher, PipelineConfig config, AssetId assetId,
                                     DetectionLiveUpdatePort liveUpdatePublisherPort, double sourceFps) {
        return new StreamPipeline(streamId, device, config, publisher, detectionPort, streamPublisherPort,
                detectionRepositoryPort, eventPublisher, null, null, assetId, liveUpdatePublisherPort, null,
                fixedFpsClock(sourceFps));
    }

    private StreamPipeline pipeline(ScriptedVideoPublisher publisher, PipelineConfig config, LongSupplier clock) {
        return new StreamPipeline(streamId, device, config, publisher, detectionPort, streamPublisherPort,
                detectionRepositoryPort, eventPublisher, null, null, null, null, null, clock);
    }

    private StreamPipeline pipeline(ScriptedVideoPublisher publisher, PipelineConfig config, OverlayPort overlayPort,
                                     Supplier<Telemetry> telemetrySupplier) {
        return new StreamPipeline(streamId, device, config, publisher, detectionPort, streamPublisherPort,
                detectionRepositoryPort, eventPublisher, overlayPort, null, null, null, telemetrySupplier);
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
                streamPublisherPort, detectionRepositoryPort, eventPublisher, null, null, null, null, null, clock);
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
                detectionRepositoryPort, eventPublisher, null, null, null, null, null,
                fixedFpsClock(30), StreamPipelineSettings.defaults(), latencyClock);
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
                detectionRepositoryPort, eventPublisher, null, null, null, null, telemetrySupplier,
                fixedFpsClock(30), settings, System::nanoTime);
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
    void turningTheTelemetryOsdOffDoesNotDisableEgoMotionCompensation() {
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(frame(0)));
        when(detectionPort.detect(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(0)));
        PipelineConfig base = config(60, 5);
        PipelineConfig osdOff = new PipelineConfig(base.model(), base.confidenceThreshold(),
                base.inferenceFps(), base.maxInFlightInferences(), false, base.labelFilter(),
                base.eventRule(), base.overlayBurnIn(), base.detectionEnabled(), base.tracking());

        attitudePipeline(publisher, osdOff, () -> telemetryWithHeading(137.5), settingsWithHfov(62.0)).start();

        // overlayTelemetry is a PRESENTATION switch; compensation is a perception concern. They
        // happen to read the same supplier and must not be coupled through it.
        verify(detectionPort).detect(any(), any(), any());
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
    void latestRawFrameStaysTheRawFrameEvenWhenOverlayBurnInPublishesADifferentRenderedFrame() {
        // docs/plans/done/CV-TRAINING-PLAN.md §2/§D: latestRawFrame() must expose the pre-overlay frame, never
        // the (possibly burned-in) instance latestFrame()/streamPublisherPort see -- this is the one
        // thing that actually distinguishes the two seams, so it is the load-bearing assertion here.
        VideoFrame f0 = frame(0);
        VideoFrame f1 = frame(1);
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(f0, f1));
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(0)));
        OverlayPort overlayPort = mock(OverlayPort.class);
        VideoFrame rendered = frame(99);
        when(overlayPort.render(any())).thenReturn(rendered);

        StreamPipeline pipeline = pipeline(publisher, config(30, 2), overlayPort);
        pipeline.start();

        assertEquals(Optional.of(rendered), pipeline.latestFrame(), "latestFrame() sees the rendered instance");
        assertEquals(Optional.of(f1), pipeline.latestRawFrame(), "latestRawFrame() must stay the clean source frame");
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
        // THE regression this sampler exists for (docs/plans/active/CV-RATE-CONTROL-PLAN.md §1, loss L1).
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
        StreamPipeline pipeline = pipeline(publisher, config(30, 1), (OverlayPort) null, 30.0);
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

    @Test
    void rawFramePublishedWhenNoOverlayPortConfiguredEvenWithNonEmptyDetections() {
        // Baseline/regression: the 8-argument (no-overlay) constructor must behave exactly as it
        // did before this feature -- overlayPort defaults to null, so a completed non-empty
        // detection result never changes what gets published.
        VideoFrame f0 = frame(0);
        VideoFrame f1 = frame(1);
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(f0, f1));
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(0)));

        pipeline(publisher, config(30, 2)).start();

        verify(streamPublisherPort).publish(streamId, f0);
        verify(streamPublisherPort).publish(streamId, f1);
    }

    @Test
    void rawFramePublishedWhenOverlayConfiguredButNoDetectionHasCompletedYet() {
        VideoFrame f = frame(0);
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(f));
        when(detectionPort.detect(any(), any())).thenReturn(new CompletableFuture<>()); // never completes
        OverlayPort overlayPort = mock(OverlayPort.class);

        pipeline(publisher, config(30, 2), overlayPort).start();

        verify(streamPublisherPort).publish(streamId, f);
        verifyNoInteractions(overlayPort);
    }

    @Test
    void overlayRendersOntoFrameOnceLatestDetectionsAreNonEmptyAndPublisherReceivesRendererOutput() {
        VideoFrame f0 = frame(0);
        VideoFrame f1 = frame(1);
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(f0, f1));
        DetectionResult result = nonEmptyResult(0);
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(result));
        OverlayPort overlayPort = mock(OverlayPort.class);
        VideoFrame rendered = frame(99); // a distinct instance standing in for the renderer's output
        when(overlayPort.render(any())).thenReturn(rendered);

        pipeline(publisher, config(30, 2), overlayPort).start();

        // f0: published raw -- no detection has completed yet when it is published.
        verify(streamPublisherPort).publish(streamId, f0);
        // f1: latestDetections is non-empty by now (f0's detection completed synchronously), so
        // the publisher receives the renderer's output instance, not the raw frame.
        verify(streamPublisherPort).publish(streamId, rendered);
        verify(streamPublisherPort, never()).publish(streamId, f1);

        ArgumentCaptor<AnnotatedFrame> captor = ArgumentCaptor.forClass(AnnotatedFrame.class);
        verify(overlayPort).render(captor.capture());
        assertEquals(f1, captor.getValue().frame());
        assertEquals(result.detections(), captor.getValue().detections());
        assertNull(captor.getValue().telemetry(), "no telemetrySupplier was configured on this pipeline");
    }

    // --- Telemetry-OSD input (closes adapter-overlay/MODULE.md's "OSD gate not reachable" gap) ---

    private Telemetry telemetrySample() {
        return new Telemetry(device.id(), Instant.now(), 50.45, 30.52, 100.0, 90.0, 77.0, Map.of());
    }

    @Test
    void overlayReceivesTheSuppliedTelemetrySampleWhenOverlayTelemetryIsEnabledAndASupplierIsConfigured() {
        VideoFrame f = frame(0);
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(f));
        when(detectionPort.detect(any(), any())).thenReturn(new CompletableFuture<>()); // never completes
        OverlayPort overlayPort = mock(OverlayPort.class);
        VideoFrame rendered = frame(99);
        when(overlayPort.render(any())).thenReturn(rendered);
        Telemetry sample = telemetrySample();
        Supplier<Telemetry> telemetrySupplier = () -> sample;

        // config(30, 2) defaults overlayTelemetry=true; no detections have completed, so the
        // telemetry sample alone is what makes overlayIfNeeded render at all -- proving the "either
        // detections or telemetry" condition, not just "both present".
        pipeline(publisher, config(30, 2), overlayPort, telemetrySupplier).start();

        ArgumentCaptor<AnnotatedFrame> captor = ArgumentCaptor.forClass(AnnotatedFrame.class);
        verify(overlayPort).render(captor.capture());
        assertEquals(sample, captor.getValue().telemetry());
        assertTrue(captor.getValue().detections().isEmpty());
        verify(streamPublisherPort).publish(streamId, rendered);
    }

    @Test
    void overlayTelemetryStaysNullWhenOverlayTelemetryFlagIsDisabledEvenWithASupplierConfigured() {
        VideoFrame f0 = frame(0);
        VideoFrame f1 = frame(1);
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(f0, f1));
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(0)));
        OverlayPort overlayPort = mock(OverlayPort.class);
        when(overlayPort.render(any())).thenReturn(frame(99));
        Supplier<Telemetry> telemetrySupplier = this::telemetrySample;

        // overlayTelemetry=false, detectionEnabled=true explicit (docs/plans/active/CV-DEMAND-PLAN.md §1 flipped
        // the convenience-ctor default) -- detections still drive rendering (non-empty), but the OSD
        // gate itself must stay shut.
        PipelineConfig config = new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 30, 2, false, Set.of(),
                EventRuleConfig.defaults(), PipelineConfig.DEFAULT_OVERLAY_BURN_IN, true);
        pipeline(publisher, config, overlayPort, telemetrySupplier).start();

        ArgumentCaptor<AnnotatedFrame> captor = ArgumentCaptor.forClass(AnnotatedFrame.class);
        verify(overlayPort, atLeastOnce()).render(captor.capture());
        assertTrue(captor.getAllValues().stream().allMatch(a -> a.telemetry() == null),
                "overlayTelemetry=false must keep every AnnotatedFrame's telemetry null regardless of the supplier");
    }

    @Test
    void telemetrySupplierThrowingIsSwallowedAndOverlayStillRendersWithNullTelemetry() {
        VideoFrame f0 = frame(0);
        VideoFrame f1 = frame(1);
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(f0, f1));
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(0)));
        OverlayPort overlayPort = mock(OverlayPort.class);
        when(overlayPort.render(any())).thenReturn(frame(99));
        Supplier<Telemetry> throwingSupplier = () -> {
            throw new RuntimeException("telemetry backend unavailable");
        };

        assertDoesNotThrow(
                () -> pipeline(publisher, config(30, 2), overlayPort, throwingSupplier).start(),
                "a throwing telemetrySupplier must never break the frame path");

        ArgumentCaptor<AnnotatedFrame> captor = ArgumentCaptor.forClass(AnnotatedFrame.class);
        verify(overlayPort, atLeastOnce()).render(captor.capture());
        assertTrue(captor.getAllValues().stream().allMatch(a -> a.telemetry() == null),
                "a throwing supplier must be treated exactly like 'no sample available'");
        verify(streamPublisherPort, never()).streamEnded(streamId);
    }

    @Test
    void overlayNeverInvokedWhenNoDetectionsAndNoTelemetrySampleAreAvailable() {
        VideoFrame f = frame(0);
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(f));
        when(detectionPort.detect(any(), any())).thenReturn(new CompletableFuture<>()); // never completes
        OverlayPort overlayPort = mock(OverlayPort.class);
        Supplier<Telemetry> emptySupplier = () -> null;

        pipeline(publisher, config(30, 2), overlayPort, emptySupplier).start();

        verify(streamPublisherPort).publish(streamId, f);
        verifyNoInteractions(overlayPort);
    }

    @Test
    void latestFrameReflectsTheRenderedFrameWhenOverlayBurnInProducesOne() {
        // docs/plans/done/MVP3-PLAN.md C-a: latestFrame() must expose the exact instance streamPublisherPort
        // was handed, so a snapshot request sees the same post-overlay picture a viewer does.
        VideoFrame f0 = frame(0);
        VideoFrame f1 = frame(1);
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(f0, f1));
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(0)));
        OverlayPort overlayPort = mock(OverlayPort.class);
        VideoFrame rendered = frame(99);
        when(overlayPort.render(any())).thenReturn(rendered);

        StreamPipeline pipeline = pipeline(publisher, config(30, 2), overlayPort);
        pipeline.start();

        assertEquals(Optional.of(rendered), pipeline.latestFrame());
    }

    @Test
    void overlayNeverInvokedAndFramesPublishRawWhenOverlayBurnInIsDisabled() {
        // docs/plans/done/MVP2-PLAN.md §V, V-e: an OverlayPort is configured and detections are non-empty --
        // exactly the condition overlayRendersOntoFrame...() above proves triggers rendering -- but
        // PipelineConfig#overlayBurnIn() is false, so the renderer must never even be called and
        // every frame publishes as the raw, unmodified instance the source produced.
        VideoFrame f0 = frame(0);
        VideoFrame f1 = frame(1);
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(f0, f1));
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(0)));
        OverlayPort overlayPort = mock(OverlayPort.class);

        pipeline(publisher, config(30, 2, false), overlayPort).start();

        verify(streamPublisherPort).publish(streamId, f0);
        verify(streamPublisherPort).publish(streamId, f1);
        verifyNoInteractions(overlayPort);
    }

    @Test
    void rendererThrowIsSwallowedAndRawFrameKeepsPublishingWithoutClosingThePipeline() {
        VideoFrame f0 = frame(0);
        VideoFrame f1 = frame(1);
        VideoFrame f2 = frame(2);
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(f0, f1, f2));
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(0)));
        OverlayPort overlayPort = mock(OverlayPort.class);
        when(overlayPort.render(any())).thenThrow(new RuntimeException("boom"));

        pipeline(publisher, config(30, 2), overlayPort).start();

        // f1 and f2 both attempt overlay (latestDetections is non-empty by then) and both throw,
        // yet every frame is still published raw and the pipeline never closes -- overlay
        // rendering is cosmetic and must never disrupt the video path.
        verify(streamPublisherPort).publish(streamId, f0);
        verify(streamPublisherPort).publish(streamId, f1);
        verify(streamPublisherPort).publish(streamId, f2);
        verify(streamPublisherPort, never()).streamEnded(streamId);
        // Rendering is retried on every frame (unlike detection's outage/backoff skip policy) --
        // only the WARNING log is throttled to once per failure run via an internal latch, not
        // observed directly here since this suite doesn't assert on System.Logger output anywhere.
        verify(overlayPort, times(2)).render(any());
    }

    @Test
    void overlayReceivesRawSingleResultDetectionsUnchangedWhenOnlyOneResultHasCompleted() {
        // docs/main/CYCLES-PLAN.md §12 CP-c: with only one completed result (no "previous" yet), the
        // DetectionExtrapolator passes it through as-is -- overlay behavior is unchanged from
        // before the extrapolator existed.
        VideoFrame f0 = frame(0);
        VideoFrame f1 = frame(1);
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(f0, f1));
        DetectionResult result = nonEmptyResult(0);
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(result));
        OverlayPort overlayPort = mock(OverlayPort.class);
        VideoFrame rendered = frame(99);
        when(overlayPort.render(any())).thenReturn(rendered);

        pipeline(publisher, config(1000, 5), overlayPort, 1000.0).start();

        ArgumentCaptor<AnnotatedFrame> captor = ArgumentCaptor.forClass(AnnotatedFrame.class);
        verify(overlayPort).render(captor.capture());
        assertEquals(result.detections(), captor.getValue().detections());
    }

    @Test
    void overlayExtrapolatesTheMatchedBoxBetweenTwoCompletedResultsInsteadOfFreezingAtTheLatestRawPosition() {
        // docs/main/CYCLES-PLAN.md §12 CP-c: once a second result completes, the overlay for a
        // subsequently-published frame shows a box moved along the measured velocity toward that
        // frame's own capture time, not L's raw (now-stale) box position.
        Instant t0 = Instant.parse("2024-01-01T00:00:00Z");
        VideoFrame f0 = frameAt(0, t0);
        VideoFrame f1 = frameAt(1, t0.plusMillis(100));
        VideoFrame f2 = frameAt(2, t0.plusMillis(150));
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(f0, f1, f2));
        DetectionResult previousResult = resultWithBoxX(0, t0, 0.10); // box center x = 0.20
        DetectionResult latestResult = resultWithBoxX(1, t0.plusMillis(100), 0.14); // box center x = 0.24
        when(detectionPort.detect(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(previousResult))
                .thenReturn(CompletableFuture.completedFuture(latestResult))
                .thenReturn(CompletableFuture.completedFuture(latestResult)); // f2's own detect(): irrelevant here
        OverlayPort overlayPort = mock(OverlayPort.class);
        VideoFrame rendered = frame(99);
        when(overlayPort.render(any())).thenReturn(rendered);

        pipeline(publisher, config(1000, 5), overlayPort, 1000.0).start();

        ArgumentCaptor<AnnotatedFrame> captor = ArgumentCaptor.forClass(AnnotatedFrame.class);
        verify(overlayPort, atLeastOnce()).render(captor.capture());
        AnnotatedFrame forF2 = captor.getAllValues().stream()
                .filter(annotated -> annotated.frame() == f2)
                .findFirst()
                .orElseThrow(() -> new AssertionError("overlay was never rendered for f2"));

        // velocity = (0.24 - 0.20) / 0.1s = 0.4 units/s; 50ms past L -> center x + 0.02 -> box x = 0.16,
        // strictly between L's raw box x (0.14) and a naive full-step continuation.
        assertEquals(1, forF2.detections().size());
        assertEquals(0.16, forF2.detections().get(0).box().x(), 1e-9);
    }

    @Test
    void overlayFreezesExtrapolationAtTheCapForAFrameFarPastTheLatestResult() {
        // docs/main/CYCLES-PLAN.md §12 CP-c: a frame published long after L (e.g. a stalled/outaged
        // detector) must not run the box off screen -- extrapolation freezes at
        // DetectionExtrapolator.MAX_EXTRAPOLATION_MILLIS past L's capture time.
        Instant t0 = Instant.parse("2024-01-01T00:00:00Z");
        VideoFrame f0 = frameAt(0, t0);
        VideoFrame f1 = frameAt(1, t0.plusMillis(100));
        VideoFrame f2 = frameAt(2, t0.plusSeconds(30)); // far beyond L.capturedAt + 800ms
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(f0, f1, f2));
        DetectionResult previousResult = resultWithBoxX(0, t0, 0.10); // box center x = 0.20
        DetectionResult latestResult = resultWithBoxX(1, t0.plusMillis(100), 0.14); // box center x = 0.24
        when(detectionPort.detect(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(previousResult))
                .thenReturn(CompletableFuture.completedFuture(latestResult))
                .thenReturn(CompletableFuture.completedFuture(latestResult));
        OverlayPort overlayPort = mock(OverlayPort.class);
        VideoFrame rendered = frame(99);
        when(overlayPort.render(any())).thenReturn(rendered);

        pipeline(publisher, config(1000, 5), overlayPort, 1000.0).start();

        ArgumentCaptor<AnnotatedFrame> captor = ArgumentCaptor.forClass(AnnotatedFrame.class);
        verify(overlayPort, atLeastOnce()).render(captor.capture());
        AnnotatedFrame forF2 = captor.getAllValues().stream()
                .filter(annotated -> annotated.frame() == f2)
                .findFirst()
                .orElseThrow(() -> new AssertionError("overlay was never rendered for f2"));

        // velocity 0.4 units/s, capped at 800ms past L -> center x + 0.32 -> box x = 0.46.
        assertEquals(1, forF2.detections().size());
        assertEquals(0.46, forF2.detections().get(0).box().x(), 1e-9);
    }

    // --- docs/plans/done/CV-CONTROL-PLAN.md Wave C: live config update, skip-detect, label-filter enforcement ---

    private DetectionResult resultWithLabels(long sequence, String... labels) {
        List<Detection> detections = new ArrayList<>();
        for (String label : labels) {
            detections.add(new Detection(label, 0.9, new BoundingBox(0.1, 0.1, 0.2, 0.2),
                    new ModelRef("yolo", "latest")));
        }
        return new DetectionResult(streamId, sequence, Instant.now(), detections, Duration.ofMillis(5));
    }

    @Test
    void configReturnsTheCurrentlyActivePipelineConfigAndReflectsAnUpdate() {
        StreamPipeline pipeline = pipeline(new ScriptedVideoPublisher(List.of()), config(30, 2));

        assertEquals(30, pipeline.config().inferenceFps());

        PipelineConfig next = new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 15, 2, true, Set.of());
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
        // stated explicitly (docs/plans/active/CV-DEMAND-PLAN.md §1 flipped the convenience-ctor default) since
        // this test's whole point is that detection keeps running across the swap.
        PipelineConfig hotter = new PipelineConfig(new ModelRef("yolo", "latest"), 0.75, 1000, 5, true, Set.of(),
                EventRuleConfig.defaults(), PipelineConfig.DEFAULT_OVERLAY_BURN_IN, true);
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
        PipelineConfig detectionOff = new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 30, 2, true, Set.of(),
                EventRuleConfig.defaults(), true, false);
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
        PipelineConfig detectionOff = new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 1000, 5, true, Set.of(),
                EventRuleConfig.defaults(), true, false);
        StreamPipeline pipeline = manualPipeline(detectionOff, () -> 0L);

        pipeline.onNext(frame(0));
        pipeline.onNext(frame(1));
        verify(detectionPort, never()).detect(any(), any());
        verify(streamPublisherPort, times(2)).publish(eq(streamId), any());

        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(2)));
        PipelineConfig detectionOn = new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 1000, 5, true, Set.of(),
                EventRuleConfig.defaults(), true, true);
        pipeline.updateConfig(detectionOn);
        pipeline.onNext(frame(2));

        verify(detectionPort, times(1)).detect(any(), any());
    }

    // --- docs/plans/active/CV-DEMAND-PLAN.md §1, §3.2: detection demand -- the second, independent gate ---

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
        PipelineConfig detectionOff = new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 30, 2, true, Set.of(),
                EventRuleConfig.defaults(), true, false);
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

    // --- docs/plans/active/CV-DEMAND-PLAN.md §3.6: DetectionState -- the gate made legible ---

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
        PipelineConfig detectionOff = new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 30, 2, true, Set.of(),
                EventRuleConfig.defaults(), true, false);
        StreamPipeline pipeline = pipeline(new ScriptedVideoPublisher(List.of()), detectionOff);

        assertEquals(DetectionState.OFF, pipeline.detectionState(),
                "detectionEnabled=false with demand still at its fail-open true default");

        pipeline.updateDetectionDemand(false);
        assertEquals(DetectionState.OFF, pipeline.detectionState(),
                "OFF beats IDLE_NO_VIEWERS -- the operator's own choice is the more specific truth, "
                        + "so disabling detection must never also read as 'nobody is watching'");
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
        PipelineConfig config = new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 30, 2, true, Set.of("person"),
                EventRuleConfig.defaults(), true, true);

        StreamPipeline pipeline = new StreamPipeline(streamId, device, config, publisher, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher, null, eventEngine, assetId,
                liveUpdatePublisherPort);
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
    void labelFilterAppliesToTheExtrapolatedOverlayViewToo() {
        VideoFrame f0 = frame(0);
        VideoFrame f1 = frame(1);
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(List.of(f0, f1));
        DetectionResult result = resultWithLabels(0, "person", "car");
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(result));
        OverlayPort overlayPort = mock(OverlayPort.class);
        when(overlayPort.render(any())).thenReturn(frame(99));
        PipelineConfig config = new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 30, 2, true, Set.of("person"),
                EventRuleConfig.defaults(), true, true);

        StreamPipeline pipeline = new StreamPipeline(streamId, device, config, publisher, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher, overlayPort);
        pipeline.start();

        ArgumentCaptor<AnnotatedFrame> captor = ArgumentCaptor.forClass(AnnotatedFrame.class);
        verify(overlayPort).render(captor.capture());
        assertEquals(List.of("person"), captor.getValue().detections().stream().map(Detection::label).toList());
    }

    @Test
    void updateConfigWithADifferentModelIdClearsStaleModelBoundDetectionStateAndAppliesTheNewModelOnTheNextSample() {
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(nonEmptyResult(0)));
        StreamPipeline pipeline = manualPipeline(config(1000, 5), () -> 0L);

        pipeline.onNext(frame(0));
        assertFalse(pipeline.latestDetections().isEmpty());

        // detectionEnabled stated explicitly (docs/plans/active/CV-DEMAND-PLAN.md §1 flipped the convenience-ctor
        // default) since this test's whole point is that the next sampled frame still detects, on
        // the new model.
        PipelineConfig newModel = new PipelineConfig(new ModelRef("orion12l", "latest"), 0.4, 1000, 5, true, Set.of(),
                EventRuleConfig.defaults(), PipelineConfig.DEFAULT_OVERLAY_BURN_IN, true);
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

    // --- docs/plans/done/TRACKING-PLAN.md §5.D/§5.E, wave T3: track book, stats window, follow sampling ---

    // --- docs/plans/active/CV-RATE-CONTROL-PLAN.md wave R2: the adaptive rate, end to end -------------

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
        return new DetectionResult(streamId, sequence, Instant.now(), List.of(detection), Duration.ofMillis(5));
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
                detectionPort, streamPublisherPort, detectionRepositoryPort, eventPublisher, null, null, null,
                null, null, fixedFpsClock(60), settingsWithAdaptiveRate(adaptiveRate), System::nanoTime).start();

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
        return new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, inferenceFps, 5, true, Set.of(),
                EventRuleConfig.defaults(), true, true, tracking);
    }

    private static TrackingConfig mode(TrackingMode trackingMode, int followFps) {
        return new TrackingConfig(trackingMode, "lk", 2000, followFps, 30, 30, 3, null);
    }

    private DetectionResult trackedResult(long sequence, long trackId, TrackState state) {
        Detection detection = new Detection("car", 0.9, new BoundingBox(0.3, 0.4, 0.1, 0.1),
                new ModelRef("yolo", "latest"), new TrackRef(trackId, state, DetectionSource.TRACKER));
        return new DetectionResult(streamId, sequence, Instant.parse("2026-08-11T10:00:00Z").plusMillis(sequence * 100),
                List.of(detection), Duration.ofMillis(5),
                new TrackingTelemetry(false, null, Duration.ofNanos(400_000), "lk", trackId));
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
                NO_OP_SOURCE, port, streamPublisherPort, detectionRepositoryPort, eventPublisher, null, null, null,
                null, null, thirtyFpsClock());
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

        pipeline.updateConfig(new PipelineConfig(new ModelRef("orion12l", "latest"), 0.4, 1000, 5, true, Set.of(),
                EventRuleConfig.defaults(), true, true, mode(TrackingMode.ASSOCIATE, 15)));

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
        PipelineConfig filtered = new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 1000, 5, true,
                Set.of("person"), EventRuleConfig.defaults(), true, true, mode(TrackingMode.FOLLOW, 15));
        StreamPipeline pipeline = manualPipeline(filtered, () -> 0L);

        pipeline.onNext(frame(0));

        assertTrue(pipeline.latestDetections().isEmpty(), "the 'car' detection is filtered out");
        assertTrue(pipeline.tracks().isEmpty(), "and therefore never booked");
        assertEquals(1L, pipeline.trackingStats().trackerFrames(), "but the frame still counted");
        assertEquals(7L, pipeline.trackingStats().lockedTrackId());
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
