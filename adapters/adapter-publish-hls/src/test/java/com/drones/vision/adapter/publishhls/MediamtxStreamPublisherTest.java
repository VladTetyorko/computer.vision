package com.drones.vision.adapter.publishhls;

import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.PixelFormat;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;

import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediamtxStreamPublisherTest {

    // -- viewUrl -------------------------------------------------------------

    @Test
    void viewUrlFormatsHlsPlaylistUrl() {
        MediamtxStreamPublisher publisher = new MediamtxStreamPublisher(
                URI.create("rtsp://localhost:8554"), URI.create("http://localhost:8888"),
                URI.create("http://localhost:8889"));
        StreamId id = StreamId.of("11111111-1111-1111-1111-111111111111");

        Optional<URI> viewUrl = publisher.viewUrl(id);

        assertEquals(Optional.of(URI.create("http://localhost:8888/11111111-1111-1111-1111-111111111111/index.m3u8")), viewUrl);
    }

    @Test
    void viewUrlToleratesTrailingSlashOnHlsBase() {
        MediamtxStreamPublisher publisher = new MediamtxStreamPublisher(
                URI.create("rtsp://localhost:8554"), URI.create("http://localhost:8888/"),
                URI.create("http://localhost:8889"));
        StreamId id = StreamId.of("11111111-1111-1111-1111-111111111111");

        Optional<URI> viewUrl = publisher.viewUrl(id);

        assertEquals(Optional.of(URI.create("http://localhost:8888/11111111-1111-1111-1111-111111111111/index.m3u8")), viewUrl);
    }

    /**
     * vision-app's HLS proxy feature wires this publisher with an
     * app-relative {@code hlsViewBase} (e.g. {@code "/hls"}, see
     * vision-app's {@code VisionPublishProperties#viewBase()}) so viewers
     * are handed a URL on this app's own origin instead of mediamtx's.
     * {@link URI#create(String)} accepts a path-only relative reference
     * without complaint, and the same trailing-slash-stripped concatenation
     * {@link #viewUrlFormatsHlsPlaylistUrl()} exercises for an absolute base
     * works identically for a relative one — no production-code change was
     * needed to support this.
     */
    @Test
    void viewUrlSupportsRelativeHlsViewBaseForAppProxiedUrls() {
        MediamtxStreamPublisher publisher = new MediamtxStreamPublisher(
                URI.create("rtsp://localhost:8554"), URI.create("/hls"), URI.create("http://localhost:8889"));
        StreamId id = StreamId.of("11111111-1111-1111-1111-111111111111");

        Optional<URI> viewUrl = publisher.viewUrl(id);

        assertEquals(Optional.of(URI.create("/hls/11111111-1111-1111-1111-111111111111/index.m3u8")), viewUrl);
    }

    @Test
    void viewUrlReturnsEmptyForNullStreamId() {
        MediamtxStreamPublisher publisher = new MediamtxStreamPublisher(
                URI.create("rtsp://localhost:8554"), URI.create("http://localhost:8888"),
                URI.create("http://localhost:8889"));

        assertEquals(Optional.empty(), publisher.viewUrl(null));
    }

    // -- whepUrl ---------------------------------------------------------------

    @Test
    void whepUrlFormatsWhepEndpointUrl() {
        MediamtxStreamPublisher publisher = new MediamtxStreamPublisher(
                URI.create("rtsp://localhost:8554"), URI.create("http://localhost:8888"),
                URI.create("http://localhost:8889"));
        StreamId id = StreamId.of("11111111-1111-1111-1111-111111111111");

        Optional<URI> whepUrl = publisher.whepUrl(id);

        assertEquals(Optional.of(URI.create("http://localhost:8889/11111111-1111-1111-1111-111111111111/whep")), whepUrl);
    }

    @Test
    void whepUrlToleratesTrailingSlashOnWhepBase() {
        MediamtxStreamPublisher publisher = new MediamtxStreamPublisher(
                URI.create("rtsp://localhost:8554"), URI.create("http://localhost:8888"),
                URI.create("http://localhost:8889/"));
        StreamId id = StreamId.of("11111111-1111-1111-1111-111111111111");

        Optional<URI> whepUrl = publisher.whepUrl(id);

        assertEquals(Optional.of(URI.create("http://localhost:8889/11111111-1111-1111-1111-111111111111/whep")), whepUrl);
    }

    /**
     * Unlike {@code hlsViewBase}, {@code whepViewBase} is never wired as an app-relative path in
     * production (see {@code StreamPublisherPort#whepUrl}'s javadoc: WHEP is not proxied), but
     * nothing in this formatting code special-cases that, so an absolute base always round-trips
     * to an absolute WHEP URL.
     */
    @Test
    void whepUrlIsAlwaysAnAbsoluteMediamtxOriginUrl() {
        MediamtxStreamPublisher publisher = new MediamtxStreamPublisher(
                URI.create("rtsp://localhost:8554"), URI.create("http://localhost:8888"),
                URI.create("http://mediamtx.local:8889"));
        StreamId id = StreamId.of("11111111-1111-1111-1111-111111111111");

        Optional<URI> whepUrl = publisher.whepUrl(id);

        assertEquals(Optional.of(URI.create("http://mediamtx.local:8889/11111111-1111-1111-1111-111111111111/whep")), whepUrl);
    }

    @Test
    void whepUrlReturnsEmptyForNullStreamId() {
        MediamtxStreamPublisher publisher = new MediamtxStreamPublisher(
                URI.create("rtsp://localhost:8554"), URI.create("http://localhost:8888"),
                URI.create("http://localhost:8889"));

        assertEquals(Optional.empty(), publisher.whepUrl(null));
    }

    // -- playbackUrl (docs/OPS-CORE-PLAN.md §R) ---------------------------------

    @Test
    void playbackUrlFormatsGetUrlWithPathStartAndDuration() {
        MediamtxStreamPublisher publisher = new MediamtxStreamPublisher(
                URI.create("rtsp://localhost:8554"), URI.create("http://localhost:8888"),
                URI.create("http://localhost:8889"), URI.create("http://localhost:19996"));
        StreamId id = StreamId.of("11111111-1111-1111-1111-111111111111");
        Instant start = Instant.parse("2026-01-15T10:00:00Z");

        Optional<URI> playbackUrl = publisher.playbackUrl(id, start, Duration.ofSeconds(30));

        assertEquals(Optional.of(URI.create("http://localhost:19996/get"
                        + "?path=11111111-1111-1111-1111-111111111111&start=2026-01-15T10:00:00Z&duration=30")),
                playbackUrl);
    }

    @Test
    void playbackUrlToleratesTrailingSlashOnPlaybackBase() {
        MediamtxStreamPublisher publisher = new MediamtxStreamPublisher(
                URI.create("rtsp://localhost:8554"), URI.create("http://localhost:8888"),
                URI.create("http://localhost:8889"), URI.create("http://localhost:19996/"));
        StreamId id = StreamId.of("11111111-1111-1111-1111-111111111111");
        Instant start = Instant.parse("2026-01-15T10:00:00Z");

        Optional<URI> playbackUrl = publisher.playbackUrl(id, start, Duration.ofSeconds(30));

        assertEquals(Optional.of(URI.create("http://localhost:19996/get"
                        + "?path=11111111-1111-1111-1111-111111111111&start=2026-01-15T10:00:00Z&duration=30")),
                playbackUrl);
    }

    @Test
    void playbackUrlReturnsEmptyForNullStreamId() {
        MediamtxStreamPublisher publisher = new MediamtxStreamPublisher(
                URI.create("rtsp://localhost:8554"), URI.create("http://localhost:8888"),
                URI.create("http://localhost:8889"), URI.create("http://localhost:19996"));

        assertEquals(Optional.empty(),
                publisher.playbackUrl(null, Instant.parse("2026-01-15T10:00:00Z"), Duration.ofSeconds(30)));
    }

    /**
     * The 4-arg constructor's {@code playbackViewBase} is genuinely optional (unlike the other
     * three bases) — a publisher constructed without one (explicit {@code null}) must return
     * {@link Optional#empty()} rather than build a URL against a nonexistent base, since not every
     * deployment has recording/playback configured (docs/OPS-CORE-PLAN.md §R).
     */
    @Test
    void playbackUrlReturnsEmptyWhenPlaybackBaseUnconfigured() {
        MediamtxStreamPublisher publisher = new MediamtxStreamPublisher(
                URI.create("rtsp://localhost:8554"), URI.create("http://localhost:8888"),
                URI.create("http://localhost:8889"), null);

        assertEquals(Optional.empty(),
                publisher.playbackUrl(StreamId.random(), Instant.parse("2026-01-15T10:00:00Z"), Duration.ofSeconds(30)));
    }

    /**
     * mediamtx's playback {@code /get} endpoint takes {@code duration} in seconds; a sub-second
     * java.time.Duration is rounded to the nearest whole second (round-half-up) rather than
     * truncated or passed through as a fraction — see {@link MediamtxStreamPublisher#playbackUrl}'s
     * javadoc for why sub-second precision would be false precision for this port's callers.
     */
    @Test
    void playbackUrlRoundsDurationToNearestWholeSecond() {
        MediamtxStreamPublisher publisher = new MediamtxStreamPublisher(
                URI.create("rtsp://localhost:8554"), URI.create("http://localhost:8888"),
                URI.create("http://localhost:8889"), URI.create("http://localhost:19996"));
        StreamId id = StreamId.of("11111111-1111-1111-1111-111111111111");
        Instant start = Instant.parse("2026-01-15T10:00:00Z");

        assertTrue(publisher.playbackUrl(id, start, Duration.ofMillis(1499)).orElseThrow().toString()
                .endsWith("duration=1"));
        assertTrue(publisher.playbackUrl(id, start, Duration.ofMillis(1500)).orElseThrow().toString()
                .endsWith("duration=2"));
        assertTrue(publisher.playbackUrl(id, start, Duration.ofMillis(2500)).orElseThrow().toString()
                .endsWith("duration=3"));
    }

    /**
     * mediamtx keys recordings on the same path name every published stream already lives at
     * (there is no separate "recording path" concept) — {@code playbackUrl}'s {@code path=} query
     * value must therefore be exactly the same {@code streamId.value()} string {@link #viewUrl}
     * and {@link #whepUrl} already format into their own URLs, not some independently-derived name.
     */
    @Test
    void playbackUrlPathNameMatchesViewUrlAndWhepUrlPathNaming() {
        MediamtxStreamPublisher publisher = new MediamtxStreamPublisher(
                URI.create("rtsp://localhost:8554"), URI.create("http://localhost:8888"),
                URI.create("http://localhost:8889"), URI.create("http://localhost:19996"));
        StreamId id = StreamId.random();

        String hlsPathSegment = publisher.viewUrl(id).orElseThrow().toString()
                .substring("http://localhost:8888/".length());
        String whepPathSegment = publisher.whepUrl(id).orElseThrow().toString()
                .substring("http://localhost:8889/".length());
        String playbackQuery = publisher.playbackUrl(id, Instant.now(), Duration.ofSeconds(1)).orElseThrow().getQuery();

        assertEquals(id.value() + "/index.m3u8", hlsPathSegment);
        assertEquals(id.value() + "/whep", whepPathSegment);
        assertTrue(playbackQuery.contains("path=" + id.value()));
    }

    /**
     * The 3-arg constructor is a convenience overload for callers (currently {@code vision-app})
     * that don't configure a playback base explicitly — it derives one from {@code whepViewBase}'s
     * own host at the compose-mapped playback port (docs/OPS-CORE-PLAN.md §R), so recording
     * playback works out of the box against this stack's own docker-compose.yml without any
     * wiring change.
     */
    @Test
    void playbackUrlIsDerivedFromWhepBaseHostViaConvenienceConstructor() {
        MediamtxStreamPublisher publisher = new MediamtxStreamPublisher(
                URI.create("rtsp://localhost:8554"), URI.create("http://localhost:8888"),
                URI.create("http://localhost:8889"));
        StreamId id = StreamId.of("11111111-1111-1111-1111-111111111111");
        Instant start = Instant.parse("2026-01-15T10:00:00Z");

        Optional<URI> playbackUrl = publisher.playbackUrl(id, start, Duration.ofSeconds(30));

        assertEquals(Optional.of(URI.create("http://localhost:19996/get"
                        + "?path=11111111-1111-1111-1111-111111111111&start=2026-01-15T10:00:00Z&duration=30")),
                playbackUrl);
    }

    /**
     * If {@code whepViewBase} has no host component to copy (malformed input — in production it's
     * always an absolute mediamtx origin, see {@code whepViewBase}'s javadoc), the convenience
     * constructor leaves playback unconfigured rather than building a broken guess: {@code
     * playbackUrl} then honestly reports "no recording available" instead of throwing.
     */
    @Test
    void playbackUrlIsEmptyViaConvenienceConstructorWhenWhepBaseHasNoHost() {
        MediamtxStreamPublisher publisher = new MediamtxStreamPublisher(
                URI.create("rtsp://localhost:8554"), URI.create("http://localhost:8888"), URI.create("/whep"));

        assertEquals(Optional.empty(),
                publisher.playbackUrl(StreamId.random(), Instant.parse("2026-01-15T10:00:00Z"), Duration.ofSeconds(30)));
    }

    // -- resilience ------------------------------------------------------------

    /**
     * mediamtx is unreachable for the whole test (port 1 on loopback refuses
     * connections immediately on Linux, so this stays fast without relying on
     * the recorder's own connect-timeout option). Publishing must never throw,
     * frames must simply be dropped, and {@code streamEnded} must still clean
     * up — see docs/PHASE1-PLAN.md §0.3 and §3.
     */
    @Test
    void publishNeverThrowsWhenMediamtxIsUnreachableAndStreamEndedStillCleansUp() {
        MediamtxStreamPublisher publisher = new MediamtxStreamPublisher(
                URI.create("rtsp://127.0.0.1:1"), URI.create("http://localhost:8888"),
                URI.create("http://localhost:8889"));
        StreamId id = StreamId.random();
        Device device = testDevice();

        assertDoesNotThrow(() -> publisher.streamStarted(id, device));

        Instant base = Instant.now();
        for (int i = 0; i < 3; i++) {
            VideoFrame frame = bgr24Frame(id, i, base.plusMillis(i * 66L));
            int seq = i;
            assertDoesNotThrow(() -> publisher.publish(id, frame), "publish() must not throw for frame " + seq);
        }

        assertDoesNotThrow(() -> publisher.streamEnded(id));
        // Idempotent: ending an already-ended (or never-started) stream must also never throw.
        assertDoesNotThrow(() -> publisher.streamEnded(id));
    }

    @Test
    void publishToleratesUnknownStreamNeverStartedAndNullArguments() {
        MediamtxStreamPublisher publisher = new MediamtxStreamPublisher(
                URI.create("rtsp://127.0.0.1:1"), URI.create("http://localhost:8888"),
                URI.create("http://localhost:8889"));
        StreamId neverStarted = StreamId.random();

        assertDoesNotThrow(() -> publisher.publish(neverStarted, bgr24Frame(neverStarted, 0, Instant.now())));
        assertDoesNotThrow(() -> publisher.publish(null, bgr24Frame(neverStarted, 0, Instant.now())));
        assertDoesNotThrow(() -> publisher.publish(neverStarted, null));
        assertDoesNotThrow(() -> publisher.streamStarted(null, null));
        assertDoesNotThrow(() -> publisher.streamEnded(null));
        assertDoesNotThrow(() -> publisher.streamEnded(StreamId.random()));
    }

    /**
     * Precise regression test for the backoff mechanism itself: a bare "this
     * completes quickly" assertion would not catch a bug where a
     * backoff-dropped frame is mistaken for a successful publish and resets
     * the backoff window on every call (verified manually while implementing
     * this class — that bug made every single frame during an outage trigger
     * a fresh, real connect attempt). Using a real local TCP server that
     * counts accepted connections lets the test observe actual connection
     * attempts directly instead of inferring them from timing.
     */
    @Test
    void publishOnlyRetriesConnectingOnceBackoffWindowElapses() throws IOException {
        try (ServerSocket blackHole = new ServerSocket(0)) {
            AtomicInteger connectionAttempts = new AtomicInteger();
            Thread acceptor = new Thread(() -> {
                while (!blackHole.isClosed()) {
                    try (Socket accepted = blackHole.accept()) {
                        connectionAttempts.incrementAndGet();
                    } catch (IOException e) {
                        return; // socket closed by the test; stop accepting
                    }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            MediamtxStreamPublisher publisher = new MediamtxStreamPublisher(
                    URI.create("rtsp://localhost:" + blackHole.getLocalPort()), URI.create("http://localhost:8888"),
                    URI.create("http://localhost:8889"));
            StreamId id = StreamId.random();
            publisher.streamStarted(id, testDevice());

            Instant base = Instant.now();
            // Back-to-back with no sleep: the whole burst completes in well under the
            // 500ms initial backoff window, so a correctly-throttled publisher must
            // only ever attempt one real connection for the whole burst.
            for (int i = 0; i < 10; i++) {
                publisher.publish(id, bgr24Frame(id, i, base.plusMillis(i * 66L)));
            }

            assertEquals(1, connectionAttempts.get(),
                    "backoff must prevent a real connect attempt for every dropped frame");

            publisher.streamEnded(id);
        }
    }

    // -- measured cadence (fixes 2x slow motion for non-15fps sources) -------

    /**
     * Regression test for the real bug this class was fixed for: the recorder
     * used to always start at a fixed {@code DEFAULT_FRAME_RATE_FPS} (15.0),
     * so a 30fps source got every timestamp bumped onto the 15fps grid and
     * played back at half wall-clock speed. The recorder's connection is now
     * deferred until {@code CADENCE_MEASUREMENT_FRAMES} frames have arrived
     * (measured, not encoded) — verified here the same way the backoff test
     * above verifies connection *timing*: a real local TCP server counts
     * accepted connections, so the test observes the actual connect attempt
     * directly instead of inferring it.
     */
    @Test
    void publishDefersRealConnectionUntilCadenceMeasurementCompletes() throws IOException {
        try (ServerSocket blackHole = new ServerSocket(0)) {
            AtomicInteger connectionAttempts = new AtomicInteger();
            Thread acceptor = new Thread(() -> {
                while (!blackHole.isClosed()) {
                    try (Socket accepted = blackHole.accept()) {
                        connectionAttempts.incrementAndGet();
                    } catch (IOException e) {
                        return; // socket closed by the test; stop accepting
                    }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            MediamtxStreamPublisher publisher = new MediamtxStreamPublisher(
                    URI.create("rtsp://localhost:" + blackHole.getLocalPort()), URI.create("http://localhost:8888"),
                    URI.create("http://localhost:8889"));
            StreamId id = StreamId.random();
            publisher.streamStarted(id, testDevice());

            Instant base = Instant.now();
            long thirtyFpsGapMillis = 33L;
            int measurementFrames = MediamtxStreamPublisher.CADENCE_MEASUREMENT_FRAMES;

            for (int i = 0; i < measurementFrames - 1; i++) {
                publisher.publish(id, bgr24Frame(id, i, base.plusMillis(i * thirtyFpsGapMillis)));
            }
            assertEquals(0, connectionAttempts.get(),
                    "no connection may be attempted before cadence measurement completes");

            publisher.publish(id, bgr24Frame(id, measurementFrames - 1L,
                    base.plusMillis((measurementFrames - 1L) * thirtyFpsGapMillis)));
            assertEquals(1, connectionAttempts.get(),
                    "the recorder must attempt its connection as soon as measurement completes");

            publisher.streamEnded(id);
        }
    }

    /**
     * Unit-level check of the {@code configureRecorder} seam: whatever fps
     * the pre-start cadence measurement produces must reach both {@code
     * setFrameRate} and a proportionally-sized GOP (not the old fixed 15fps).
     * Constructing an {@link FFmpegFrameRecorder} only assigns fields — no
     * network I/O happens until {@code start()}, which this test never calls
     * — so this needs neither a live mediamtx nor even a reachable socket.
     */
    @Test
    void configureRecorderAppliesMeasuredFrameRateAndProportionalGop() {
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder("rtsp://127.0.0.1:1/ignored", 64, 48);

        MediamtxStreamPublisher.configureRecorder(recorder, 30.0);

        assertEquals(30.0, recorder.getFrameRate());
        // GOP_SECONDS(1, docs/MVP2-PLAN.md V-a: was 2) * measured fps -- a 1s
        // keyframe interval matches mediamtx's own 1s hlsSegmentDuration, so
        // an HLS segment is never coarser than 1s regardless of the source's
        // measured cadence.
        assertEquals(30, recorder.getGopSize());
    }

    /**
     * Regression guard for the pixelation bug: without an explicit rate
     * control, {@link FFmpegFrameRecorder} encodes at its ~400 kbps default,
     * which macroblocks 720p footage (and the detection boxes burned into
     * it). CRF mode plus a VBV cap must reach the encoder's option map.
     */
    @Test
    void configureRecorderUsesCrfRateControlNotTheDefault400kbps() {
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder("rtsp://127.0.0.1:1/ignored", 64, 48);

        MediamtxStreamPublisher.configureRecorder(recorder, 30.0);

        assertEquals(MediamtxStreamPublisher.X264_CRF, recorder.getVideoOption("crf"));
        assertEquals(MediamtxStreamPublisher.X264_MAXRATE_BITS_PER_SECOND, recorder.getVideoOption("maxrate"));
        assertEquals(MediamtxStreamPublisher.X264_BUFSIZE_BITS, recorder.getVideoOption("bufsize"));
        assertEquals("veryfast", recorder.getVideoOption("preset"));
    }

    /**
     * docs/MVP2-PLAN.md V-a latency audit: {@code tune=zerolatency} (which
     * x264 itself expands to {@code --bframes 0 --no-mbtree --sync-lookahead
     * 0 --rc-lookahead 0 --force-cfr}, verified against x264's own source —
     * see {@code MediamtxStreamPublisher.configureRecorder}'s javadoc) is
     * the single option responsible for zero B-frame reordering delay and
     * zero rate-control lookahead buffering; {@code setMaxBFrames(0)} and
     * {@code sc_threshold=0} are this task's redundant-but-explicit,
     * independently testable reinforcements of the same intent (closed,
     * strictly periodic GOPs with no frame reordering) rather than new
     * behavior on their own.
     */
    @Test
    void configureRecorderMinimizesLatencyWithZerolatencyTuneNoBFramesAndClosedGop() {
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder("rtsp://127.0.0.1:1/ignored", 64, 48);

        MediamtxStreamPublisher.configureRecorder(recorder, 30.0);

        assertEquals("zerolatency", recorder.getVideoOption("tune"));
        assertEquals(0, recorder.getMaxBFrames());
        assertEquals(MediamtxStreamPublisher.X264_SCENECUT_THRESHOLD, recorder.getVideoOption("sc_threshold"));
    }

    /**
     * The keyframe interval an HLS segment is cut on can never be shorter
     * than the GOP itself (docs/MVP2-PLAN.md V-a) — this asserts the actual
     * wall-clock duration a GOP spans at a slow, real-world-plausible source
     * cadence is close to 1s (mediamtx's own configured {@code
     * hlsSegmentDuration}, see docker-compose.yml), not the old 2s.
     */
    @Test
    void configureRecorderGopSpansApproximatelyOneSecondAtTypicalFrameRates() {
        FFmpegFrameRecorder recorder15 = new FFmpegFrameRecorder("rtsp://127.0.0.1:1/ignored", 64, 48);
        MediamtxStreamPublisher.configureRecorder(recorder15, 15.0);
        assertEquals(15, recorder15.getGopSize());

        FFmpegFrameRecorder recorder24 = new FFmpegFrameRecorder("rtsp://127.0.0.1:1/ignored", 64, 48);
        MediamtxStreamPublisher.configureRecorder(recorder24, 24.0);
        assertEquals(24, recorder24.getGopSize());
    }

    private static VideoFrame bgr24Frame(StreamId id, long sequence, Instant capturedAt) {
        int width = 8;
        int height = 8;
        return new VideoFrame(id, sequence, capturedAt, width, height,
                PixelFormat.BGR24, ByteBuffer.wrap(new byte[width * height * 3]));
    }

    private static Device testDevice() {
        return new Device(DeviceId.random(), "test-camera", Set.of(Capability.VIDEO),
                new StreamDescriptor("sim", URI.create("sim://test"), Map.of()));
    }
}
