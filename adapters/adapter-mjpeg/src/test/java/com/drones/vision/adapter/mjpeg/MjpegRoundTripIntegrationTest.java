package com.drones.vision.adapter.mjpeg;

import com.drones.vision.domain.model.FeedId;
import com.drones.vision.domain.model.FeedSpec;
import com.drones.vision.domain.model.PixelFormat;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;

import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full TX→wire→RX round trip through this module's own two halves, with
 * <b>no docker involved</b> (unlike {@code adapter-rtsp}'s {@code
 * MediamtxDockerIntegrationTest}, MJPEG needs no external server): {@link
 * MjpegFeedTransmitter} serves a tiny generated video file as an MJPEG HTTP
 * stream, and {@link MjpegVideoSource} (the RX half) ingests it back from
 * the descriptor {@link MjpegFeedTransmitter#start} returns. Also proves the
 * default {@code loop=true} behavior (frames keep flowing well past the
 * source file's own short duration) and that both halves leave no {@code
 * mjpeg-} prefixed thread running once stopped.
 */
class MjpegRoundTripIntegrationTest {

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void transmittedFeedRoundTripsThroughThisModulesOwnRxSideAndKeepsLoopingPastFileDuration(@TempDir Path tempDir)
            throws Exception {
        int frameCount = 5;
        double fps = 20.0;
        Path videoFile = createTestVideo(tempDir, 64, 48, frameCount, fps);

        MjpegFeedTransmitter transmitter = new MjpegFeedTransmitter();
        FeedId feedId = FeedId.random();
        MjpegVideoSource rxSource = new MjpegVideoSource();
        StreamId streamId = StreamId.random();

        try {
            // No explicit "loop" option -- this exercises MjpegFeedTransmitter's default (true).
            StreamDescriptor txDescriptor = transmitter.start(feedId, new FeedSpec("mjpeg", videoFile.toUri(), Map.of()));
            assertEquals("mjpeg", txDescriptor.protocol());

            List<VideoFrame> collected = Collections.synchronizedList(new ArrayList<>());
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            Object monitor = new Object();

            Flow.Publisher<VideoFrame> publisher = rxSource.open(streamId, txDescriptor);
            publisher.subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(VideoFrame item) {
                    collected.add(item);
                    synchronized (monitor) {
                        monitor.notifyAll();
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    errorRef.set(throwable);
                    synchronized (monitor) {
                        monitor.notifyAll();
                    }
                }

                @Override
                public void onComplete() {
                    synchronized (monitor) {
                        monitor.notifyAll();
                    }
                }
            });

            awaitAtLeast(collected, monitor, 3, Duration.ofSeconds(30));
            assertNull(errorRef.get(), "a real TX->wire->RX round trip must not error");

            for (VideoFrame frame : List.copyOf(collected)) {
                assertEquals(PixelFormat.JPEG, frame.format(), "MJPEG frames must pass through as JPEG");
                assertTrue(frame.width() > 0 && frame.height() > 0,
                        "expected plausible positive dimensions, got " + frame.width() + "x" + frame.height());
            }

            // Keep waiting until more frames have arrived than the source file itself
            // contains -- proof the feed kept going past a single pass (i.e. it looped).
            awaitAtLeast(collected, monitor, frameCount + 3, Duration.ofSeconds(30));
            assertNull(errorRef.get());
            assertTrue(collected.size() > frameCount,
                    "expected more than " + frameCount + " frames once the feed looped past its own file's duration, got "
                            + collected.size());
        } finally {
            rxSource.close(streamId);
            transmitter.stop(feedId);
            transmitter.close();
        }

        // Bounded joins already happened inside close()/stop(); a brief grace period covers any
        // last bookkeeping (e.g. a handler thread's name restoring itself in its own finally
        // block) racing the assertion below.
        Thread.sleep(200);
        List<String> leftoverMjpegThreads = Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(name -> name.startsWith("mjpeg-"))
                .toList();
        assertTrue(leftoverMjpegThreads.isEmpty(), "expected no mjpeg- threads left, found: " + leftoverMjpegThreads);
    }

    private static void awaitAtLeast(List<VideoFrame> collected, Object monitor, int count, Duration timeout)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        synchronized (monitor) {
            while (collected.size() < count && System.currentTimeMillis() < deadline) {
                monitor.wait(500);
            }
        }
        assertTrue(collected.size() >= count,
                "expected at least " + count + " frames within " + timeout + ", got " + collected.size());
    }

    private static Path createTestVideo(Path dir, int width, int height, int frameCount, double fps) throws Exception {
        Path file = dir.resolve("mjpeg-round-trip-it-" + frameCount + "-" + fps + ".mp4");
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
