package com.drones.vision.adapter.mjpeg;

import com.drones.vision.domain.model.PixelFormat;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MjpegVideoSourceTest {

    private static final long AWAIT_SECONDS = 20;
    private static final String BOUNDARY = "testboundary";

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void supportsMjpegProtocolWithHttpOrHttpsUriOnly() {
        MjpegVideoSource source = new MjpegVideoSource();

        assertTrue(source.supports(new StreamDescriptor("mjpeg", URI.create("http://cam/stream"), Map.of())));
        assertTrue(source.supports(new StreamDescriptor("mjpeg", URI.create("https://cam/stream"), Map.of())));
        assertFalse(source.supports(new StreamDescriptor("mjpeg", URI.create("rtsp://cam/stream"), Map.of())),
                "mjpeg protocol must be rejected for a non-http(s) URI");
        assertFalse(source.supports(new StreamDescriptor("rtsp", URI.create("http://cam/stream"), Map.of())),
                "a non-mjpeg protocol must be rejected regardless of the URI scheme");
        assertFalse(source.supports(null));
    }

    @Test
    void openRejectsAnUnsupportedDescriptor() {
        MjpegVideoSource source = new MjpegVideoSource();
        StreamDescriptor descriptor = new StreamDescriptor("sim", URI.create("sim://cam"), Map.of());

        assertThrows(IllegalArgumentException.class, () -> source.open(StreamId.random(), descriptor));
    }

    @Test
    void closeOnAnUnknownOrUnopenedStreamIsANoop() {
        MjpegVideoSource source = new MjpegVideoSource();

        assertDoesNotThrow(() -> source.close(StreamId.random()));
    }

    @Test
    void openProducesJpegFramesWithCorrectDimensionsFromACannedMultipartServer() throws Exception {
        int width = 32;
        int height = 24;
        server = startCannedServer(width, height);
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/stream");
        StreamDescriptor descriptor = new StreamDescriptor("mjpeg", uri, Map.of());

        MjpegVideoSource source = new MjpegVideoSource();
        StreamId streamId = StreamId.random();
        List<VideoFrame> collected = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch atLeastThree = new CountDownLatch(3);
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
                atLeastThree.countDown();
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
            assertTrue(atLeastThree.await(AWAIT_SECONDS, TimeUnit.SECONDS),
                    "expected at least 3 frames within " + AWAIT_SECONDS + "s");
            assertNull(errorRef.get(), "a well-formed canned MJPEG server must not error the RX side");

            for (VideoFrame frame : List.copyOf(collected)) {
                assertEquals(streamId, frame.streamId());
                assertEquals(PixelFormat.JPEG, frame.format());
                assertEquals(width, frame.width());
                assertEquals(height, frame.height());
            }

            long previousSequence = -1;
            for (VideoFrame frame : List.copyOf(collected)) {
                assertTrue(frame.sequence() > previousSequence, "sequence must be strictly increasing");
                previousSequence = frame.sequence();
            }
        } finally {
            assertDoesNotThrow(() -> source.close(streamId));
            assertDoesNotThrow(() -> source.close(streamId), "close() must be idempotent");
        }
    }

    @Test
    void openSignalsOnErrorWhenTheServerReturnsANonSuccessStatus() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.setExecutor(daemonExecutor());
        server.start();

        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/missing");
        StreamDescriptor descriptor = new StreamDescriptor("mjpeg", uri, Map.of());

        MjpegVideoSource source = new MjpegVideoSource();
        StreamId streamId = StreamId.random();
        CountDownLatch terminal = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        Flow.Publisher<VideoFrame> publisher = source.open(streamId, descriptor);
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
                terminal.countDown();
            }

            @Override
            public void onComplete() {
                terminal.countDown();
            }
        });

        try {
            assertTrue(terminal.await(AWAIT_SECONDS, TimeUnit.SECONDS), "expected a terminal signal within " + AWAIT_SECONDS + "s");
            assertNotNull(errorRef.get(), "a non-2xx response must surface as onError, not onComplete");
        } finally {
            assertDoesNotThrow(() -> source.close(streamId));
        }
    }

    @Test
    void openSignalsOnErrorWhenTheHostIsUnreachable() throws Exception {
        // 192.0.2.0/24 is TEST-NET-1 (RFC 5737): reserved for documentation, never routable, so a
        // connect attempt reliably times out rather than racing a real (possibly still-listening)
        // socket -- unlike binding-then-stopping a local HttpServer, which was observed to leave a
        // brief window where a new connection is still accepted into the OS backlog even after
        // stop(0) returns, making "connection refused" non-deterministic to simulate that way.
        StreamDescriptor descriptor = new StreamDescriptor("mjpeg",
                URI.create("http://192.0.2.1:9999/stream"), Map.of("timeout", "1000"));

        MjpegVideoSource source = new MjpegVideoSource();
        StreamId streamId = StreamId.random();
        CountDownLatch terminal = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        Flow.Publisher<VideoFrame> publisher = source.open(streamId, descriptor);
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
                terminal.countDown();
            }

            @Override
            public void onComplete() {
                terminal.countDown();
            }
        });

        try {
            assertTrue(terminal.await(AWAIT_SECONDS, TimeUnit.SECONDS), "expected a terminal signal within " + AWAIT_SECONDS + "s");
            assertNotNull(errorRef.get(), "an unreachable host must surface as onError");
        } finally {
            assertDoesNotThrow(() -> source.close(streamId));
        }
    }

    private static HttpServer startCannedServer(int width, int height) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stream", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "multipart/x-mixed-replace; boundary=" + BOUNDARY);
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            try {
                // Loops "forever" (until the client disconnects) -- a real MJPEG camera never
                // stops on its own, and this exercises MjpegVideoSource#close() actually tearing
                // down a genuinely still-open connection rather than merely draining a finite one.
                while (true) {
                    byte[] jpeg = renderJpeg(width, height);
                    String header = "--" + BOUNDARY + "\r\n"
                            + "Content-Type: image/jpeg\r\n"
                            + "Content-Length: " + jpeg.length + "\r\n\r\n";
                    out.write(header.getBytes(StandardCharsets.US_ASCII));
                    out.write(jpeg);
                    out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    Thread.sleep(20);
                }
            } catch (Exception e) {
                // Client disconnected (test tore its subscription down) -- expected, not fatal.
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(daemonExecutor());
        server.start();
        return server;
    }

    // Deliberately NOT prefixed "mjpeg-": MjpegVideoSource reserves that prefix for its own
    // per-open runtime thread, and other test classes in this module assert no "mjpeg-" thread
    // is left running -- this canned test server's own thread must never be mistaken for one.
    private static java.util.concurrent.ExecutorService daemonExecutor() {
        return Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "test-canned-mjpeg-http-server");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static byte[] renderJpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.GREEN);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
