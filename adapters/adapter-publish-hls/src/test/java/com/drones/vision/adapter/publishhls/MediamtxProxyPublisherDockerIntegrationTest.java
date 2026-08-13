package com.drones.vision.adapter.publishhls;

import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.PixelFormat;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.domain.port.out.StreamPublisherPort;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end check of {@link MediamtxProxyPublisher} and {@link MediamtxLiveFrameGrabber} against a
 * real <a href="https://github.com/bluenviron/mediamtx">mediamtx</a> 1.19.3 container: start → the
 * mediamtx path exists with the camera as its source → a live frame is grabbable → stop → the path is
 * gone. Runs the container via the {@code docker} CLI directly, same idiom as {@link
 * MediamtxDockerIntegrationTest} — no Testcontainers dependency.
 *
 * <h2>The Control API auth correction (docs/conclusions/CV-PULL-SPIKE.md &sect;5)</h2>
 * mediamtx's default config 401s every Control API call from anything but {@code 127.0.0.1}/{@code
 * ::1} — which a docker-published port is not. Wave M0's spike (whose config lives at {@code
 * cv-service/spikes/pull/results/mediamtx-spike.yml}, read as a reference, not modified or mounted
 * directly by this test) found the fix: mount a {@code mediamtx.yml} that widens the {@code api}
 * user's {@code ips} to {@code []}. This test writes that same fix to a throwaway temp file per run
 * (self-contained — it does not depend on the spike directory's path or survival) and mounts it, so
 * this is the one test in this module that actually authenticates against the Control API rather than
 * exercising a path unaffected by it (publish/read, which mediamtx never gated).
 */
@EnabledIf(value = "dockerAvailable", disabledReason = "docker is not available in this environment")
class MediamtxProxyPublisherDockerIntegrationTest {

    private static final String IMAGE = "bluenviron/mediamtx:1.19.3";

    /**
     * The image's own default config plus the two widened-auth overrides M0 verified fix the 401
     * (docs/conclusions/CV-PULL-SPIKE.md &sect;5) — mirrors {@code
     * cv-service/spikes/pull/results/mediamtx-spike.yml} without depending on that file's path.
     */
    private static final String MEDIAMTX_CONFIG = """
            api: yes
            apiAddress: :9997
            authInternalUsers:
              - user: any
                pass:
                ips: []
                permissions:
                  - action: publish
                  - action: read
                  - action: playback
                  - action: api
                  - action: metrics
                  - action: pprof
            paths:
              all_others:
            """;

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void proxiedPathIsCreatedReadableAndTornDownAgainstARealMediamtx() throws Exception {
        String containerName = "vision-publish-hls-proxy-it-" + UUID.randomUUID();
        Path configFile = Files.createTempFile("mediamtx-proxy-it-", ".yml");
        Files.writeString(configFile, MEDIAMTX_CONFIG);
        AtomicBoolean keepPumping = new AtomicBoolean(true);
        Thread cameraPump = null;
        try {
            startContainer(containerName, configFile);
            int rtspPort = resolveHostPort(containerName, "8554/tcp");
            int apiPort = resolveHostPort(containerName, "9997/tcp");
            awaitTcpPortOpen(rtspPort, Duration.ofSeconds(10));
            awaitTcpPortOpen(apiPort, Duration.ofSeconds(10));
            URI rtspBase = URI.create("rtsp://localhost:" + rtspPort);
            URI apiBase = URI.create("http://localhost:" + apiPort);

            // Stand-in "camera": a synthetic feed published, via the existing (already-tested)
            // MediamtxStreamPublisher, to its own path on this same mediamtx instance. mediamtx
            // treats an RTSP source as just a URL to dial -- it does not care that the URL happens
            // to name another path on itself -- so this needs no second server process or ffmpeg
            // CLI dependency.
            StreamId cameraStreamId = StreamId.random();
            StreamPublisherPort cameraPublisher = new MediamtxStreamPublisher(
                    rtspBase, URI.create("http://localhost:8888"), URI.create("http://localhost:8889"), null);
            Device cameraDevice = new Device(DeviceId.random(), "synthetic-camera", Set.of(Capability.VIDEO),
                    new StreamDescriptor("sim", URI.create("sim://proxy-it-camera"), Map.of()));
            cameraPublisher.streamStarted(cameraStreamId, cameraDevice);
            cameraPump = startFramePump(cameraPublisher, cameraStreamId, keepPumping);
            // mediamtx dials this URL from INSIDE its own container, not from the host -- the
            // host-mapped rtspPort above is meaningless there. Since the "camera" is really another
            // path on this same mediamtx instance, its address from mediamtx's own point of view is
            // the container-internal RTSP port (always 8554), reached via the container's own
            // loopback (mediamtx-as-client dialing mediamtx-as-server in the same network namespace).
            URI cameraUrl = URI.create("rtsp://127.0.0.1:8554/" + cameraStreamId.value());

            MediamtxProxyPublisher proxyPublisher = new MediamtxProxyPublisher(apiBase,
                    URI.create("http://localhost:8888"), URI.create("http://localhost:8889"), null,
                    MediamtxProxySettings.defaults());
            StreamId proxiedStreamId = StreamId.random();
            Device proxiedDevice = new Device(DeviceId.random(), "proxied-camera", Set.of(Capability.VIDEO),
                    new StreamDescriptor("rtsp", cameraUrl, Map.of()));

            assertDoesNotThrow(() -> proxyPublisher.streamStarted(proxiedStreamId, proxiedDevice),
                    "streamStarted must succeed once mediamtx reports the proxied path ready");

            PathStatus status = fetchPathStatus(apiBase, proxiedStreamId.value().toString());
            assertTrue(status.ready(), "expected the proxied path to be reported ready after streamStarted returned");
            assertEquals("rtspSource", status.sourceType());

            // The point of MediamtxLiveFrameGrabber: nothing in this JVM decoded the proxied
            // stream's video (MediamtxProxyPublisher#publish is a no-op) -- this is the only way a
            // caller in proxy mode ever sees a real, un-annotated pixel from it.
            MediamtxLiveFrameGrabber liveFrameGrabber = new MediamtxLiveFrameGrabber(rtspBase);
            Optional<VideoFrame> liveFrame = pollForLiveFrame(liveFrameGrabber, proxiedStreamId, Duration.ofSeconds(30));
            VideoFrame frame = liveFrame.orElseThrow(() -> new AssertionError("expected a live frame from the proxied path"));
            assertEquals(proxiedStreamId, frame.streamId());
            assertEquals(PixelFormat.BGR24, frame.format());
            assertTrue(frame.width() > 0 && frame.height() > 0,
                    "expected positive dimensions, got " + frame.width() + "x" + frame.height());

            proxyPublisher.streamEnded(proxiedStreamId);
            assertEquals(404, fetchPathStatusCode(apiBase, proxiedStreamId.value().toString()),
                    "expected the proxied path to be gone after streamEnded");
        } finally {
            keepPumping.set(false);
            if (cameraPump != null) {
                cameraPump.join(Duration.ofSeconds(5).toMillis());
            }
            removeContainerQuietly(containerName);
            Files.deleteIfExists(configFile);
        }
    }

    private static Optional<VideoFrame> pollForLiveFrame(MediamtxLiveFrameGrabber grabber, StreamId streamId,
            Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Optional<VideoFrame> result = grabber.grab(streamId);
            if (result.isPresent()) {
                return result;
            }
            Thread.sleep(500);
        }
        return Optional.empty();
    }

    private static PathStatus fetchPathStatus(URI apiBase, String pathName) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + "/v3/paths/get/" + pathName))
                .timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        boolean ready = response.body().contains("\"ready\":true");
        String sourceType = response.body().contains("\"type\":\"rtspSource\"") ? "rtspSource" : "unknown";
        return new PathStatus(ready, sourceType);
    }

    private static int fetchPathStatusCode(URI apiBase, String pathName) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + "/v3/paths/get/" + pathName))
                .timeout(Duration.ofSeconds(5)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private record PathStatus(boolean ready, String sourceType) {
    }

    /** JUnit {@code @EnabledIf} condition: true iff the {@code docker} CLI can talk to a daemon. */
    static boolean dockerAvailable() {
        try {
            return run(Duration.ofSeconds(5), "docker", "info").exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void startContainer(String name, Path configFile) throws IOException, InterruptedException {
        ProcessResult result = run(Duration.ofSeconds(90),
                "docker", "run", "-d", "--rm", "--name", name,
                "-v", configFile.toAbsolutePath() + ":/mediamtx.yml:ro",
                "-p", "0:8554", "-p", "0:9997", IMAGE);
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
        }, "proxy-it-camera-frame-pump");
        pump.setDaemon(true);
        pump.start();
        return pump;
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
