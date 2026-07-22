package com.drones.vision.adapter.rtsp;

import com.drones.vision.domain.model.FeedId;
import com.drones.vision.domain.model.FeedSpec;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;

import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end check against a real <a href="https://github.com/bluenviron/mediamtx">mediamtx</a>
 * container: a genuine TX→wire→RX round trip through this module's own two
 * halves — {@link RtspFeedTransmitter} pushes a tiny generated video file to
 * mediamtx, and {@link FfmpegVideoSource} (the RX half) ingests it back from
 * the descriptor {@link RtspFeedTransmitter#start} returns. Also proves the
 * default {@code loop=true} behavior: the transmitted feed keeps producing
 * frames well past the source file's own short duration.
 *
 * <p>Runs the container via the {@code docker} CLI directly, duplicating the
 * container-lifecycle helpers of {@code adapter-publish-hls}'s {@code
 * MediamtxDockerIntegrationTest} (adapters must not depend on each other, so
 * this ~small helper set is copy-pasted rather than shared — same rule as
 * {@link FfmpegVideoSource#ensureQuietLogging()}'s duplication). Skips
 * cleanly (not a failure) whenever the {@code docker} CLI isn't usable; the
 * container name is randomized and always removed in a {@code finally}
 * block.
 */
@EnabledIf(value = "dockerAvailable", disabledReason = "docker is not available in this environment")
class MediamtxDockerIntegrationTest {

    private static final String IMAGE = "bluenviron/mediamtx:latest";

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void transmittedFeedRoundTripsThroughRealMediamtxToTheModulesOwnRxSideAndKeepsLoopingPastFileDuration(
            @TempDir Path tempDir) throws Exception {
        int frameCount = 5;
        double fps = 20.0;
        Path videoFile = createTestVideo(tempDir, 64, 48, frameCount, fps);
        String containerName = "vision-rtsp-tx-it-" + UUID.randomUUID();

        try {
            startContainer(containerName);
            int rtspPort = resolveHostPort(containerName, "8554/tcp");
            awaitTcpPortOpen(rtspPort, Duration.ofSeconds(10));

            RtspFeedTransmitter transmitter = new RtspFeedTransmitter(URI.create("rtsp://localhost:" + rtspPort));
            FeedId feedId = FeedId.random();
            // No explicit "loop" option -- this exercises RtspFeedTransmitter's default (true).
            StreamDescriptor descriptor = transmitter.start(feedId, new FeedSpec("rtsp", videoFile.toUri(), Map.of()));
            assertEquals("rtsp", descriptor.protocol());

            // Empirically required: FfmpegVideoSource's default RTSP "timeout"/"rw_timeout" (10s,
            // see its DEFAULT_TIMEOUT_MICROS) triggers a same-process JavaCV/FFmpeg native
            // contention with RtspFeedTransmitter's own concurrently-running recorder -- observed
            // as the *transmitter's* grab/record loop stalling for ~10s (matching the RX side's
            // configured timeout, not its own) before mediamtx drops the stale publisher
            // connection with "i/o timeout" and the next record() fails. A real TX+RX round trip
            // in a single JVM (exactly this test's shape, and vision-app's eventual "transport=rtsp"
            // wiring) must give the RX side a much shorter RTSP timeout than its 10s default to
            // avoid this; reproduced and confirmed fixed via a standalone harness outside this
            // module before landing this workaround. Flagged in RtspFeedTransmitter's MODULE.md
            // Gotchas for the later application-wiring task.
            StreamDescriptor rxDescriptor =
                    new StreamDescriptor(descriptor.protocol(), descriptor.uri(), Map.of("timeout", "2000000"));

            // Give the transmitter's background thread time to establish its own RTSP push
            // session with mediamtx (grabber.start() on the local file plus the recorder's
            // ANNOUNCE/SETUP/RECORD handshake) before the RX side's first connect attempt --
            // mediamtx only serves DESCRIBE for a path once a publisher is actively pushing to
            // it. Observed to complete in well under this margin in practice.
            Thread.sleep(1000);

            FfmpegVideoSource rxSource = new FfmpegVideoSource();
            StreamId streamId = StreamId.random();
            List<VideoFrame> collected = Collections.synchronizedList(new ArrayList<>());
            Object monitor = new Object();

            try {
                awaitRxConnected(rxSource, streamId, rxDescriptor, collected, monitor, Duration.ofSeconds(60));

                // Keep waiting on the SAME subscription until more frames have been received than
                // the source file itself contains -- proof the transmitted feed kept going past a
                // single pass through the file (i.e. it looped) rather than stopping at its EOF.
                long loopDeadline = System.currentTimeMillis() + Duration.ofSeconds(45).toMillis();
                int size;
                do {
                    synchronized (monitor) {
                        if (collected.size() <= frameCount) {
                            monitor.wait(500);
                        }
                    }
                    size = collected.size();
                } while (size <= frameCount && System.currentTimeMillis() < loopDeadline);

                assertTrue(size > frameCount,
                        "expected more than " + frameCount + " frames once the transmitted feed looped past the "
                                + "source file's own duration, got " + size);
            } finally {
                rxSource.close(streamId);
                transmitter.stop(feedId);
            }
        } finally {
            removeContainerQuietly(containerName);
        }
    }

    /**
     * mediamtx only serves an RTSP path once a publisher is actively pushing
     * to it, so opening the descriptor from the RX side only succeeds once
     * that has happened; a fresh {@link FfmpegVideoSource#open} is retried
     * whenever a connection attempt genuinely fails (errors or completes),
     * rather than assumed to succeed on the first try.
     *
     * <p>Deliberately does <b>not</b> reconnect merely because no frame has
     * arrived yet within some short window: {@link RtspFeedTransmitter}'s
     * encoder GOP (see its class javadoc) only emits a keyframe every couple
     * of seconds, so a connection that lands mid-GOP logs transient decode
     * warnings (missing reference picture) until the next keyframe arrives
     * and self-heals -- reconnecting impatiently would just restart that
     * same wait against a new, differently-phased mid-GOP landing, risking
     * never lining up with a keyframe at all.
     */
    private static void awaitRxConnected(FfmpegVideoSource rxSource, StreamId streamId, StreamDescriptor descriptor,
            List<VideoFrame> collected, Object monitor, Duration connectTimeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + connectTimeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            AtomicBoolean attemptErrored = new AtomicBoolean(false);
            AtomicBoolean attemptCompleted = new AtomicBoolean(false);
            Flow.Publisher<VideoFrame> publisher = rxSource.open(streamId, descriptor);
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
                    attemptErrored.set(true);
                    synchronized (monitor) {
                        monitor.notifyAll();
                    }
                }

                @Override
                public void onComplete() {
                    attemptCompleted.set(true);
                    synchronized (monitor) {
                        monitor.notifyAll();
                    }
                }
            });

            // Stay on this SAME subscription for the full remaining budget -- only a genuine
            // error/completion (not merely "no frame yet") is worth abandoning it for.
            synchronized (monitor) {
                while (collected.isEmpty() && !attemptErrored.get() && !attemptCompleted.get()
                        && System.currentTimeMillis() < deadline) {
                    monitor.wait(500);
                }
            }
            if (!collected.isEmpty()) {
                return; // connected: frames are flowing on this subscription
            }
            rxSource.close(streamId);
            if (System.currentTimeMillis() >= deadline) {
                break;
            }
            Thread.sleep(500);
        }
        fail("expected the RX side to start receiving frames from the transmitted RTSP feed within " + connectTimeout);
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
                "docker", "run", "-d", "--rm", "--name", name, "-p", "0:8554", IMAGE);
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

    private static Path createTestVideo(Path dir, int width, int height, int frameCount, double fps)
            throws Exception {
        Path file = dir.resolve("rtsp-feed-tx-it-" + frameCount + "-" + fps + ".mp4");
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

    private record ProcessResult(int exitCode, String output) {
    }
}
