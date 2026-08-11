package com.drones.vision.adapter.publishhls;

import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.PixelFormat;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.domain.port.out.ReplayFrameExtractionPort;
import com.drones.vision.domain.port.out.StreamPublisherPort;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.net.CookieManager;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end check against a real <a href="https://github.com/bluenviron/mediamtx">mediamtx</a>
 * container: publishes synthetic frames through {@link MediamtxStreamPublisher}
 * and asserts mediamtx's HLS playlist for the stream becomes fetchable.
 *
 * <p>Runs the container via the {@code docker} CLI directly (no Testcontainers
 * dependency, per {@code docs/plans/done/PHASE1-PLAN.md} — this module adds no
 * dependencies beyond javacv/ffmpeg-platform-gpl). Skips cleanly (not a
 * failure) whenever the {@code docker} CLI isn't usable; the container name
 * is randomized and always removed in a {@code finally} block, so a crashed
 * run never leaves a stray container behind or blocks the next run.
 */
@EnabledIf(value = "dockerAvailable", disabledReason = "docker is not available in this environment")
class MediamtxDockerIntegrationTest {

    private static final String IMAGE = "bluenviron/mediamtx:latest";
    private static final Duration PLAYLIST_TIMEOUT = Duration.ofSeconds(30);
    /**
     * docs/plans/done/OPS-CORE-PLAN.md §R: the recording/playback test below deliberately pins the exact
     * image tag docker-compose.yml pins ({@code bluenviron/mediamtx:1.19.3}), not the {@code
     * :latest} the other tests in this class use — recording/playback is new, stack-specific
     * config this test is meant to validate against the real version this app actually ships with,
     * not whatever "latest" happens to resolve to on a given day.
     */
    private static final String RECORDING_IMAGE = "bluenviron/mediamtx:1.19.3";

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void publishedStreamBecomesFetchableAsHlsOnRealMediamtx() throws Exception {
        String containerName = "vision-publish-hls-it-" + java.util.UUID.randomUUID();
        try {
            startContainer(containerName);
            int rtspPort = resolveHostPort(containerName, "8554/tcp");
            int hlsPort = resolveHostPort(containerName, "8888/tcp");
            awaitTcpPortOpen(rtspPort, Duration.ofSeconds(10));

            StreamPublisherPort publisher = new MediamtxStreamPublisher(
                    URI.create("rtsp://localhost:" + rtspPort), URI.create("http://localhost:" + hlsPort),
                    URI.create("http://localhost:8889"), null); // WHEP/playback not exercised by this HLS-focused test
            StreamId streamId = StreamId.random();
            Device device = new Device(DeviceId.random(), "docker-it-camera",
                    Set.of(Capability.VIDEO), new StreamDescriptor("sim", URI.create("sim://docker-it"), Map.of()));
            URI playlistUrl = publisher.viewUrl(streamId).orElseThrow(() -> new AssertionError("expected a view URL"));

            publisher.streamStarted(streamId, device);
            AtomicBoolean keepPumping = new AtomicBoolean(true);
            // A real pipeline publishes continuously; mediamtx only serves HLS while frames
            // keep arriving (mediamtx tears the muxer down once the RTSP source goes idle), so
            // this pumps frames at ~15fps for as long as it takes the playlist to become
            // fetchable (capped by PLAYLIST_TIMEOUT below), well past the ~1s/15-frame GOP
            // boundary (docs/plans/done/MVP2-PLAN.md V-a) a single short burst would need to close its
            // first HLS segment.
            Thread pump = startFramePump(publisher, streamId, keepPumping);
            try {
                long elapsedMs = pollUntilFetchable(playlistUrl, PLAYLIST_TIMEOUT);
                assertTrue(elapsedMs >= 0,
                        "expected " + playlistUrl + " to become fetchable within " + PLAYLIST_TIMEOUT);
            } finally {
                keepPumping.set(false);
                pump.join(Duration.ofSeconds(5).toMillis());
            }

            publisher.streamEnded(streamId);
        } finally {
            removeContainerQuietly(containerName);
        }
    }

