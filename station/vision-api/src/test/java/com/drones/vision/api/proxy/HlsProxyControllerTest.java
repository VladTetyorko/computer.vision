package com.drones.vision.api.proxy;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises {@link HlsProxyController} against a real local upstream — a
 * plain {@link com.sun.net.httpserver.HttpServer} standing in for mediamtx —
 * rather than mocking {@code java.net.http.HttpClient}, since the behavior
 * under test (redirect-following, cookie relay, raw path pass-through) is
 * exactly the wire-level behavior a mock would have to reimplement anyway.
 */
class HlsProxyControllerTest {

    private HttpServer upstream;

    @AfterEach
    void tearDown() {
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    @Test
    void playlistFetchPassesThroughBodyAndContentTypeAndForwardsRawPath() throws Exception {
        byte[] body = "#EXTM3U\n#EXT-X-VERSION:3\n".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> receivedPath = new AtomicReference<>();
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/", exchange -> {
            receivedPath.set(exchange.getRequestURI().toString());
            exchange.getResponseHeaders().add("Content-Type", "application/vnd.apple.mpegurl");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        MockMvc mockMvc = mockMvcFor(upstream);

        mockMvc.perform(get("/hls/{streamId}/index.m3u8", "stream-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/vnd.apple.mpegurl"))
                .andExpect(content().bytes(body));

        assertEquals("/stream-1/index.m3u8", receivedPath.get());
    }

    @Test
    void rawSegmentPathIsForwardedWithoutDecodingOrReencoding() throws Exception {
        byte[] body = new byte[]{1, 2, 3, 4};
        AtomicReference<String> receivedPath = new AtomicReference<>();
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/", exchange -> {
            receivedPath.set(exchange.getRequestURI().toString());
            exchange.getResponseHeaders().add("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        MockMvc mockMvc = mockMvcFor(upstream);

        // "%20" (encoded space) must reach upstream exactly as "%20", never decoded to a
        // literal space nor double-encoded to "%2520".
        mockMvc.perform(get(new URI("/hls/stream-1/seg%20ment.mp4")))
                .andExpect(status().isOk())
                .andExpect(content().bytes(body));

        assertEquals("/stream-1/seg%20ment.mp4", receivedPath.get());
    }

    /**
     * docs/plans/done/MVP2-PLAN.md V-a proxy audit: mediamtx marks live LL-HLS media
     * playlists {@code Cache-Control: no-cache} — before this fix that
     * header was silently dropped rather than forwarded, which risked a
     * stock (pre-lowLatencyMode) hls.js polling loop getting served a stale
     * cached playlist by the browser's own HTTP cache instead of a fresh
     * one each poll.
     */
    @Test
    void cacheControlIsForwardedFromUpstreamNotAddedOrDropped() throws Exception {
        byte[] body = "#EXTM3U\n#EXT-X-VERSION:3\n".getBytes(StandardCharsets.UTF_8);
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/vnd.apple.mpegurl");
            exchange.getResponseHeaders().add("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        MockMvc mockMvc = mockMvcFor(upstream);

        mockMvc.perform(get("/hls/{streamId}/index.m3u8", "stream-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"));
    }

    /**
     * LL-HLS's blocking playlist reload protocol is entirely query-string
     * driven ({@code _HLS_msn}/{@code _HLS_part}/{@code _HLS_skip}) — the
     * proxy must forward it untouched for mediamtx's blocking-wait logic to
     * resolve the request against the right segment/part.
     */
    @Test
    void llHlsBlockingReloadQueryParametersAreForwardedUntouched() throws Exception {
        byte[] body = "#EXTM3U\n#EXT-X-VERSION:9\n".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> receivedQuery = new AtomicReference<>();
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/", exchange -> {
            receivedQuery.set(exchange.getRequestURI().getRawQuery());
            exchange.getResponseHeaders().add("Content-Type", "application/vnd.apple.mpegurl");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        MockMvc mockMvc = mockMvcFor(upstream);

        mockMvc.perform(get("/hls/{streamId}/index.m3u8?_HLS_msn=42&_HLS_part=3&_HLS_skip=YES", "stream-1"))
                .andExpect(status().isOk());

        assertEquals("_HLS_msn=42&_HLS_part=3&_HLS_skip=YES", receivedQuery.get());
    }

    @Test
    void cookieIsForwardedUpstreamAndSetCookieIsRelayedBackToTheBrowser() throws Exception {
        byte[] body = "segment-bytes".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> receivedCookie = new AtomicReference<>();
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/", exchange -> {
            receivedCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.getResponseHeaders().add("Set-Cookie", "mtx-session=abc123; Path=/");
            exchange.getResponseHeaders().add("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        MockMvc mockMvc = mockMvcFor(upstream);

        mockMvc.perform(get("/hls/{streamId}/seg1.mp4", "stream-1").header("Cookie", "browser-cookie=xyz"))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", "mtx-session=abc123; Path=/"));

        assertNotNull(receivedCookie.get(), "expected the upstream to receive a Cookie header");
        assertTrue(receivedCookie.get().contains("browser-cookie=xyz"),
                "expected the browser's Cookie header to be forwarded upstream, got: " + receivedCookie.get());
    }

    @Test
    void serverSideFollowsUpstreamRedirectAndReturnsFinalBodyWith200() throws Exception {
        byte[] finalBody = "#EXTM3U\nfinal-node-playlist\n".getBytes(StandardCharsets.UTF_8);
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/stream-1/index.m3u8", exchange -> {
            exchange.getResponseHeaders().add("Location", "/stream-1-node2/index.m3u8");
            exchange.getResponseHeaders().add("Set-Cookie", "mtx-session=pinned; Path=/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        upstream.createContext("/stream-1-node2/index.m3u8", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/vnd.apple.mpegurl");
            exchange.sendResponseHeaders(200, finalBody.length);
            exchange.getResponseBody().write(finalBody);
            exchange.close();
        });
        upstream.start();
        MockMvc mockMvc = mockMvcFor(upstream);

        mockMvc.perform(get("/hls/{streamId}/index.m3u8", "stream-1"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(finalBody))
                .andExpect(header().string("Set-Cookie", "mtx-session=pinned; Path=/"));
    }

    /**
     * The wave's acceptance gate (docs/plans/active/SCALE-100-PLAN.md §5 S1): the per-request
     * {@code HttpClient} was replaced with one shared client that has no {@link
     * java.net.CookieHandler}. A shared {@code CookieHandler} would remember whichever cookie it
     * last saw for the upstream host and hand it to the *next* request through that same client --
     * exactly the leak this test rules out, using one shared controller instance (one shared
     * client) across two "viewers" with different cookies.
     */
    @Test
    void sharedClientDoesNotLeakOneViewersCookieToAnother() throws Exception {
        byte[] body = "segment-bytes".getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> cookieSeenForStreamA = new AtomicReference<>();
        AtomicReference<String> cookieSeenForStreamB = new AtomicReference<>();
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/stream-a/seg.mp4", exchange -> {
            cookieSeenForStreamA.set(exchange.getRequestHeaders().getFirst("Cookie"));
            // Simulates mediamtx pinning viewer A to a session -- this Set-Cookie must never be
            // replayed on any *other* viewer's request through the shared client.
            exchange.getResponseHeaders().add("Set-Cookie", "mtx-session=alice-session; Path=/");
            exchange.getResponseHeaders().add("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.createContext("/stream-b/seg.mp4", exchange -> {
            cookieSeenForStreamB.set(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.getResponseHeaders().add("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        // One shared controller instance == one shared HttpClient, exactly like the real bean.
        MockMvc mockMvc = mockMvcFor(upstream);

        mockMvc.perform(get("/hls/{streamId}/seg.mp4", "stream-a").header("Cookie", "viewer=alice"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/hls/{streamId}/seg.mp4", "stream-b").header("Cookie", "viewer=bob"))
                .andExpect(status().isOk());

        assertNotNull(cookieSeenForStreamA.get());
        assertTrue(cookieSeenForStreamA.get().contains("viewer=alice"),
                "viewer A's own cookie must reach upstream, got: " + cookieSeenForStreamA.get());

        assertNotNull(cookieSeenForStreamB.get());
        assertTrue(cookieSeenForStreamB.get().contains("viewer=bob"),
                "viewer B's own cookie must reach upstream, got: " + cookieSeenForStreamB.get());
        assertFalse(cookieSeenForStreamB.get().contains("viewer=alice"),
                "viewer A's cookie must never reach viewer B's upstream request, got: " + cookieSeenForStreamB.get());
        assertFalse(cookieSeenForStreamB.get().contains("mtx-session=alice-session"),
                "the session cookie mediamtx set for viewer A must never reach viewer B's upstream request, got: "
                        + cookieSeenForStreamB.get());
    }

    /**
     * Byte-range requests are dropped today (harmless for live HLS, which never sends one) but
     * wrong for the recording playback path (docs/plans/active/SCALE-100-PLAN.md §5 S1, task 3).
     */
    @Test
    void rangeHeaderIsForwardedUpstreamAndContentRangeAcceptRangesArePassedBack() throws Exception {
        byte[] partialBody = new byte[]{5, 6, 7, 8};
        AtomicReference<String> receivedRange = new AtomicReference<>();
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/", exchange -> {
            receivedRange.set(exchange.getRequestHeaders().getFirst("Range"));
            exchange.getResponseHeaders().add("Content-Type", "video/mp4");
            exchange.getResponseHeaders().add("Content-Range", "bytes 4-7/20");
            exchange.getResponseHeaders().add("Accept-Ranges", "bytes");
            exchange.sendResponseHeaders(206, partialBody.length);
            exchange.getResponseBody().write(partialBody);
            exchange.close();
        });
        upstream.start();
        MockMvc mockMvc = mockMvcFor(upstream);

        mockMvc.perform(get("/hls/{streamId}/recording.mp4", "stream-1").header("Range", "bytes=4-7"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 4-7/20"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(content().bytes(partialBody));

        assertEquals("bytes=4-7", receivedRange.get());
    }

    @Test
    void upstreamConnectionFailureReturns502WithStandardErrorJson() throws Exception {
        // Port 1 refuses connections immediately on Linux without a privileged process
        // listening there, so this fails fast without relying on a connect-timeout.
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new HlsProxyController(URI.create("http://127.0.0.1:1")))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mockMvc.perform(get("/hls/{streamId}/index.m3u8", "stream-1"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("BAD_GATEWAY"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void unmappedPathUnderHlsWithoutStreamIdReturns404() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new HlsProxyController(URI.create("http://127.0.0.1:1")))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mockMvc.perform(get("/hls")).andExpect(status().isNotFound());
        mockMvc.perform(get("/hls/")).andExpect(status().isNotFound());
    }

    private static MockMvc mockMvcFor(HttpServer server) {
        URI base = URI.create("http://localhost:" + server.getAddress().getPort());
        return MockMvcBuilders.standaloneSetup(new HlsProxyController(base))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }
}
