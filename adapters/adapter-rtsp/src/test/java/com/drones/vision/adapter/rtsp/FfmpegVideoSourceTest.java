package com.drones.vision.adapter.rtsp;

import com.drones.vision.domain.model.PixelFormat;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
