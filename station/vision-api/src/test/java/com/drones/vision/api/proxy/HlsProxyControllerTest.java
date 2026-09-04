package com.drones.vision.api.proxy;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.security.PrincipalResolver;
import com.drones.vision.api.security.StreamAccess;
import com.drones.vision.api.support.VisionApiProperties;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.map.application.MapAccessPolicy;
import com.drones.vision.perception.application.stream.ActiveStream;
import com.drones.vision.perception.application.stream.StreamService;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.platform.Authority;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Identity;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
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
 *
 * <p>Every wire-mechanics test below builds its controller with {@link #openStreamAccess()} — a real
 * {@link StreamAccess} backed by a {@link StreamService} stub reporting no running streams at all, so
 * {@link StreamAccess#requireVisible(StreamId)} is a no-op for whatever placeholder {@code streamId}
 * path segment each test uses ("stream-1" etc. — never a real {@link StreamId}), exactly mirroring
 * production's own "not currently running" no-op (see {@link HlsProxyController}'s class javadoc).
 * The scope gate itself is exercised separately, below, against a stream {@link StreamService}
 * actually reports as running.
 */
class HlsProxyControllerTest {

    private HttpServer upstream;

    @AfterEach
    void tearDown() {
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    /**
     * A real {@link StreamAccess} ({@code final}, so not mocked) whose {@link StreamService} stub
     * reports no running streams — {@link StreamAccess#requireVisible(StreamId)} is then a
     * documented no-op for any {@code streamId}, matching every wire-mechanics test's placeholder
     * path segments.
     */
    private static StreamAccess openStreamAccess() {
        StreamService streamService = mock(StreamService.class);
        when(streamService.streams()).thenReturn(List.of());
        CurrentUser currentUser = new CurrentUser(new Ownership(UserId.random(), GroupId.random()));
        return new StreamAccess(streamService, mock(AssetRepositoryPort.class), currentUser);
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

    /**
     * The failure this guards is silent and total: an http viewer that cannot store mediamtx's
     * hardened duplicate never sends {@code hlsSession} back, and every media-playlist request after
     * the first answers 401. It hid in local development because browsers treat {@code
     * http://localhost} as a secure context and keep {@code Secure} cookies there.
     */
    @Test
    void secureOnlyCookieAttributesAreStrippedForAPlainHttpViewerAndKeptForAnHttpsOne() throws Exception {
        byte[] body = "#EXTM3U\n".getBytes(StandardCharsets.UTF_8);
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/", exchange -> {
            // Exactly what mediamtx 1.19 emits: the same cookie twice, once bare and once hardened.
            exchange.getResponseHeaders().add("Set-Cookie", "hlsSession=s1");
            exchange.getResponseHeaders().add("Set-Cookie",
                    "hlsSession=s1; HttpOnly; Secure; SameSite=None; Partitioned");
            exchange.getResponseHeaders().add("Content-Type", "application/vnd.apple.mpegurl");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        MockMvc mockMvc = mockMvcFor(upstream);

        mockMvc.perform(get("/hls/{streamId}/index.m3u8", "stream-1"))
                .andExpect(status().isOk())
                .andExpect(header().stringValues("Set-Cookie", "hlsSession=s1", "hlsSession=s1; HttpOnly"));

        // Attribute order is the JDK client's normalisation of what the upstream sent, not this
        // controller's doing -- an https viewer gets the hardened cookie through untouched.
        mockMvc.perform(get("/hls/{streamId}/index.m3u8", "stream-1").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().stringValues("Set-Cookie", "hlsSession=s1",
                        "hlsSession=s1; Secure; HttpOnly; Partitioned; SameSite=None"));
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
     * The wave's acceptance gate (docs/plans/done/SCALE-100-PLAN.md §5 S1): the per-request
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
     * wrong for the recording playback path (docs/plans/done/SCALE-100-PLAN.md §5 S1, task 3).
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

    /**
     * docs/plans/active/ASSET-FLOWS-PLAN.md &sect;2 "S6 auth model": once mediamtx gates {@code
     * read} on every path behind a viewer account, this controller must authenticate its own
     * upstream fetch or every proxied request would 401. Asserts the actual wire-level header, not
     * just that the controller was constructed with credentials — the same "prove the mechanism, not
     * just the config plumbing" standard {@link #configuredMaxRedirectHopsBoundsTheHandFollowedRedirectLoop}
     * already applies to {@code maxRedirectHops}.
     */
    @Test
    void configuredMediamtxCredentialsAreSentAsAnUpstreamAuthorizationHeader() throws Exception {
        AtomicReference<String> receivedAuthorization = new AtomicReference<>();
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/", exchange -> {
            receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "#EXTM3U".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/vnd.apple.mpegurl");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        URI base = URI.create("http://localhost:" + upstream.getAddress().getPort());
        VisionApiProperties.HlsProxy authenticated = new VisionApiProperties.HlsProxy(
                VisionApiProperties.HlsProxy.defaults().connectTimeout(),
                VisionApiProperties.HlsProxy.defaults().requestTimeout(),
                VisionApiProperties.HlsProxy.defaults().errorBodyPreviewMaxChars(),
                VisionApiProperties.HlsProxy.defaults().maxRedirectHops(), "vision-viewer", "change-me");
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new HlsProxyController(base, authenticated, openStreamAccess()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mockMvc.perform(get("/hls/{streamId}/index.m3u8", "stream-1")).andExpect(status().isOk());

        assertEquals("Basic dmlzaW9uLXZpZXdlcjpjaGFuZ2UtbWU=", receivedAuthorization.get(),
                "expected Basic base64(vision-viewer:change-me), got: " + receivedAuthorization.get());
    }

    /**
     * Same request as {@link #playlistFetchPassesThroughBodyAndContentTypeAndForwardsRawPath}, but
     * against {@link #mockMvcFor} — the default no-credentials controller every other wire-mechanics
     * test in this class already uses — proving the new header is opt-in, not sent unconditionally.
     */
    @Test
    void noAuthorizationHeaderIsSentWhenNoMediamtxCredentialIsConfigured() throws Exception {
        AtomicReference<String> receivedAuthorization = new AtomicReference<>();
        AtomicReference<Boolean> headerPresent = new AtomicReference<>();
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/", exchange -> {
            headerPresent.set(exchange.getRequestHeaders().containsKey("Authorization"));
            receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().add("Content-Type", "application/vnd.apple.mpegurl");
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        upstream.start();
        MockMvc mockMvc = mockMvcFor(upstream);

        mockMvc.perform(get("/hls/{streamId}/index.m3u8", "stream-1")).andExpect(status().isOk());

        assertFalse(Boolean.TRUE.equals(headerPresent.get()),
                "expected no Authorization header, got: " + receivedAuthorization.get());
    }

    /**
     * docs/plans/done/SCALE-100-PLAN.md §5 S7: {@code maxRedirectHops} is no longer the private
     * {@code static final} constant it used to be — it comes from the {@link
     * VisionApiProperties.HlsProxy} passed to the {@code @Autowired} constructor. This proves that
     * value is actually enforced, not just stored: an upstream that redirects forever hits the
     * *configured* bound (1 hop here, not the default 5) and the controller reports 502 exactly one
     * hop sooner than {@link #serverSideFollowsUpstreamRedirectAndReturnsFinalBodyWith200} shows a
     * single real hop succeeding.
     */
    @Test
    void configuredMaxRedirectHopsBoundsTheHandFollowedRedirectLoop() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Location", "/stream-1/index.m3u8");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        upstream.start();
        URI base = URI.create("http://localhost:" + upstream.getAddress().getPort());
        VisionApiProperties.HlsProxy oneHop = new VisionApiProperties.HlsProxy(
                VisionApiProperties.HlsProxy.defaults().connectTimeout(),
                VisionApiProperties.HlsProxy.defaults().requestTimeout(),
                VisionApiProperties.HlsProxy.defaults().errorBodyPreviewMaxChars(), 1, null, null);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new HlsProxyController(base, oneHop, openStreamAccess()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mockMvc.perform(get("/hls/{streamId}/index.m3u8", "stream-1"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("BAD_GATEWAY"));
    }

    @Test
    void upstreamConnectionFailureReturns502WithStandardErrorJson() throws Exception {
        // Port 1 refuses connections immediately on Linux without a privileged process
        // listening there, so this fails fast without relying on a connect-timeout.
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new HlsProxyController(URI.create("http://127.0.0.1:1"), openStreamAccess()))
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
                .standaloneSetup(new HlsProxyController(URI.create("http://127.0.0.1:1"), openStreamAccess()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mockMvc.perform(get("/hls")).andExpect(status().isNotFound());
        mockMvc.perform(get("/hls/")).andExpect(status().isNotFound());
    }

    /**
     * The wave's own acceptance case (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R7, finding
     * A2): a stream {@link StreamService} reports as genuinely running, on a device belonging to an
     * asset the caller's {@link VisibilityScope} does not reach, must 404 <em>before</em> the upstream
     * is ever contacted — not proxy the video through. No {@link #upstream} server is even started
     * here, so a passing test proves the upstream was never touched, not merely that its response was
     * discarded.
     */
    @Test
    void proxyReturns404ForARunningStreamOnADeviceOutsideTheCallersScopeWithoutTouchingUpstream() throws Exception {
        DeviceId deviceId = DeviceId.random();
        StreamId streamId = StreamId.random();
        AssetId visibleAssetId = AssetId.random();
        Asset foreignAsset = Asset.register(AssetId.random(), "someone else's drone", new CategoryId("drone"),
                new Ownership(UserId.random(), GroupId.random()), Set.of(deviceId), Map.of(), Identity.NONE,
                Custody.NONE);

        StreamService streamService = mock(StreamService.class);
        when(streamService.streams())
                .thenReturn(List.of(new ActiveStream(streamId, deviceId, Instant.now())));
        AssetRepositoryPort assetRepositoryPort = mock(AssetRepositoryPort.class);
        when(assetRepositoryPort.findByDeviceId(deviceId)).thenReturn(Optional.of(foreignAsset));

        CurrentUser pilotScopedElsewhere = currentUserWithAssignedAssets(Set.of(visibleAssetId));
        StreamAccess streamAccess = new StreamAccess(streamService, assetRepositoryPort, pilotScopedElsewhere);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new HlsProxyController(URI.create("http://127.0.0.1:1"), streamAccess))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mockMvc.perform(get("/hls/{streamId}/index.m3u8", streamId.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    /** The visible-counterpart of the test above: the caller's own assigned asset still streams through. */
    @Test
    void proxyStillServesARunningStreamOnTheCallersOwnAssignedAsset() throws Exception {
        byte[] body = "#EXTM3U\n".getBytes(StandardCharsets.UTF_8);
        DeviceId deviceId = DeviceId.random();
        StreamId streamId = StreamId.random();
        AssetId ownedAssetId = AssetId.random();
        Asset ownedAsset = Asset.register(ownedAssetId, "my drone", new CategoryId("drone"),
                new Ownership(UserId.random(), GroupId.random()), Set.of(deviceId), Map.of(), Identity.NONE,
                Custody.NONE);

        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/vnd.apple.mpegurl");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();

        StreamService streamService = mock(StreamService.class);
        when(streamService.streams())
                .thenReturn(List.of(new ActiveStream(streamId, deviceId, Instant.now())));
        AssetRepositoryPort assetRepositoryPort = mock(AssetRepositoryPort.class);
        when(assetRepositoryPort.findByDeviceId(deviceId)).thenReturn(Optional.of(ownedAsset));

        CurrentUser pilotScopedToOwnAsset = currentUserWithAssignedAssets(Set.of(ownedAssetId));
        StreamAccess streamAccess = new StreamAccess(streamService, assetRepositoryPort, pilotScopedToOwnAsset);
        URI base = URI.create("http://localhost:" + upstream.getAddress().getPort());
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new HlsProxyController(base, streamAccess))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mockMvc.perform(get("/hls/{streamId}/index.m3u8", streamId.value()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(body));
    }

    /**
     * A {@link CurrentUser} with a PILOT-shaped {@link VisibilityScope#assignedAssets(Set)} scope —
     * {@link CurrentUser#CurrentUser(Ownership)}'s convenience constructor always answers {@link
     * VisibilityScope#unbounded()}, which cannot exercise a real 404, so this builds a
     * {@link PrincipalResolver} by hand instead (the same idiom {@code StreamControllerTest} uses).
     * {@link PrincipalResolver#viewer()} is never called by {@link HlsProxyController}/{@link
     * StreamAccess}, so it throws rather than fake a map viewer no test here needs.
     */
    private static CurrentUser currentUserWithAssignedAssets(Set<AssetId> assignedAssets) {
        Ownership ownership = new Ownership(UserId.random(), GroupId.random());
        return new CurrentUser(new PrincipalResolver() {
            @Override
            public UserId userId() {
                return ownership.ownerId();
            }

            @Override
            public Ownership ownership() {
                return ownership;
            }

            @Override
            public VisibilityScope scope() {
                return VisibilityScope.assignedAssets(assignedAssets);
            }

            @Override
            public MapAccessPolicy.Viewer viewer() {
                throw new UnsupportedOperationException("HlsProxyController never calls viewer()");
            }

            @Override
            public Role role() {
                throw new UnsupportedOperationException("HlsProxyController never calls role()");
            }

            @Override
            public Authority authority() {
                throw new UnsupportedOperationException("HlsProxyController never calls authority()");
            }
        });
    }

    private static MockMvc mockMvcFor(HttpServer server) {
        URI base = URI.create("http://localhost:" + server.getAddress().getPort());
        return MockMvcBuilders.standaloneSetup(new HlsProxyController(base, openStreamAccess()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }
}
