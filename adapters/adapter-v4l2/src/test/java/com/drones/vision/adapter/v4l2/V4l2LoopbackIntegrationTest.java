package com.drones.vision.adapter.v4l2;

import com.drones.vision.perception.domain.model.PixelFormat;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.VideoFrame;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-device ingest test: opens {@link V4l2VideoSource} against an actual
 * {@code /dev/videoN} node and asserts frames actually arrive.
 *
 * <p><b>Assumption-check pattern</b> (same idiom as {@code adapter-rtsp}'s/
 * {@code adapter-publish-hls}'s docker-gated tests, adapted for hardware
 * instead of docker): {@link #v4l2DeviceAvailable()} <em>probes</em> for a
 * usable device before the test class is even allowed to run, rather than
 * asserting one exists. It never assumes a webcam is attached; a CI box or a
 * dev laptop with no camera and no loopback module simply skips this class,
 * cleanly, with a message pointing at the setup recipe below (never a
 * failure). See {@code adapter-v4l2/MODULE.md} for the same recipe, kept in
 * sync.
 *
 * <h2>Setting up a device to run this test locally (Linux)</h2>
 * <pre>{@code
 * sudo modprobe v4l2loopback video_nr=9 card_label="vision-test-loopback"
 * # feed a synthetic test pattern into it, kept running in the background:
 * ffmpeg -re -f lavfi -i testsrc=size=640x480:rate=30 -f v4l2 /dev/video9
 * }</pre>
 * Then either rely on this test's own scan (it walks every {@code
 * /dev/video*} node looking for one that actually starts and yields a frame)
 * or pin it explicitly: {@code VISION_V4L2_TEST_DEVICE=/dev/video9 ./mvnw ...}.
 * A real, unoccupied USB webcam at {@code /dev/video0} (or wherever the
 * kernel assigns it) works exactly the same way, no loopback module needed.
 */
class V4l2LoopbackIntegrationTest {

    private static final Pattern VIDEO_NODE_PATTERN = Pattern.compile("video\\d+");
    private static final long PROBE_TIMEOUT_MILLIS = 3_000L;

    /** Set by {@link #v4l2DeviceAvailable()} once it finds a usable candidate; read by the test. */
    private static volatile String probedDevicePath;

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @EnabledIf(value = "v4l2DeviceAvailable",
            disabledReason = "no usable /dev/video* device found -- see this class's javadoc "
                    + "(or adapter-v4l2/MODULE.md) for the v4l2loopback setup recipe")
    void grabsFramesFromARealOrLoopbackV4l2Device() throws Exception {
        String devicePath = probedDevicePath;
        V4l2VideoSource source = new V4l2VideoSource();
        StreamId streamId = StreamId.random();
        StreamDescriptor descriptor = new StreamDescriptor("v4l2", URI.create("file:" + devicePath), Map.of());

        List<VideoFrame> collected = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstFrame = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        Flow.Publisher<VideoFrame> publisher = source.open(streamId, descriptor);
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(VideoFrame item) {
                collected.add(item);
                firstFrame.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                errorRef.set(throwable);
                firstFrame.countDown();
            }

            @Override
            public void onComplete() {
                firstFrame.countDown();
            }
        });

        try {
            assertTrue(firstFrame.await(15, TimeUnit.SECONDS),
                    "expected at least one frame from " + devicePath + " within 15s");
            assertNull(errorRef.get(), "grab loop must not error against a usable device");
            assertTrue(!collected.isEmpty(), "expected at least one collected frame");

            VideoFrame frame = collected.get(0);
            assertEquals(streamId, frame.streamId());
            assertEquals(PixelFormat.BGR24, frame.format());
            assertTrue(frame.width() > 0 && frame.height() > 0,
                    "expected positive dimensions, got " + frame.width() + "x" + frame.height());
        } finally {
            assertDoesNotThrow(() -> source.close(streamId));
        }
    }

    /**
     * JUnit {@code @EnabledIf} condition: {@code true} iff a usable {@code
     * v4l2} device was found, in which case {@link #probedDevicePath} is set
     * for the test method to reuse. Honors {@code VISION_V4L2_TEST_DEVICE}
     * (skip scanning, use exactly this path) when set; otherwise scans every
     * {@code /dev/video*} node and probes each in turn.
     */
    static boolean v4l2DeviceAvailable() {
        String override = System.getenv("VISION_V4L2_TEST_DEVICE");
        List<String> candidates = (override != null && !override.isBlank())
                ? List.of(override.trim())
                : discoverCandidateDevices();
        for (String candidate : candidates) {
            if (probe(candidate)) {
                probedDevicePath = candidate;
                return true;
            }
        }
        return false;
    }

    private static List<String> discoverCandidateDevices() {
        Path dev = Path.of("/dev");
        try (Stream<Path> entries = Files.list(dev)) {
            return entries.map(p -> p.getFileName().toString())
                    .filter(name -> VIDEO_NODE_PATTERN.matcher(name).matches())
                    .sorted()
                    .map(name -> "/dev/" + name)
                    .toList();
        } catch (IOException | RuntimeException e) {
            return List.of(); // no /dev to scan (e.g. not Linux) -- nothing to probe
        }
    }

    /**
     * Best-effort, time-bounded probe: tries to start a grabber against
     * {@code devicePath} and pull exactly one frame, off a background thread
     * so a device that blocks indefinitely (e.g. an occupied or misbehaving
     * node) cannot hang the build -- a probe that doesn't finish within
     * {@link #PROBE_TIMEOUT_MILLIS} counts as "not usable", same as one that
     * throws.
     */
    private static boolean probe(String devicePath) {
        if (!Files.isReadable(Path.of(devicePath))) {
            return false;
        }
        AtomicReference<Boolean> result = new AtomicReference<>(Boolean.FALSE);
        Thread probeThread = new Thread(() -> {
            FFmpegFrameGrabber grabber = null;
            try {
                grabber = new FFmpegFrameGrabber(devicePath);
                grabber.setFormat("v4l2");
                grabber.start();
                Frame frame = grabber.grabImage();
                result.set(frame != null);
            } catch (Exception e) {
                result.set(Boolean.FALSE);
            } finally {
                if (grabber != null) {
                    try {
                        grabber.release();
                    } catch (Exception ignored) {
                        // best-effort cleanup only
                    }
                }
            }
        }, "v4l2-probe-" + devicePath);
        probeThread.setDaemon(true);
        probeThread.start();
        try {
            probeThread.join(PROBE_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (probeThread.isAlive()) {
            return false; // still blocked past the budget -- treat as unusable, leave it be (daemon thread)
        }
        return Boolean.TRUE.equals(result.get());
    }
}
