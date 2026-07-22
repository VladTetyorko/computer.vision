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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtspVideoSourceTest {

    private static final int MIN_FRAMES_EXPECTED = 5;
    private static final int SOURCE_FRAME_COUNT = 15;
    private static final int WIDTH = 64;
    private static final int HEIGHT = 48;
    // Generous: the first FFmpeg-touching test in the module pays for native lib extraction.
    private static final long AWAIT_SECONDS = 60;

    @Test
    void supportsOnlyRtspProtocol() {
        RtspVideoSource source = new RtspVideoSource();

        assertTrue(source.supports(new StreamDescriptor("rtsp", URI.create("rtsp://cam/stream"), Map.of())));
        assertFalse(source.supports(new StreamDescriptor("sim", URI.create("sim://cam"), Map.of())));
        assertFalse(source.supports(new StreamDescriptor("mjpeg", URI.create("http://cam/stream"), Map.of())));
        assertFalse(source.supports(null));
    }

    @Test
    void openRejectsAnUnsupportedDescriptor() {
        RtspVideoSource source = new RtspVideoSource();
        StreamDescriptor descriptor = new StreamDescriptor("sim", URI.create("sim://cam"), Map.of());

        assertThrows(IllegalArgumentException.class, () -> source.open(StreamId.random(), descriptor));
    }

    @Test
    void closeOnAnUnknownOrUnopenedStreamIsANoop() {
        RtspVideoSource source = new RtspVideoSource();

        assertDoesNotThrow(() -> source.close(StreamId.random()));
    }

    /**
     * File-based integration test: exercises the real grab loop (real
     * {@code FFmpegFrameGrabber}, real native decode) against a tiny local
     * mp4 generated on the fly with {@code FFmpegFrameRecorder}, via the
     * package-private {@link RtspVideoSource#openAny} seam that skips the
     * {@code "rtsp"} protocol check. This is what exercises the real FFmpeg
     * path in CI, without a live camera or RTSP server.
     */
    @Test
    void grabLoopProducesBgr24FramesWithCorrectDimensionsAndMonotonicSequenceFromAFile(@TempDir Path tempDir)
            throws Exception {
        Path videoFile = createTestVideo(tempDir, WIDTH, HEIGHT, SOURCE_FRAME_COUNT, 25);

        RtspVideoSource source = new RtspVideoSource();
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

    private static Path createTestVideo(Path dir, int width, int height, int frameCount, double fps)
            throws Exception {
        Path file = dir.resolve("rtsp-source-test.mp4");
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