    /**
     * Regression test for a real bug: {@link org.bytedeco.javacv.FFmpegFrameRecorder#setTimestamp(long)}
     * quantizes whatever microsecond value it's handed down to a whole video
     * frame number at the recorder's configured frame rate (see {@code
     * MediamtxStreamPublisher.StreamState#nextTimestampMicros}'s javadoc for
     * the exact formula). Two frames whose {@code capturedAt} land less than
     * one frame period apart — routine with bursty {@link
     * java.util.concurrent.SubmissionPublisher} delivery, e.g. a scheduled
     * producer catching up after a stall — used to round to the *same*
     * frame number, and the muxer rejected the second write with {@code
     * av_interleaved_write_frame() error -22} (EINVAL: non-monotonic /
     * duplicate DTS), observed in production as repeated WARN-log
     * publish-failure/backoff/recovery cycles even though HLS kept serving
     * (degradation, not outage). This drives frame timing through the real
     * FFmpeg muxer (a plain unit test can't see FFmpeg's own PTS rounding)
     * and asserts zero publish-failure WARNings for a cadence that reliably
     * reproduced the bug pre-fix.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void burstyFrameDeliveryNeverTriggersNonMonotonicPtsFailures() throws Exception {
        String containerName = "vision-publish-hls-it-" + java.util.UUID.randomUUID();
        Logger julLogger = Logger.getLogger(MediamtxStreamPublisher.class.getName());
        AtomicInteger publishFailures = new AtomicInteger();
        Handler failureCountingHandler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    publishFailures.incrementAndGet();
                }
            }
            @Override public void flush() {
            }
            @Override public void close() {
            }
        };
        julLogger.addHandler(failureCountingHandler);
        try {
            startContainer(containerName);
            int rtspPort = resolveHostPort(containerName, "8554/tcp");
            awaitTcpPortOpen(rtspPort, Duration.ofSeconds(10));

            StreamPublisherPort publisher = new MediamtxStreamPublisher(
                    URI.create("rtsp://localhost:" + rtspPort), URI.create("http://localhost:8888"),
                    URI.create("http://localhost:8889"), null); // WHEP/playback not exercised by this HLS-focused test
            StreamId streamId = StreamId.random();
            Device device = new Device(DeviceId.random(), "bursty-it-camera",
                    Set.of(Capability.VIDEO), new StreamDescriptor("sim", URI.create("sim://bursty-it"), Map.of()));
            publisher.streamStarted(streamId, device);

            // Realistic-but-jittery 15fps cadence: most gaps are one frame period
            // (~66.7ms), but every 7th frame lands only 4ms after its predecessor —
            // a burst well inside one frame period, exactly what a bursty
            // SubmissionPublisher delivers when a scheduled producer catches up.
            int width = 64;
            int height = 48;
            long nominalGapMicros = 1_000_000L / 15L;
            Instant base = Instant.now();
            long cursorMicros = 0;
            int totalFrames = 75;
            for (int i = 0; i < totalFrames; i++) {
                long gap = (i % 7 == 0 && i > 0) ? 4_000L : nominalGapMicros;
                cursorMicros += gap;
                Instant capturedAt = base.plusNanos(cursorMicros * 1000L);
                byte[] data = new byte[width * height * 3];
                VideoFrame frame = new VideoFrame(streamId, i, capturedAt, width, height,
                        PixelFormat.BGR24, ByteBuffer.wrap(data));
                publisher.publish(streamId, frame);
                Thread.sleep(15);
            }

            publisher.streamEnded(streamId);

            assertEquals(0, publishFailures.get(),
                    "publish() must not log any WARNING-level failures for a bursty-but-plausible frame "
                            + "cadence (non-monotonic/duplicate PTS must be avoided, not just tolerated)");
        } finally {
            julLogger.removeHandler(failureCountingHandler);
            removeContainerQuietly(containerName);
        }
    }

    /**
     * Regression test for the user-visible slow-motion bug: the publisher used
     * to configure its encoder with a fixed 15fps frame rate and quantize all
     * timestamps onto that grid, so a 30fps source had every frame bumped into
     * the next 15fps slot — the published timeline advanced at half wall-clock
     * speed and viewers saw ~2x slow motion. With cadence now measured before
     * the recorder starts, the received stream's media-timestamp span must
     * track wall-clock time (ratio ~1.0), not stretch (~2.0 pre-fix).
     *
     * <p>Reads the stream back over RTSP in-process; per adapter-rtsp's
     * documented same-JVM TX/RX contention gotcha, the reading grabber uses a
     * short 2s timeout so it can never stall the publishing side.
     */
    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void thirtyFpsSourcePlaysBackAtWallClockSpeedNotSlowMotion() throws Exception {
        String containerName = "vision-publish-hls-it-" + java.util.UUID.randomUUID();
        try {
            startContainer(containerName);
            int rtspPort = resolveHostPort(containerName, "8554/tcp");
            awaitTcpPortOpen(rtspPort, Duration.ofSeconds(10));

            StreamPublisherPort publisher = new MediamtxStreamPublisher(
                    URI.create("rtsp://localhost:" + rtspPort), URI.create("http://localhost:8888"),
                    URI.create("http://localhost:8889"), null); // WHEP/playback not exercised by this HLS-focused test
            StreamId streamId = StreamId.random();
            Device device = new Device(DeviceId.random(), "wallclock-it-camera",
                    Set.of(Capability.VIDEO), new StreamDescriptor("sim", URI.create("sim://wallclock-it"), Map.of()));
            publisher.streamStarted(streamId, device);

            AtomicBoolean keepPumping = new AtomicBoolean(true);
            Thread pump = new Thread(() -> {
                int width = 320;
                int height = 240;
                long sequence = 0;
                while (keepPumping.get()) {
                    byte[] data = new byte[width * height * 3];
                    VideoFrame frame = new VideoFrame(streamId, sequence++, Instant.now(), width, height,
                            PixelFormat.BGR24, ByteBuffer.wrap(data));
                    publisher.publish(streamId, frame);
                    try {
                        Thread.sleep(1000L / 30);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "wallclock-it-frame-pump-30fps");
            pump.setDaemon(true);
            pump.start();
            try {
                double ratio = measureMediaToWallClockRatio(
                        "rtsp://localhost:" + rtspPort + "/" + streamId.value(), 60, Duration.ofSeconds(60));
                assertTrue(ratio > 0.6 && ratio < 1.5,
                        "media-timestamp span over wall-clock span was " + ratio
                                + " for a 30fps source; ~1.0 means real-time playback, ~2.0 is the "
                                + "pre-fix slow-motion regression (fixed 15fps encoder grid)");
            } finally {
                keepPumping.set(false);
                pump.join(Duration.ofSeconds(5).toMillis());
            }
            publisher.streamEnded(streamId);
        } finally {
            removeContainerQuietly(containerName);
        }
    }

    /**
     * docs/plans/done/OPS-CORE-PLAN.md §R end-to-end check: a real mediamtx started with the same
     * record/playback env vars docker-compose.yml sets ({@code MTX_PATHDEFAULTS_RECORD},
     * {@code MTX_PATHDEFAULTS_RECORDDELETEAFTER}, {@code MTX_PLAYBACK}, {@code
     * MTX_PLAYBACKADDRESS}) records a short published stream to disk, and {@link
     * MediamtxStreamPublisher#playbackUrl} — the exact URL production code builds, not a
     * hand-rolled one — fetches it back as an MP4 clip.
     *
     * <p>mediamtx only finalizes/flushes a path's in-progress recording segment once the path is
     * unpublished (verified manually against this same image/env before writing this test: a
     * short ffmpeg push followed by a clean disconnect made the segment appear on disk and become
     * servable within a few seconds — recording is not on a fixed segment-duration boundary here,
     * mediamtx's own default {@code recordSegmentDuration} is 1 hour), so this test calls {@link
     * StreamPublisherPort#streamEnded} (which stops the {@code FFmpegFrameRecorder}, cleanly
     * closing the RTSP push) before polling the playback endpoint, then polls rather than asserting
     * immediately since that finalization is not instantaneous.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void recordedStreamIsFetchableAsMp4ThroughPlaybackUrl() throws Exception {
        String containerName = "vision-publish-hls-it-" + java.util.UUID.randomUUID();
        try {
            startContainerWithRecording(containerName);
            int rtspPort = resolveHostPort(containerName, "8554/tcp");
            int playbackPort = resolveHostPort(containerName, "9996/tcp");
            awaitTcpPortOpen(rtspPort, Duration.ofSeconds(10));
            awaitTcpPortOpen(playbackPort, Duration.ofSeconds(10));

            StreamPublisherPort publisher = new MediamtxStreamPublisher(
                    URI.create("rtsp://localhost:" + rtspPort), URI.create("http://localhost:8888"),
                    URI.create("http://localhost:8889"), // HLS/WHEP not exercised by this recording-focused test
                    URI.create("http://localhost:" + playbackPort));
            StreamId streamId = StreamId.random();
            Device device = new Device(DeviceId.random(), "recording-it-camera",
                    Set.of(Capability.VIDEO), new StreamDescriptor("sim", URI.create("sim://recording-it"), Map.of()));

            Instant recordingStart = Instant.now();
            publisher.streamStarted(streamId, device);
            AtomicBoolean keepPumping = new AtomicBoolean(true);
            Thread pump = startFramePump(publisher, streamId, keepPumping);
            Duration pumpDuration = Duration.ofSeconds(4);
            Thread.sleep(pumpDuration.toMillis());
            keepPumping.set(false);
            pump.join(Duration.ofSeconds(5).toMillis());
            // Cleanly stops the FFmpegFrameRecorder (closes the RTSP push), which is what makes
            // mediamtx unpublish the path and finalize its recording segment -- see javadoc above.
            publisher.streamEnded(streamId);

            URI playbackUrl = publisher.playbackUrl(streamId, recordingStart, pumpDuration)
                    .orElseThrow(() -> new AssertionError("expected a playback URL (playback base was configured)"));

            byte[] clip = pollForPlayableClip(playbackUrl, Duration.ofSeconds(30));

            assertTrue(clip.length > 8, "expected a non-empty MP4 clip, got " + clip.length + " bytes");
            String boxType = new String(clip, 4, 4, StandardCharsets.US_ASCII);
            assertEquals("ftyp", boxType, "expected an MP4 'ftyp' box at the start of the clip");
        } finally {
            removeContainerQuietly(containerName);
        }
    }

    /**
     * docs/plans/done/CV-TRAINING-V2-PLAN.md §6 end-to-end check: {@link MediamtxReplayFrameExtractor} pulls a
     * decoded frame back out of the same kind of recorded clip {@link
     * #recordedStreamIsFetchableAsMp4ThroughPlaybackUrl} fetches as raw MP4 bytes — this test
     * decodes it all the way to a {@link VideoFrame} instead of only checking the byte stream is a
     * well-formed MP4, using the extractor's own production URL-building/grabbing path (not a
     * hand-rolled fetch).
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void recordedStreamFrameIsExtractableViaMediamtxReplayFrameExtractor() throws Exception {
        String containerName = "vision-publish-hls-it-" + java.util.UUID.randomUUID();
        try {
            startContainerWithRecording(containerName);
            int rtspPort = resolveHostPort(containerName, "8554/tcp");
            int playbackPort = resolveHostPort(containerName, "9996/tcp");
            awaitTcpPortOpen(rtspPort, Duration.ofSeconds(10));
            awaitTcpPortOpen(playbackPort, Duration.ofSeconds(10));

            URI playbackBase = URI.create("http://localhost:" + playbackPort);
            StreamPublisherPort publisher = new MediamtxStreamPublisher(
                    URI.create("rtsp://localhost:" + rtspPort), URI.create("http://localhost:8888"),
                    URI.create("http://localhost:8889"), // HLS/WHEP not exercised by this recording-focused test
                    playbackBase);
            StreamId streamId = StreamId.random();
            Device device = new Device(DeviceId.random(), "replay-extractor-it-camera",
                    Set.of(Capability.VIDEO), new StreamDescriptor("sim", URI.create("sim://replay-extractor-it"), Map.of()));

            Instant recordingStart = Instant.now();
            publisher.streamStarted(streamId, device);
            AtomicBoolean keepPumping = new AtomicBoolean(true);
            Thread pump = startFramePump(publisher, streamId, keepPumping);
            Thread.sleep(Duration.ofSeconds(4).toMillis());
            keepPumping.set(false);
            pump.join(Duration.ofSeconds(5).toMillis());
            // Same finalization requirement as the raw-bytes test above: streamEnded closes the
            // RTSP push, which is what makes mediamtx unpublish the path and flush the segment.
            publisher.streamEnded(streamId);

            Instant at = recordingStart.plusSeconds(1);
            ReplayFrameExtractionPort extractor = new MediamtxReplayFrameExtractor(playbackBase);

            VideoFrame frame = pollForFrame(extractor, streamId, at, Duration.ofSeconds(30))
                    .orElseThrow(() -> new AssertionError("expected a decoded replay frame at " + at));

            assertEquals(streamId, frame.streamId());
            assertEquals(0L, frame.sequence());
            assertEquals(at, frame.capturedAt());
            assertEquals(PixelFormat.BGR24, frame.format());
            assertTrue(frame.width() > 0 && frame.height() > 0,
                    "expected positive frame dimensions, got " + frame.width() + "x" + frame.height());
        } finally {
            removeContainerQuietly(containerName);
        }
    }

    /**
     * Polls {@link ReplayFrameExtractionPort#frameAt} until it returns a present frame (mediamtx's
     * recording segment for the requested window isn't finalized/servable instantly, same reason
     * {@link #pollForPlayableClip} polls) or {@code timeout} elapses.
     */
    private static Optional<VideoFrame> pollForFrame(ReplayFrameExtractionPort extractor, StreamId streamId,
            Instant at, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Optional<VideoFrame> result = extractor.frameAt(streamId, at);
            if (result.isPresent()) {
                return result;
            }
            Thread.sleep(500);
        }
        return Optional.empty();
    }

    private static void startContainerWithRecording(String name) throws IOException, InterruptedException {
        ProcessResult result = run(Duration.ofSeconds(90),
                "docker", "run", "-d", "--rm", "--name", name, "-p", "0:8554", "-p", "0:8888", "-p", "0:9996",
                "-e", "MTX_PATHDEFAULTS_RECORD=yes",
                "-e", "MTX_PATHDEFAULTS_RECORDDELETEAFTER=72h",
                "-e", "MTX_PLAYBACK=yes",
                "-e", "MTX_PLAYBACKADDRESS=:9996",
                RECORDING_IMAGE);
        if (result.exitCode() != 0) {
            fail("failed to start mediamtx container (recording+playback): " + result.output());
        }
    }

    /**
     * Polls {@code playbackUrl} until it returns {@code 200} with a non-empty body (mediamtx
     * returns {@code 404}/{@code 400} until the requested window's recording segment has been
     * finalized to disk, see this test method's own javadoc) or {@code timeout} elapses.
     */
    private static byte[] pollForPlayableClip(URI playbackUrl, Duration timeout) throws InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpRequest request = HttpRequest.newBuilder(playbackUrl).timeout(Duration.ofSeconds(5)).GET().build();
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == 200 && response.body().length > 0) {
                    return response.body();
                }
            } catch (IOException e) {
                // not ready yet; keep polling until the deadline
            }
            Thread.sleep(500);
        }
        fail("playback URL " + playbackUrl + " never returned a non-empty 200 within " + timeout);
        throw new AssertionError("unreachable");
    }

    /**
     * Connects an RTSP reader (retrying until the publisher's push makes the
     * path readable), then measures {@code videoFrames} received video frames:
     * returns (last-first media timestamp) / (wall-clock elapsed between the
     * same two frames).
     */
    private static double measureMediaToWallClockRatio(String rtspUrl, int videoFrames, Duration overallTimeout)
            throws Exception {
        long deadline = System.currentTimeMillis() + overallTimeout.toMillis();
        org.bytedeco.javacv.FFmpegFrameGrabber grabber = null;
        try {
            while (grabber == null) {
                if (System.currentTimeMillis() >= deadline) {
                    fail("RTSP reader could not connect to " + rtspUrl + " within " + overallTimeout);
                }
                org.bytedeco.javacv.FFmpegFrameGrabber candidate = new org.bytedeco.javacv.FFmpegFrameGrabber(rtspUrl);
                candidate.setOption("rtsp_transport", "tcp");
                // Same-JVM TX/RX: a long RX timeout can stall the in-process
                // publisher (adapter-rtsp MODULE.md gotcha) -- keep it short.
                candidate.setOption("timeout", "2000000");
                try {
                    candidate.start();
                    grabber = candidate;
                } catch (Exception notReadyYet) {
                    candidate.release();
                    Thread.sleep(500);
                }
            }
            // FFmpeg's demuxer flushes an initial burst of buffered frames right
            // after connect; anchoring the wall-clock measurement inside that
            // burst compresses the denominator and inflates the ratio, so the
            // first frames are skipped and only steady-state delivery is measured.
            int burstSkipFrames = 15;
            long firstTimestampMicros = -1;
            long firstWallNanos = 0;
            long lastTimestampMicros = -1;
            long lastWallNanos = 0;
            int skipped = 0;
            int seen = 0;
            while (seen < videoFrames && System.currentTimeMillis() < deadline) {
                org.bytedeco.javacv.Frame frame = grabber.grab();
                if (frame == null) {
                    break;
                }
                if (frame.image == null) {
                    continue;
                }
                if (skipped < burstSkipFrames) {
                    skipped++;
                    continue;
                }
                long ts = grabber.getTimestamp();
                long now = System.nanoTime();
                if (firstTimestampMicros < 0) {
                    firstTimestampMicros = ts;
                    firstWallNanos = now;
                }
                lastTimestampMicros = ts;
                lastWallNanos = now;
                seen++;
            }
            assertTrue(seen >= videoFrames / 2,
                    "expected to receive at least " + (videoFrames / 2) + " video frames, got " + seen);
            double mediaSpanMicros = lastTimestampMicros - firstTimestampMicros;
            double wallSpanMicros = (lastWallNanos - firstWallNanos) / 1000.0;
            assertTrue(wallSpanMicros > 0, "degenerate wall-clock span");
            return mediaSpanMicros / wallSpanMicros;
        } finally {
            if (grabber != null) {
                grabber.release();
            }
        }
    }

    /** JUnit {@code @EnabledIf} condition: true iff the {@code docker} CLI can talk to a daemon. */
    static boolean dockerAvailable() {
        try {
            return run(Duration.ofSeconds(5), "docker", "info").exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void startContainer(String name) throws IOException, InterruptedException {
        ProcessResult result = run(Duration.ofSeconds(90),
                "docker", "run", "-d", "--rm", "--name", name, "-p", "0:8554", "-p", "0:8888", IMAGE);
        if (result.exitCode() != 0) {
            fail("failed to start mediamtx container: " + result.output());
        }
    }

    private static int resolveHostPort(String containerName, String containerPort) throws IOException, InterruptedException {
        ProcessResult result = run(Duration.ofSeconds(10), "docker", "port", containerName, containerPort);
        if (result.exitCode() != 0) {
            fail("failed to resolve host port for " + containerPort + ": " + result.output());
        }
        List<String> lines = result.output().lines().map(String::trim).filter(s -> !s.isBlank()).toList();
        String chosen = lines.stream().filter(l -> l.startsWith("0.0.0.0:")).findFirst().orElseGet(() -> lines.get(0));
        String portPart = chosen.substring(chosen.lastIndexOf(':') + 1);
        return Integer.parseInt(portPart.trim());
    }

    private static void awaitTcpPortOpen(int port, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", port), 500);
                return;
            } catch (IOException e) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static Thread startFramePump(StreamPublisherPort publisher, StreamId streamId, AtomicBoolean keepRunning) {
        Thread pump = new Thread(() -> {
            int width = 320;
            int height = 240;
            long periodMs = 1000L / 15;
            long sequence = 0;
            while (keepRunning.get()) {
                byte[] data = new byte[width * height * 3];
                byte shade = (byte) (sequence % 256);
                for (int i = 0; i < data.length; i += 3) {
                    data[i] = shade;
                    data[i + 1] = (byte) (255 - shade);
                    data[i + 2] = (byte) ((sequence * 3) % 256);
                }
                VideoFrame frame = new VideoFrame(streamId, sequence, Instant.now(), width, height,
                        PixelFormat.BGR24, ByteBuffer.wrap(data));
                publisher.publish(streamId, frame);
                sequence++;
                try {
                    Thread.sleep(periodMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "mediamtx-it-frame-pump");
        pump.setDaemon(true);
        pump.start();
        return pump;
    }

    /** @return elapsed milliseconds once the playlist is fetchable, or -1 if {@code timeout} elapses first */
    private static long pollUntilFetchable(URI playlistUrl, Duration timeout) {
        long startNanos = System.nanoTime();
        // mediamtx redirects the first request per session with a cookie-pinning 302; a
        // plain stateless client would loop on 404s forever, so redirects + cookies are required.
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .cookieHandler(new CookieManager())
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpRequest request = HttpRequest.newBuilder(playlistUrl).timeout(Duration.ofSeconds(3)).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && !response.body().isBlank()) {
                    return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
                }
            } catch (IOException | InterruptedException e) {
                // not ready yet; keep polling until the deadline
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1L;
            }
        }
        return -1L;
    }

    private static void removeContainerQuietly(String name) {
        try {
            run(Duration.ofSeconds(15), "docker", "rm", "-f", name);
        } catch (Exception ignored) {
            // best-effort cleanup only
        }
    }

    private static ProcessResult run(Duration timeout, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "docker-cli-output-reader");
            t.setDaemon(true);
            return t;
        });
        Future<String> outputFuture = executor.submit(
                () -> new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Command timed out after " + timeout + ": " + String.join(" ", command));
            }
            String output;
            try {
                output = outputFuture.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                output = "";
            }
            return new ProcessResult(process.exitValue(), output);
        } finally {
            executor.shutdownNow();
        }
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
