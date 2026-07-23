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
                URI.create("rtsp://localhost:8554"), URI.create("http://localhost:8888"));
        StreamId id = StreamId.of("11111111-1111-1111-1111-111111111111");

        Optional<URI> viewUrl = publisher.viewUrl(id);

        assertEquals(Optional.of(URI.create("http://localhost:8888/11111111-1111-1111-1111-111111111111/index.m3u8")), viewUrl);
    }

    @Test
    void viewUrlToleratesTrailingSlashOnHlsBase() {
        MediamtxStreamPublisher publisher = new MediamtxStreamPublisher(
                URI.create("rtsp://localhost:8554"), URI.create("http://localhost:8888/"));
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
                URI.create("rtsp://localhost:8554"), URI.create("/hls"));
        StreamId id = StreamId.of("11111111-1111-1111-1111-111111111111");

        Optional<URI> viewUrl = publisher.viewUrl(id);

        assertEquals(Optional.of(URI.create("/hls/11111111-1111-1111-1111-111111111111/index.m3u8")), viewUrl);
    }

    @Test
    void viewUrlReturnsEmptyForNullStreamId() {
        MediamtxStreamPublisher publisher = new MediamtxStreamPublisher(
                URI.create("rtsp://localhost:8554"), URI.create("http://localhost:8888"));

        assertEquals(Optional.empty(), publisher.viewUrl(null));
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
                URI.create("rtsp://127.0.0.1:1"), URI.create("http://localhost:8888"));
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
                URI.create("rtsp://127.0.0.1:1"), URI.create("http://localhost:8888"));
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
                    URI.create("rtsp://localhost:" + blackHole.getLocalPort()), URI.create("http://localhost:8888"));
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
                    URI.create("rtsp://localhost:" + blackHole.getLocalPort()), URI.create("http://localhost:8888"));
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
        assertEquals(60, recorder.getGopSize()); // GOP_SECONDS(2) * measured fps, not the old fixed 15fps*2=30
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
