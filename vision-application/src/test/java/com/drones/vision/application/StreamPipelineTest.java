package com.drones.vision.application;

import com.drones.vision.domain.model.AnnotatedFrame;
import com.drones.vision.domain.model.BoundingBox;
import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.Detection;
import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.Event;
import com.drones.vision.domain.model.EventRuleConfig;
import com.drones.vision.domain.model.EventType;
import com.drones.vision.domain.model.ModelRef;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.PixelFormat;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.domain.port.out.DetectionPort;
import com.drones.vision.domain.port.out.DetectionRepositoryPort;
import com.drones.vision.domain.port.out.EventPublisherPort;
import com.drones.vision.domain.port.out.OverlayPort;
import com.drones.vision.domain.port.out.StreamPublisherPort;
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
import java.util.concurrent.Flow;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
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

    private static PipelineConfig config(int inferenceFps, int maxInFlight) {
        return new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, inferenceFps, maxInFlight, true, Set.of());
    }

    private static PipelineConfig config(int inferenceFps, int maxInFlight, boolean overlayBurnIn) {
        return new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, inferenceFps, maxInFlight, true, Set.of(),
                EventRuleConfig.defaults(), overlayBurnIn);
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

    private StreamPipeline pipeline(ScriptedVideoPublisher publisher, PipelineConfig config, LongSupplier clock) {
        return new StreamPipeline(streamId, device, config, publisher, detectionPort, streamPublisherPort,
                detectionRepositoryPort, eventPublisher, null, null, clock);
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
                streamPublisherPort, detectionRepositoryPort, eventPublisher, null, null, clock);
        pipeline.onSubscribe(NOOP_SUBSCRIPTION);
        return pipeline;
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
    void samplesEveryNthFrameBasedOnMeasuredSourceFpsOnceWarmedUp() {
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
    void usesAssumedThirtyFpsDuringWarmupRegardlessOfActualSourceRate() {
        // A slow, constant 5fps clock, but only 4 frames arrive -- fewer
        // than StreamPipeline.WARMUP_FRAMES (5) -- so every one of them is
        // still governed by the ASSUMED_SOURCE_FPS(30) fallback, not the
        // (very different) measured rate: everyNth = round(30/10) = 3.
        List<VideoFrame> frames = List.of(frame(0), frame(1), frame(2), frame(3));
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(frames);
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(0)));

        pipeline(publisher, config(10, 5), fixedFpsClock(5)).start();

        verify(detectionPort, times(2)).detect(any(), any()); // sequences 0, 3
    }

    @Test
    void samplesEveryOtherFrameAfterWarmupWhenMeasuredRateIsSlowerThanAssumed() {
        // 10fps source, inferenceFps=5. Warmup (frame indices 0-3, assumed
        // 30fps) -> everyNth = round(30/5) = 6, sampling only sequence 0.
        // Once WARMUP_FRAMES=5 frames have been observed (from frame index
        // 4 onward), the measured 10fps takes over -> everyNth =
        // round(10/5) = 2, sampling sequences 4, 6, 8.
        List<VideoFrame> frames = new ArrayList<>();
        for (long i = 0; i < 10; i++) {
            frames.add(frame(i));
        }
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(frames);
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(0)));

        pipeline(publisher, config(5, 10), fixedFpsClock(10)).start();

        verify(detectionPort, times(4)).detect(any(), any()); // sequences 0, 4, 6, 8
    }

    @Test
    void clampsMeasuredFpsToTheConfiguredMaximum() {
        // An absurdly fast synthetic clock (source far above any real
        // camera) must clamp the measured rate to
        // StreamPipeline.MAX_MEASURED_FPS (240) rather than an unbounded
        // value. inferenceFps=60: warmup (frame indices 0-3) uses assumed
        // 30fps -> everyNth = round(30/60) = 1 (every frame). From frame
        // index 4 onward the clamped 240fps measurement applies -> everyNth
        // = round(240/60) = 4, sampling sequences 4 and 8.
        List<VideoFrame> frames = new ArrayList<>();
        for (long i = 0; i < 10; i++) {
            frames.add(frame(i));
        }
        ScriptedVideoPublisher publisher = new ScriptedVideoPublisher(frames);
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(0)));

        pipeline(publisher, config(60, 10), fixedFpsClock(1_000_000)).start();

        verify(detectionPort, times(6)).detect(any(), any()); // sequences 0,1,2,3 (warmup) + 4,8 (clamped-measured)
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

        pipeline(publisher, config(30, 1)).start(); // every frame sampled, at most 1 in flight

        verify(detectionPort, times(1)).detect(any(), any());
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
        // docs/MVP2-PLAN.md §E, E-a: DetectionEventEngine needs empty results too (that is exactly
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

        pipeline(publisher, config(30, 2), eventEngine).start();

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
    void detectionFailureDoesNotClosePipelineAndVideoKeepsFlowing() {
        // Resilience policy (docs/MVP1-PLAN.md §C7): a failing/absent CV
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
        assertNull(captor.getValue().telemetry(), "no telemetry input reaches StreamPipeline yet (see class javadoc)");
    }

    @Test
    void latestFrameReflectsTheRenderedFrameWhenOverlayBurnInProducesOne() {
        // docs/MVP3-PLAN.md C-a: latestFrame() must expose the exact instance streamPublisherPort
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
        // docs/MVP2-PLAN.md §V, V-e: an OverlayPort is configured and detections are non-empty --
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
        // docs/CYCLES-PLAN.md §12 CP-c: with only one completed result (no "previous" yet), the
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

        pipeline(publisher, config(1000, 5), overlayPort).start();

        ArgumentCaptor<AnnotatedFrame> captor = ArgumentCaptor.forClass(AnnotatedFrame.class);
        verify(overlayPort).render(captor.capture());
        assertEquals(result.detections(), captor.getValue().detections());
    }

    @Test
    void overlayExtrapolatesTheMatchedBoxBetweenTwoCompletedResultsInsteadOfFreezingAtTheLatestRawPosition() {
        // docs/CYCLES-PLAN.md §12 CP-c: once a second result completes, the overlay for a
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

        pipeline(publisher, config(1000, 5), overlayPort).start();

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
        // docs/CYCLES-PLAN.md §12 CP-c: a frame published long after L (e.g. a stalled/outaged
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

        pipeline(publisher, config(1000, 5), overlayPort).start();

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
