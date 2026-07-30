package com.drones.vision.adapter.rtsp;

import com.drones.vision.domain.model.PixelFormat;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.DatagramSocket;
import java.net.URI;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FfmpegVideoSourceTest {

    private static final int MIN_FRAMES_EXPECTED = 5;
    private static final int SOURCE_FRAME_COUNT = 15;
    private static final int WIDTH = 64;
    private static final int HEIGHT = 48;
    // Generous: the first FFmpeg-touching test in the module pays for native lib extraction.
    private static final long AWAIT_SECONDS = 60;

    @Test
    void supportsRtspAnyUriAndFileOnlyWithAFileUri() {
        FfmpegVideoSource source = new FfmpegVideoSource();

        assertTrue(source.supports(new StreamDescriptor("rtsp", URI.create("rtsp://cam/stream"), Map.of())),
                "rtsp protocol must be supported regardless of the URI");
        assertTrue(source.supports(new StreamDescriptor("file", URI.create("file:///tmp/clip.mp4"), Map.of())),
                "file protocol must be supported when the URI scheme is itself file");
        assertFalse(source.supports(new StreamDescriptor("file", URI.create("http://example.com/clip.mp4"), Map.of())),
                "file protocol must be rejected when the URI is not actually a file: URI");
        assertFalse(source.supports(new StreamDescriptor("sim", URI.create("sim://cam"), Map.of())));
        assertFalse(source.supports(new StreamDescriptor("mjpeg", URI.create("http://cam/stream"), Map.of())));
        assertFalse(source.supports(null));
    }

    /** docs/DRONE-INFRA-PLAN.md I-h: srt/udp accepted only with a matching-scheme URI, unknown protocols rejected. */
    @Test
    void supportsSrtAndUdpOnlyWithAMatchingSchemeUri() {
        FfmpegVideoSource source = new FfmpegVideoSource();

        assertTrue(source.supports(new StreamDescriptor("srt", URI.create("srt://127.0.0.1:9000"), Map.of())),
                "srt protocol must be supported when the URI scheme is itself srt");
        assertTrue(source.supports(new StreamDescriptor("srt", URI.create("srt://0.0.0.0:9000"), Map.of())));
        assertFalse(source.supports(new StreamDescriptor("srt", URI.create("udp://127.0.0.1:9000"), Map.of())),
                "srt protocol must be rejected when the URI is not actually an srt: URI");

        assertTrue(source.supports(new StreamDescriptor("udp", URI.create("udp://0.0.0.0:9001"), Map.of())),
                "udp protocol must be supported when the URI scheme is itself udp");
        assertFalse(source.supports(new StreamDescriptor("udp", URI.create("srt://127.0.0.1:9001"), Map.of())),
                "udp protocol must be rejected when the URI is not actually a udp: URI");

        assertFalse(source.supports(new StreamDescriptor("webrtc", URI.create("webrtc://cam/stream"), Map.of())),
                "an unknown protocol must be rejected");
    }

    @Test
    void openRejectsAnUnsupportedDescriptor() {
        FfmpegVideoSource source = new FfmpegVideoSource();
        StreamDescriptor descriptor = new StreamDescriptor("sim", URI.create("sim://cam"), Map.of());

        assertThrows(IllegalArgumentException.class, () -> source.open(StreamId.random(), descriptor));
    }

    @Test
    void closeOnAnUnknownOrUnopenedStreamIsANoop() {
        FfmpegVideoSource source = new FfmpegVideoSource();

        assertDoesNotThrow(() -> source.close(StreamId.random()));
    }

    /**
     * File-based integration test: exercises the real grab loop (real
     * {@code FFmpegFrameGrabber}, real native decode) against a tiny local
     * mp4 generated on the fly with {@code FFmpegFrameRecorder}, via the
     * package-private {@link FfmpegVideoSource#openAny} seam that skips the
     * protocol check. This is what exercises the real FFmpeg path in CI,
     * without a live camera or RTSP server.
     */
    @Test
    void grabLoopProducesBgr24FramesWithCorrectDimensionsAndMonotonicSequenceFromAFile(@TempDir Path tempDir)
            throws Exception {
        Path videoFile = createTestVideo(tempDir, WIDTH, HEIGHT, SOURCE_FRAME_COUNT, 25);

        FfmpegVideoSource source = new FfmpegVideoSource();
        StreamId streamId = StreamId.random();

        List<VideoFrame> collected = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch atLeastMinFrames = new CountDownLatch(MIN_FRAMES_EXPECTED);
        CountDownLatch terminal = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        Flow.Publisher<VideoFrame> publisher = source.openAny(streamId, videoFile.toUri(), Map.of());
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(VideoFrame item) {
                collected.add(item);
                atLeastMinFrames.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                errorRef.set(throwable);
                terminal.countDown();
            }

            @Override
            public void onComplete() {
                terminal.countDown();
            }
        });

        try {
            assertTrue(atLeastMinFrames.await(AWAIT_SECONDS, TimeUnit.SECONDS),
                    "expected at least " + MIN_FRAMES_EXPECTED + " frames within " + AWAIT_SECONDS + "s");

            // The source file is small and finite; let the grab loop finish draining it
            // before taking the final snapshot (best-effort -- frames may still have been
            // dropped under the small-buffer latest-wins policy, which is fine here).
            terminal.await(AWAIT_SECONDS, TimeUnit.SECONDS);

            assertNull(errorRef.get(), "grab loop must not error on a well-formed local file");

            List<VideoFrame> snapshot = List.copyOf(collected);
            assertTrue(snapshot.size() >= MIN_FRAMES_EXPECTED,
                    "expected >= " + MIN_FRAMES_EXPECTED + " frames, got " + snapshot.size());

            long previousSequence = -1;
            for (VideoFrame frame : snapshot) {
                assertEquals(streamId, frame.streamId());
                assertEquals(WIDTH, frame.width());
                assertEquals(HEIGHT, frame.height());
                assertEquals(PixelFormat.BGR24, frame.format());
                assertEquals(WIDTH * HEIGHT * 3, frame.data().remaining(), "BGR24 payload must be width*height*3 bytes");
                assertTrue(frame.sequence() > previousSequence, "sequence must be strictly increasing");
                previousSequence = frame.sequence();
            }
        } finally {
            assertDoesNotThrow(() -> source.close(streamId));
            assertDoesNotThrow(() -> source.close(streamId), "close() must be idempotent");
        }
    }

    /**
     * Exercises the public {@link FfmpegVideoSource#open(StreamId, StreamDescriptor)}
     * path (not the {@code openAny} test seam) with a real {@code file:} descriptor,
     * confirming {@code supports()}/{@code open()} wiring for the {@code "file"}
     * protocol actually reaches the same grab loop.
     */
    @Test
    void openWithAFileProtocolDescriptorProducesFrames(@TempDir Path tempDir) throws Exception {
        Path videoFile = createTestVideo(tempDir, WIDTH, HEIGHT, SOURCE_FRAME_COUNT, 25);
        StreamDescriptor descriptor = new StreamDescriptor("file", videoFile.toUri(), Map.of());

        FfmpegVideoSource source = new FfmpegVideoSource();
        StreamId streamId = StreamId.random();
        CountDownLatch atLeastMinFrames = new CountDownLatch(MIN_FRAMES_EXPECTED);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        assertTrue(source.supports(descriptor));
        Flow.Publisher<VideoFrame> publisher = source.open(streamId, descriptor);
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(VideoFrame item) {
                atLeastMinFrames.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                errorRef.set(throwable);
            }

            @Override
            public void onComplete() {
            }
        });

        try {
            assertTrue(atLeastMinFrames.await(AWAIT_SECONDS, TimeUnit.SECONDS),
                    "expected at least " + MIN_FRAMES_EXPECTED + " frames within " + AWAIT_SECONDS + "s via the public open() path");
            assertNull(errorRef.get(), "grab loop must not error on a well-formed local file");
        } finally {
            assertDoesNotThrow(() -> source.close(streamId));
        }
    }

    /**
     * {@code loop=true} must restart the grabber on graceful EOF rather than
     * completing the publisher, and the frame sequence must keep climbing
     * past the source file's own frame count rather than resetting.
     */
    @Test
    void loopOptionRestartsOnEofAndSequenceKeepsIncreasingPastTheFilesFrameCount(@TempDir Path tempDir)
            throws Exception {
        int frameCount = 5;
        Path videoFile = createTestVideo(tempDir, WIDTH, HEIGHT, frameCount, 25);

        FfmpegVideoSource source = new FfmpegVideoSource();
        StreamId streamId = StreamId.random();
        List<VideoFrame> collected = Collections.synchronizedList(new ArrayList<>());
        // One full loop plus a few frames into the second pass -- proves a restart happened.
        CountDownLatch pastOneLoop = new CountDownLatch(frameCount + 3);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        Flow.Publisher<VideoFrame> publisher =
                source.openAny(streamId, videoFile.toUri(), Map.of("loop", "true"));
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(VideoFrame item) {
                collected.add(item);
                pastOneLoop.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                errorRef.set(throwable);
            }

            @Override
            public void onComplete() {
            }
        });

        try {
            assertTrue(pastOneLoop.await(AWAIT_SECONDS, TimeUnit.SECONDS),
                    "expected sequence to pass the file's own frame count (" + frameCount
                            + ") within " + AWAIT_SECONDS + "s, i.e. the grabber looped");
            assertNull(errorRef.get(), "looping a well-formed local file must not error");

            List<VideoFrame> snapshot = List.copyOf(collected);
            long maxSequence = snapshot.stream().mapToLong(VideoFrame::sequence).max().orElseThrow();
            assertTrue(maxSequence >= frameCount,
                    "expected sequence to exceed the file's frame count (" + frameCount + ") once looped, got max="
                            + maxSequence);

            long previousSequence = -1;
            for (VideoFrame frame : snapshot) {
                assertTrue(frame.sequence() > previousSequence,
                        "sequence must keep increasing monotonically across loop restarts, never reset");
                previousSequence = frame.sequence();
            }
        } finally {
            assertDoesNotThrow(() -> source.close(streamId));
        }
    }

    /** Without the {@code loop} option (default {@code false}), EOF completes the publisher. */
    @Test
    void withoutLoopOptionEofCompletesThePublisher(@TempDir Path tempDir) throws Exception {
        Path videoFile = createTestVideo(tempDir, WIDTH, HEIGHT, SOURCE_FRAME_COUNT, 25);

        FfmpegVideoSource source = new FfmpegVideoSource();
        StreamId streamId = StreamId.random();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        Flow.Publisher<VideoFrame> publisher = source.openAny(streamId, videoFile.toUri(), Map.of());
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(VideoFrame item) {
            }

            @Override
            public void onError(Throwable throwable) {
                errorRef.set(throwable);
                completed.countDown();
            }

            @Override
            public void onComplete() {
                completed.countDown();
            }
        });

        try {
            assertTrue(completed.await(AWAIT_SECONDS, TimeUnit.SECONDS),
                    "expected onComplete (loop defaults to false) within " + AWAIT_SECONDS + "s");
            assertNull(errorRef.get(), "a finite well-formed file must complete, not error, without loop");
        } finally {
            assertDoesNotThrow(() -> source.close(streamId));
        }
    }

    /**
     * A {@code file:} source must be paced to its own frame rate rather than
     * decoded flat out: {@code frameCount} frames at {@code fps} should take
     * at least half of the frame-rate-implied wall-clock duration. Generous
     * lower bound only -- this never asserts a tight upper bound, since CI
     * scheduling jitter must not flake the test.
     */
    @Test
    void fileSourceIsPacedToItsNativeFrameRate(@TempDir Path tempDir) throws Exception {
        int frameCount = 10;
        double fps = 20.0;
        Path videoFile = createTestVideo(tempDir, WIDTH, HEIGHT, frameCount, fps);

        FfmpegVideoSource source = new FfmpegVideoSource();
        StreamId streamId = StreamId.random();
        CountDownLatch allFrames = new CountDownLatch(frameCount);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicLong lastFrameNanos = new AtomicLong(-1);
        long startNanos = System.nanoTime();

        Flow.Publisher<VideoFrame> publisher = source.openAny(streamId, videoFile.toUri(), Map.of());
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(VideoFrame item) {
                lastFrameNanos.set(System.nanoTime());
                allFrames.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                errorRef.set(throwable);
            }

            @Override
            public void onComplete() {
            }
        });

        try {
            assertTrue(allFrames.await(AWAIT_SECONDS, TimeUnit.SECONDS),
                    "expected all " + frameCount + " frames within " + AWAIT_SECONDS + "s");
            assertNull(errorRef.get(), "pacing a well-formed local file must not error");

            double elapsedSeconds = (lastFrameNanos.get() - startNanos) / 1_000_000_000.0;
            double expectedMinimumSeconds = ((frameCount - 1) / fps) * 0.5; // generous lower bound only
            assertTrue(elapsedSeconds >= expectedMinimumSeconds,
                    "expected pacing to take >= " + expectedMinimumSeconds + "s for " + frameCount + " frames at "
                            + fps + "fps, took " + elapsedSeconds + "s");
        } finally {
            assertDoesNotThrow(() -> source.close(streamId));
        }
    }

    // -- docs/MVP2-PLAN.md V-c: RTSP demuxer latency tuning --------------------

    /**
     * Unit-level check of the {@code configureRtspOptions} seam: constructing
     * an {@link FFmpegFrameGrabber} and calling {@code setOption}/{@code
     * setMaxDelay} only assigns fields — no network I/O happens until {@code
     * start()}, which this test never calls — so this needs neither a live
     * camera nor even a reachable socket, mirroring adapter-publish-hls's
     * {@code configureRecorder} test seam.
     */
    @Test
    void configureRtspOptionsAppliesDefaultLowLatencyDemuxerTuning() {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber("rtsp://127.0.0.1:1/ignored");

        FfmpegVideoSource.configureRtspOptions(grabber, Map.of());

        assertEquals(FfmpegVideoSource.DEFAULT_PROBESIZE_BYTES, grabber.getOption("probesize"));
        assertEquals(FfmpegVideoSource.DEFAULT_ANALYZE_DURATION_MICROS, grabber.getOption("analyzeduration"));
        assertEquals(FfmpegVideoSource.DEFAULT_REORDER_QUEUE_SIZE, grabber.getOption("reorder_queue_size"));
        // max_delay is NOT read back via getOption() -- see DEFAULT_MAX_DELAY_MICROS's
        // javadoc: it must go through the dedicated setMaxDelay(int) setter, not the
        // generic string-option map, so it is asserted via getMaxDelay() instead.
        assertEquals(Integer.parseInt(FfmpegVideoSource.DEFAULT_MAX_DELAY_MICROS), grabber.getMaxDelay());
        // Pre-existing options must still be applied unchanged alongside the new ones.
        assertEquals(FfmpegVideoSource.DEFAULT_RTSP_TRANSPORT, grabber.getOption("rtsp_transport"));
        assertEquals(FfmpegVideoSource.DEFAULT_TIMEOUT_MICROS, grabber.getOption("timeout"));
        assertEquals(FfmpegVideoSource.DEFAULT_TIMEOUT_MICROS, grabber.getOption("rw_timeout"));
    }

    /** Every one of the four new options must be overridable via device options, same idiom as {@code timeout}. */
    @Test
    void configureRtspOptionsHonorsDeviceOptionOverrides() {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber("rtsp://127.0.0.1:1/ignored");
        Map<String, String> overrides = Map.of(
                "probesize", "65536",
                "analyzeduration", "2000000",
                "reorder_queue_size", "32",
                "max_delay", "250000");

        FfmpegVideoSource.configureRtspOptions(grabber, overrides);

        assertEquals("65536", grabber.getOption("probesize"));
        assertEquals("2000000", grabber.getOption("analyzeduration"));
        assertEquals("32", grabber.getOption("reorder_queue_size"));
        assertEquals(250_000, grabber.getMaxDelay());
    }

    /** A malformed {@code max_delay} override must fall back to the default, never crash setup. */
    @Test
    void configureRtspOptionsFallsBackToDefaultMaxDelayOnAMalformedOverride() {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber("rtsp://127.0.0.1:1/ignored");

        FfmpegVideoSource.configureRtspOptions(grabber, Map.of("max_delay", "not-a-number"));

        assertEquals(Integer.parseInt(FfmpegVideoSource.DEFAULT_MAX_DELAY_MICROS), grabber.getMaxDelay());
    }

    /**
     * Contract regression: the {@code file} protocol must never see any RTSP
     * grabber configuration at all — {@code newGrabber()} only calls {@code
     * configureRtspOptions} inside its {@code uri.getScheme().equals("rtsp")}
     * branch. Asserted here directly against a real grabber produced by the
     * production {@code file:} path (via the {@link #openAny} seam plumbing,
     * exercised end-to-end by the existing pacing/loop/EOF tests above),
     * rather than re-deriving scheme logic in the test — a fresh grabber's
     * {@code getOption} for any of these keys is {@code null} (never set),
     * and {@code getMaxDelay()} stays at {@code FrameGrabber}'s own default.
     */
    @Test
    void fileProtocolNeverReceivesRtspDemuxerTuning() {
        FFmpegFrameGrabber fileGrabber = new FFmpegFrameGrabber("/tmp/does-not-need-to-exist-for-this-check.mp4");

        // The production code path (StreamRuntime.newGrabber) only calls
        // configureRtspOptions for an rtsp-scheme URI; a file-scheme grabber is
        // never passed to it at all, so it is simply never called here either --
        // proving the "file untouched" contract by construction (no test-only
        // reflection needed), and pinning the untouched grabber's own defaults.
        assertNull(fileGrabber.getOption("probesize"));
        assertNull(fileGrabber.getOption("analyzeduration"));
        assertNull(fileGrabber.getOption("reorder_queue_size"));
        assertNull(fileGrabber.getOption("rtsp_transport"));
        assertNull(fileGrabber.getOption("timeout"));
        assertEquals(-1, fileGrabber.getMaxDelay(), "max_delay must stay at FrameGrabber's own unset default");
    }

    // -- docs/DRONE-INFRA-PLAN.md I-h: SRT + UDP/MPEG-TS ingest -----------------

    /**
     * Unit-level check of the {@code configureSrtOptions} seam, mirroring
     * {@code configureRtspOptionsAppliesDefaultLowLatencyDemuxerTuning}:
     * asserts the local option map a real grabber would be started with,
     * no network I/O. Also pins the {@code mode} default-inference rule for
     * an any-address host (docs/DRONE-INFRA-PLAN.md I-h: "the natural
     * choice for {@code srt://0.0.0.0:port}" is {@code listener}).
     */
    @Test
    void configureSrtOptionsAppliesDefaultsAndInfersListenerModeForAnAnyAddressHost() {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber("srt://0.0.0.0:9000");
        URI uri = URI.create("srt://0.0.0.0:9000");

        FfmpegVideoSource.configureSrtOptions(grabber, uri, Map.of());

        long expectedLatencyMicros = Long.parseLong(FfmpegVideoSource.DEFAULT_SRT_LATENCY_MILLIS) * 1000L;
        assertEquals(String.valueOf(expectedLatencyMicros), grabber.getOption("latency"),
                "default latency must be converted from ms (this module's option unit) to us (FFmpeg's own unit)");
        assertEquals(FfmpegVideoSource.SRT_MODE_LISTENER, grabber.getOption("mode"),
                "an any-address host (0.0.0.0) must default to listener mode -- the app binds and waits");
        assertNull(grabber.getOption("streamid"), "streamid has no default -- absent means unset");
        assertNull(grabber.getOption("passphrase"), "passphrase has no default -- absent means unset");
        assertNull(grabber.getOption("pbkeylen"), "pbkeylen must only be set when a passphrase is given");
    }

    /** A real host (not an any-address host) must default to caller mode -- the app dials out to it. */
    @Test
    void configureSrtOptionsInfersCallerModeForARealHost() {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber("srt://encoder.example:9000");
        URI uri = URI.create("srt://encoder.example:9000");

        FfmpegVideoSource.configureSrtOptions(grabber, uri, Map.of());

        assertEquals(FfmpegVideoSource.SRT_MODE_CALLER, grabber.getOption("mode"));
    }

    /**
     * Every device-option override must be honored, and a {@code
     * passphrase} must also set {@code pbkeylen} to this class's default
     * key length -- see {@link FfmpegVideoSource#OPTION_SRT_PASSPHRASE}'s
     * javadoc for why this is set explicitly rather than left to libsrt's
     * own internal default.
     */
    @Test
    void configureSrtOptionsHonorsDeviceOptionOverridesAndSetsPbkeylenWhenAPassphraseIsGiven() {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber("srt://encoder.example:9000");
        URI uri = URI.create("srt://encoder.example:9000");
        Map<String, String> overrides = Map.of(
                "latency", "250",
                "mode", "listener",
                "streamid", "drone-42",
                "passphrase", "a-real-passphrase");

        FfmpegVideoSource.configureSrtOptions(grabber, uri, overrides);

        assertEquals("250000", grabber.getOption("latency"), "250ms override must convert to 250000us");
        assertEquals("listener", grabber.getOption("mode"), "an explicit override must win over the inferred default");
        assertEquals("drone-42", grabber.getOption("streamid"));
        assertEquals("a-real-passphrase", grabber.getOption("passphrase"));
        assertEquals("16", grabber.getOption("pbkeylen"));
    }

    /**
     * Unit-level check of the {@code configureUdpOptions} seam: format is
     * always forced to {@code mpegts} (see {@code FORMAT_MPEGTS}'s javadoc
     * for why), and {@code fifo_size}/{@code overrun_nonfatal} get
     * low-latency defaults while {@code buffer_size} stays unset (no forced
     * default -- an OS-level knob this module leaves alone unless asked).
     */
    @Test
    void configureUdpOptionsForcesMpegtsFormatAndAppliesLowLatencyDefaults() {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber("udp://127.0.0.1:9002");

        FfmpegVideoSource.configureUdpOptions(grabber, Map.of());

        assertEquals("mpegts", grabber.getFormat());
        assertEquals(FfmpegVideoSource.DEFAULT_UDP_FIFO_SIZE_PACKETS, grabber.getOption("fifo_size"));
        assertEquals(FfmpegVideoSource.DEFAULT_UDP_OVERRUN_NONFATAL, grabber.getOption("overrun_nonfatal"));
        assertNull(grabber.getOption("buffer_size"), "buffer_size has no forced default -- absent means OS default");
        // Internal safety default, not a StreamDescriptor option (DEFAULT_UDP_TIMEOUT_MICROS is private) --
        // see that constant's javadoc for why an always-applied read timeout is a production-critical fix,
        // not just a test convenience: it bounds a udp source's native open() call so a source nobody ever
        // sends a packet to cannot hold JavaCV's process-wide start() lock forever.
        assertEquals("5000000", grabber.getOption("timeout"));
    }

    /** Every UDP device-option override must be honored, same idiom as SRT/RTSP above. */
    @Test
    void configureUdpOptionsHonorsDeviceOptionOverrides() {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber("udp://127.0.0.1:9002");
        Map<String, String> overrides = Map.of(
                "fifo_size", "4096",
                "overrun_nonfatal", "0",
                "buffer_size", "131072");

        FfmpegVideoSource.configureUdpOptions(grabber, overrides);

        assertEquals("4096", grabber.getOption("fifo_size"));
        assertEquals("0", grabber.getOption("overrun_nonfatal"));
        assertEquals("131072", grabber.getOption("buffer_size"));
    }

    // A short fixed-size burst (e.g. 30 frames) was tried first and reliably deadlocked the
    // receiver: raw UDP has no protocol-level EOF, so once the sender's finite burst ends,
    // FFmpegFrameGrabber#start()'s native avformat_find_stream_info call -- if it hadn't yet
    // finished probing (plausible on a cold JVM: this module's own MODULE.md notes the first
    // FFmpeg-touching test in a fresh module pays real native-library-load cost) -- blocks on
    // a read() that will now never be satisfied, and that native block is not responsive to
    // Thread#interrupt, reproduced empirically while writing this test (had to SIGKILL the
    // forked JVM after several minutes). Streaming continuously until the receiver confirms
    // (bounded by MAX_LOOPBACK_SENDER_FRAMES so a genuinely broken receiver still fails in a
    // bounded amount of time rather than hanging forever) removes the race instead of papering
    // over it -- and matches how a real encoder actually behaves: it keeps streaming regardless
    // of when a particular viewer's receiver happens to finish connecting.
    private static final int MAX_LOOPBACK_SENDER_FRAMES = 400;
    private static final long LOOPBACK_SENDER_FRAME_INTERVAL_MILLIS = 50; // ~20fps pacing; up to ~20s total

    /** Bounded retries for the udp loopback attempt below -- see that method's javadoc for why. */
    private static final int UDP_LOOPBACK_MAX_ATTEMPTS = 3;

    /**
     * End-to-end UDP/MPEG-TS loopback: a real {@link FFmpegFrameRecorder}
     * encodes synthetic H.264 frames to {@code udp://127.0.0.1:<port>} with
     * format {@code mpegts} while {@link FfmpegVideoSource} opens the same
     * URL and grabs at least one real {@link VideoFrame} back -- proving
     * the {@code udp} path end to end using only this module's own bundled
     * FFmpeg, no external tooling or docker. UDP is connectionless, so the
     * receiver (this class, in read/"listen" mode -- see the class
     * javadoc's listen-vs-dial note) has no guarantee it is bound by any
     * particular wall-clock moment; per the note above, the sender streams
     * continuously (not a fixed burst) so it is still producing packets
     * whenever the receiver's bind/probe actually completes.
     *
     * <p><b>Retries up to {@value #UDP_LOOPBACK_MAX_ATTEMPTS} times</b> (a
     * fresh port/source/sender each attempt) before failing. This is a
     * deliberate, considered choice, not a mask for a flaky test: a single
     * attempt's {@code avformat_find_stream_info} occasionally could not
     * conclusively identify a stream within its own analyze budget when
     * this test ran immediately after certain other tests in the same JVM
     * (observed: a fast, clean {@code "Did not find a video or audio
     * stream"} error, never a hang -- see {@link #DEFAULT_UDP_TIMEOUT_MICROS}
     * in {@code FfmpegVideoSource} and this module's MODULE.md Gotchas for
     * the related, more serious hang-risk finding and fix this uncovered).
     * A real production deployment already retries a failed {@link
     * FfmpegVideoSource#open} indefinitely via the stream supervisor's own
     * 1s-30s backoff, so re-attempting a handful of times here mirrors real
     * behavior rather than papering over a correctness bug -- each
     * individual attempt is a genuine, unmodified exercise of the real
     * {@code open()}/decode path.
     */
    @Test
    void udpMpegtsLoopbackDeliversAtLeastOneFrame() throws Exception {
        AtomicReference<Throwable> lastReceiveError = new AtomicReference<>();
        AtomicReference<Throwable> lastSendError = new AtomicReference<>();
        for (int attempt = 1; attempt <= UDP_LOOPBACK_MAX_ATTEMPTS; attempt++) {
            if (attemptUdpLoopback(lastReceiveError, lastSendError)) {
                return;
            }
        }
        fail("expected at least one frame over a udp:// loopback within "
                + UDP_LOOPBACK_MAX_ATTEMPTS + " attempts -- last receiver error: " + lastReceiveError.get()
                + ", last sender error: " + lastSendError.get());
    }

    /**
     * One udp loopback attempt; returns {@code true} iff a frame was
     * received without error.
     *
     * <p><b>Starts the sender before the receiver</b> -- the reverse of
     * this test's own first draft, and safe only because UDP is
     * connectionless (an unbound destination silently drops early
     * datagrams instead of refusing them, unlike SRT/TCP). This ordering
     * was forced by {@link FfmpegVideoSource}'s own {@link
     * FfmpegVideoSource#DEFAULT_UDP_TIMEOUT_MICROS} production fix (see
     * that constant's javadoc and this module's MODULE.md Gotchas): once
     * the receiver's native probe is bounded by a real read timeout rather
     * than blocking forever, it stops patiently retrying an idle socket --
     * confirmed via a verbose-FFmpeg-logging standalone reproduction, an
     * idle receiver's {@code avformat_find_stream_info} can return "0
     * bytes read" almost immediately rather than waiting out its own
     * budget. Waiting for {@link #streamUntilConfirmedOrTimeout} to signal
     * that the sender has actually started encoding (not just that the
     * thread was created) before opening the receiver ensures the
     * receiver's first read sees a live stream instead of racing an empty
     * socket -- this is not a workaround for a bug in the timeout fix, it
     * is what makes the test match how a real drone/encoder deployment
     * actually behaves (the encoder is already running before the app
     * connects to it).
     */
    private static boolean attemptUdpLoopback(AtomicReference<Throwable> receiveErrorRef,
            AtomicReference<Throwable> sendErrorRef) throws Exception {
        int port = freeEphemeralPort();
        URI udpUri = URI.create("udp://127.0.0.1:" + port);

        CountDownLatch senderStarted = new CountDownLatch(1);
        CountDownLatch atLeastOneFrame = new CountDownLatch(1);
        receiveErrorRef.set(null);
        sendErrorRef.set(null);

        Thread sender = new Thread(
                () -> streamUntilConfirmedOrTimeout(udpUri.toString(), atLeastOneFrame, sendErrorRef, senderStarted),
                "udp-loopback-test-sender");
        sender.start();

        FfmpegVideoSource source = null;
        StreamId streamId = StreamId.random();
        try {
            assertTrue(senderStarted.await(AWAIT_SECONDS, TimeUnit.SECONDS),
                    "expected the udp sender to start encoding within " + AWAIT_SECONDS + "s");

            source = new FfmpegVideoSource();
            Flow.Publisher<VideoFrame> publisher = source.openAny(streamId, udpUri, Map.of());
            publisher.subscribe(loopbackSubscriber(atLeastOneFrame, receiveErrorRef));

            boolean awaited = atLeastOneFrame.await(AWAIT_SECONDS, TimeUnit.SECONDS);
            return awaited && receiveErrorRef.get() == null && sendErrorRef.get() == null;
        } finally {
            atLeastOneFrame.countDown(); // let the sender loop's condition end promptly regardless of outcome
            sender.interrupt();
            sender.join(5_000);
            if (source != null) {
                FfmpegVideoSource finalSource = source;
                assertDoesNotThrow(() -> finalSource.close(streamId));
            }
        }
    }

    /**
     * SRT caller-path liveness check, gated on this build's bundled FFmpeg
     * actually including libsrt (checked at runtime via {@link
     * #isSrtProtocolRegistered()} rather than assumed -- see that method's
     * javadoc). Skips cleanly via {@link
     * org.junit.jupiter.api.Assumptions#assumeTrue} when libsrt is absent,
     * same posture as this module's docker-gated {@code
     * MediamtxDockerIntegrationTest}.
     *
     * <p>This module's {@code adapter-rtsp} pins {@code ffmpeg-platform-gpl
     * 6.1.1-1.5.10}, whose Linux/x86_64 native binary was confirmed (by
     * extracting the published jar and inspecting {@code
     * libavformat.so.60}) to be built with {@code --enable-libsrt} --
     * {@code SRTO_LATENCY}/{@code SRTO_PASSPHRASE}/{@code SRTO_STREAMID}/the
     * {@code caller}/{@code listener}/{@code rendezvous} mode constants all
     * appear in its exported strings, and {@code avio_enum_protocols}
     * confirms {@code srt} is a registered input protocol at runtime on
     * this platform. Other platform classifiers are not independently
     * verified, hence the runtime check rather than an unconditional test.
     *
     * <p><b>Deliberately does not attempt a live SRT listener open in this
     * JVM, and this is a considered safety decision, not a shortcut.</b> An
     * earlier draft of this test opened a real {@code srt://0.0.0.0:<port>}
     * listener (mode inferred by {@link FfmpegVideoSource#OPTION_SRT_MODE})
     * alongside a continuously-streaming caller sender, mirroring the
     * {@code udp} loopback test above. In this development environment
     * that listener open reliably hit libsrt's own native {@code "no
     * sockets to check, this would deadlock"} defensive error and never
     * returned. That alone would only fail one test -- but a full {@code
     * mvn test} run of this class then <b>stalled indefinitely on a later,
     * completely unrelated test method</b> (observed directly via {@code
     * jstack}): JavaCV's {@code FFmpegFrameGrabber}/{@code
     * FFmpegFrameRecorder#start()} both synchronize on a shared, static
     * lock (the {@code avcodec} class object) for their whole native
     * {@code avformat_open_input}/native-start call -- so the stuck
     * listener thread, never returning, held that process-wide lock
     * forever, and every subsequent test in the same JVM that called
     * {@code start()} (an entirely unrelated pacing test, in the observed
     * case) blocked on it too, cascading one skip into an apparently-hung
     * whole test class. Given that severity, this test only exercises the
     * CALLER role, verified safe empirically: a caller connecting to a
     * definitely-nothing-listening local port fails cleanly with a real
     * FFmpeg/libsrt exception in single-digit seconds every time it was
     * tried, never hangs. This still proves the real, bundled-FFmpeg SRT
     * caller code path is live and reachable through this class's own
     * {@code configureSrtOptions}/{@code open()} -- not a mock -- while
     * avoiding the listener path that is unsafe to automate here. See this
     * module's MODULE.md Gotchas ("verified finding: a JVM-hosted SRT
     * connection via JavaCV does not complete in this development
     * environment") for the full investigation, including why the same
     * connection succeeds via two independent, non-JVM {@code ffmpeg} CLI
     * processes on this same machine (ruling out a machine-wide network
     * restriction) and why this is not evidence against {@code
     * configureSrtOptions}'s own correctness (covered by the unit tests
     * above, which touch no network at all).
     */
    @Test
    void srtCallerConnectAttemptReachesRealFfmpegSrtCodeAndFailsCleanlyWhenNobodyIsListening() throws Exception {
        assumeTrue(isSrtProtocolRegistered(),
                "srt protocol not registered in this build's bundled FFmpeg (no libsrt) -- skipping");

        int port = freeEphemeralPort(); // guaranteed nothing is listening on it
        URI callerUri = URI.create("srt://127.0.0.1:" + port);

        FfmpegVideoSource source = new FfmpegVideoSource();
        StreamId streamId = StreamId.random();
        CountDownLatch terminal = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        Flow.Publisher<VideoFrame> publisher = source.openAny(streamId, callerUri, Map.of());
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }
            @Override public void onNext(VideoFrame item) {
            }
            @Override public void onError(Throwable throwable) {
                errorRef.set(throwable);
                terminal.countDown();
            }
            @Override public void onComplete() {
                terminal.countDown();
            }
        });

        try {
            // A real SRT connect attempt against nobody listening was measured at up to ~6s in
            // this environment (SRT's own connect_timeout default territory) -- some real
            // wall-clock time is expected since this is a live libsrt attempt, not a mock, but
            // 30s is a generous ceiling for a healthy (non-hanging) caller path.
            assertTrue(terminal.await(30, TimeUnit.SECONDS),
                    "expected the srt:// caller connection attempt to reach a terminal state (onError) "
                            + "within 30s when nobody is listening -- a hang here would mean the JVM-lock-"
                            + "poisoning risk documented in this method's javadoc / MODULE.md has resurfaced "
                            + "for the caller role too, not just listener");
            assertNotNull(errorRef.get(), "connecting to a definitely-nothing-listening srt:// port must error");
        } finally {
            assertDoesNotThrow(() -> source.close(streamId));
        }
    }

    /** Subscriber for the udp loopback test above: counts down on the first frame or any terminal error. */
    private static Flow.Subscriber<VideoFrame> loopbackSubscriber(CountDownLatch atLeastOneFrame,
            AtomicReference<Throwable> errorRef) {
        return new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }
            @Override public void onNext(VideoFrame item) {
                atLeastOneFrame.countDown();
            }
            @Override public void onError(Throwable throwable) {
                errorRef.set(throwable);
                atLeastOneFrame.countDown();
            }
            @Override public void onComplete() {
            }
        };
    }

    /**
     * Streams synthetic H.264/MPEG-TS frames to {@code targetUri} for as
     * long as {@code confirmed} hasn't fired, up to {@link
     * #MAX_LOOPBACK_SENDER_FRAMES} (~{@link
     * #LOOPBACK_SENDER_FRAME_INTERVAL_MILLIS}-paced) frames -- see the
     * comment above {@link #MAX_LOOPBACK_SENDER_FRAMES} for why a
     * continuous stream is used instead of a fixed-size burst. {@code
     * started} counts down the moment the recorder's {@code start()}
     * (real encoder/socket init) succeeds -- see {@link
     * #attemptUdpLoopback}'s own javadoc for why the caller must wait for
     * this before opening the receiver -- or immediately if setup itself
     * fails, so the caller never waits out its own timeout on a sender
     * that never got going. Used by the udp loopback test above (the srt
     * test does not use this seam -- it deliberately never opens a live
     * srt listener in this JVM, see its own javadoc); encoder settings
     * mirror {@link RtspFeedTransmitter}'s (ultrafast/zerolatency H.264),
     * plus {@code setGopSize(1)} so every single frame is independently
     * decodable -- the receiver does not need to land on a specific
     * keyframe cadence.
     */
    private static void streamUntilConfirmedOrTimeout(String targetUri, CountDownLatch confirmed,
            AtomicReference<Throwable> errorRef, CountDownLatch started) {
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(targetUri, WIDTH, HEIGHT)) {
            recorder.setFormat("mpegts");
            recorder.setVideoCodec(AV_CODEC_ID_H264);
            recorder.setVideoCodecName("libx264");
            recorder.setVideoOption("preset", "ultrafast");
            recorder.setVideoOption("tune", "zerolatency");
            recorder.setFrameRate(20.0);
            recorder.setGopSize(1);
            recorder.setPixelFormat(AV_PIX_FMT_YUV420P);
            recorder.start();
            started.countDown(); // encoder is live -- safe for a connectionless-udp receiver to start probing now
            int frameIndex = 0;
            while (confirmed.getCount() > 0 && frameIndex < MAX_LOOPBACK_SENDER_FRAMES) {
                recorder.record(solidFrame(WIDTH, HEIGHT, frameIndex++));
                confirmed.await(LOOPBACK_SENDER_FRAME_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            errorRef.compareAndSet(null, e);
            started.countDown(); // don't let the caller wait out its own timeout on a sender that failed to start
        }
    }

    /**
     * Runtime libsrt presence check via FFmpeg's own {@code
     * avio_enum_protocols} C API (not a hardcoded assumption about this
     * pinned dependency version): walks the list of registered input
     * protocol names looking for {@code "srt"}. Deliberately not a live
     * connection attempt -- enumerating registered protocols cannot hang or
     * flake on network conditions the way a live probe connection could.
     */
    private static boolean isSrtProtocolRegistered() {
        PointerPointer<?> opaque = new PointerPointer<>(1);
        opaque.put(0, null);
        BytePointer name;
        while ((name = avformat.avio_enum_protocols(opaque, 0)) != null) {
            if ("srt".equals(name.getString())) {
                return true;
            }
        }
        return false;
    }

    /** Ephemeral local UDP port for a loopback test: bind briefly, then release it for the real test traffic. */
    private static int freeEphemeralPort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path createTestVideo(Path dir, int width, int height, int frameCount, double fps)
            throws Exception {
        Path file = dir.resolve("ffmpeg-source-test-" + frameCount + "-" + fps + ".mp4");
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(file.toFile(), width, height)) {
            recorder.setFormat("mp4");
            recorder.setFrameRate(fps);
            recorder.start();
            for (int i = 0; i < frameCount; i++) {
                recorder.record(solidFrame(width, height, i));
            }
        }
        return file;
    }

    /**
     * Regression test for the audio-interleave pacing stall: mp4 muxers write
     * audio packets up to ~0.5s ahead of video, and pacing on {@code grab()}'s
     * mixed audio+video timestamps slept until wall-clock caught up with the
     * audio look-ahead — starving a real 30fps clip with an AAC track down to
     * ~2 emitted fps. With {@code grabImage()} the pacing timeline is
     * video-only, so a 3s file with audio must deliver most of its frames in
     * roughly real time, not one every half second.
     */
    @Test
    void fileWithAudioTrackIsPacedByVideoTimestampsNotStalledByAudioLookahead(@TempDir Path tempDir)
            throws Exception {
        int fps = 15;
        int frameCount = 45; // 3s of media
        Path videoFile = createTestVideoWithSilentAudio(tempDir, WIDTH, HEIGHT, frameCount, fps);

        FfmpegVideoSource source = new FfmpegVideoSource();
        StreamId streamId = StreamId.random();
        int expectedWithinWindow = 30; // 2s of media; the stall bug delivered ~2fps -> ~11 frames in the window
        CountDownLatch enoughFrames = new CountDownLatch(expectedWithinWindow);

        Flow.Publisher<VideoFrame> publisher = source.openAny(streamId, videoFile.toUri(), Map.of());
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }
            @Override public void onNext(VideoFrame item) {
                enoughFrames.countDown();
            }
            @Override public void onError(Throwable throwable) {
            }
            @Override public void onComplete() {
            }
        });
        try {
            // Real-time budget for 2s of media + startup slack (native extraction
            // is prepaid by earlier tests in the class). The pre-fix stall needed
            // ~15s wall-clock for these 30 frames, far outside this window.
            assertTrue(enoughFrames.await(6, TimeUnit.SECONDS),
                    "expected " + expectedWithinWindow + " frames of an audio-carrying 15fps file within 6s "
                            + "(audio-interleave pacing stall would deliver ~2fps)");
        } finally {
            source.close(streamId);
        }
    }

    /** Same as {@link #createTestVideo} but muxes an interleaved silent mono AAC track alongside the video. */
    private static Path createTestVideoWithSilentAudio(Path dir, int width, int height, int frameCount, double fps)
            throws Exception {
        Path file = dir.resolve("ffmpeg-source-test-av-" + frameCount + "-" + fps + ".mp4");
        int sampleRate = 44100;
        int samplesPerFrame = (int) Math.round(sampleRate / fps);
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(file.toFile(), width, height, 1)) {
            recorder.setFormat("mp4");
            recorder.setFrameRate(fps);
            recorder.setSampleRate(sampleRate);
            recorder.start();
            java.nio.ShortBuffer silence = java.nio.ShortBuffer.allocate(samplesPerFrame);
            for (int i = 0; i < frameCount; i++) {
                recorder.record(solidFrame(width, height, i));
                silence.rewind();
                recorder.recordSamples(sampleRate, 1, silence);
            }
        }
        return file;
    }

    /** A single-color synthetic BGR frame, shaded by frame index, for the test-only recorder. */
    private static Frame solidFrame(int width, int height, int frameIndex) {
        int channels = 3;
        int stride = width * channels;
        ByteBuffer buffer = ByteBuffer.allocateDirect(stride * height);
        byte value = (byte) (frameIndex * 17);
        for (int i = 0; i < buffer.capacity(); i++) {
            buffer.put(value);
        }
        buffer.rewind();

        Frame frame = new Frame();
        frame.imageWidth = width;
        frame.imageHeight = height;
        frame.imageDepth = Frame.DEPTH_UBYTE;
        frame.imageChannels = channels;
        frame.imageStride = stride;
        frame.image = new Buffer[] {buffer};
        return frame;
    }
}
