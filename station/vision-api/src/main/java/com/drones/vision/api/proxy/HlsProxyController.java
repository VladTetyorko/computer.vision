package com.drones.vision.api.proxy;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.exception.HlsUpstreamUnavailableException;
import com.drones.vision.api.live.LiveHlsAndReaderVideoDemand;
import com.drones.vision.api.security.StreamAccess;
import com.drones.vision.api.support.VisionApiProperties;
import com.drones.vision.kernel.StreamId;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Driving REST adapter that proxies HLS playback traffic through this app's
 * own HTTP origin, so browsers never talk to the mediamtx sidecar directly.
 *
 * <p>This exists because mediamtx's own HLS port collides with other
 * services commonly bound to the same well-known port on a user's machine
 * (e.g. {@code 8888}), which previously forced per-machine {@code
 * vision.publish.mediamtx.hls-base} configuration just to view a stream. By
 * proxying HLS through this app's own origin under {@code /hls/**} (see
 * {@link com.drones.vision.app.VisionPublishProperties#viewBase()} in {@code
 * vision-app}, which defaults to the app-relative {@code /hls}), the
 * mediamtx port becomes purely an internal implementation detail viewers
 * never see or configure.
 *
 * <h2>Forwarding</h2>
 * {@code GET /hls/{streamId}/**} forwards the request to {@code
 * hlsUpstreamBase + "/" + <raw remainder after "/hls/">}, preserving the
 * remainder exactly as received on the wire (via {@link
 * HttpServletRequest#getRequestURI()}, which the servlet container never
 * URL-decodes) so segment/playlist names are never decoded and re-encoded
 * in transit. A request under {@code /hls} with no further path segment
 * (e.g. {@code /hls} or {@code /hls/}) simply doesn't match this mapping and
 * falls through to Spring's normal 404 handling. The incoming {@code Range}
 * header (byte-range requests — used by the recording playback path; plain
 * live HLS never sends one) is forwarded upstream verbatim, and the
 * upstream's {@code Content-Range}/{@code Accept-Ranges}/{@code
 * Content-Length} are passed back exactly as received, alongside {@code
 * Content-Type} and {@code Cache-Control}.
 *
 * <h2>Streaming, not buffering</h2>
 * The response body is streamed straight from the upstream connection to
 * the browser ({@link HttpResponse.BodyHandlers#ofInputStream()} into an
 * {@link InputStreamResource}, which Spring's {@code
 * ResourceHttpMessageConverter} copies in fixed-size chunks) rather than
 * read fully into a {@code byte[]} first — an fMP4 segment is a few hundred
 * KB to a couple of MB, and at roughly one segment per second per viewer,
 * buffering every one of them whole in heap was the single largest
 * allocation source in the app. The only body content this controller ever
 * holds in a Java array is the diagnostic preview of a non-2xx upstream
 * response (at most {@link #errorBodyPreviewMaxChars} bytes, default 200 —
 * {@code vision.api.hls-proxy.error-body-preview-max-chars}, see {@link
 * #logProxyOutcome}) — the remainder of even an error body still streams
 * through untouched, stitched back onto the preview with a {@link
 * SequenceInputStream} so the browser still sees the whole thing.
 *
 * <h2>One shared client, no shared cookie jar</h2>
 * A single {@link HttpClient} is built once (the {@code @Autowired}
 * constructor) and reused for every proxied request, replacing the
 * previous per-request client (which allocated a selector thread and a
 * connection pool per request and never closed either). The previous
 * per-request client existed <em>because</em> of a per-request {@link
 * java.net.CookieManager}: mediamtx issues per-viewer session cookies, and
 * a {@link java.net.CookieHandler} attached to a shared client would
 * remember viewer A's cookie and hand it to viewer B's request to the same
 * upstream host — a cross-viewer session leak. This client therefore has
 * <strong>no</strong> {@code cookieHandler} at all; cookies are handled
 * entirely as request/response headers scoped to the one servlet request
 * each proxied call belongs to (see {@link #fetch}), never stored on the
 * client itself.
 *
 * <h2>Redirects, followed by hand instead of by the client</h2>
 * mediamtx pins HLS viewers to a specific internal node with a {@code
 * Set-Cookie} on an initial {@code 302} redirect. With no cookie handler on
 * the shared client, {@link HttpClient.Redirect#NORMAL} cannot be trusted
 * to carry a cookie set on the first hop's response onto the second hop's
 * request — that carry-over is exactly what a {@code CookieHandler} would
 * normally supply, and it is deliberately absent here. So this controller
 * follows redirects itself in {@link #fetch}: the client is built with
 * {@link HttpClient.Redirect#NEVER}, a bounded loop resolves each {@code
 * Location} against the previous hop's URI, and each hop's {@code
 * Set-Cookie} values are explicitly folded into the {@code Cookie} header
 * sent on the next hop ({@link #mergeCookies}) — a deliberate, local,
 * per-request replacement for what a shared cookie jar would have done
 * unsafely. Every hop's {@code Set-Cookie} is also collected, oldest hop
 * first, and relayed back to the browser so it ends up pinned the same way
 * a direct client of mediamtx would be — with the scheme rewrite {@link
 * #relayableSetCookie} describes, without which that pinning only works over
 * https.
 *
 * <h2>Failure handling</h2>
 * Only a failure to reach the upstream at all (connection refused, DNS
 * failure, timeout, a redirect chain longer than {@link #maxRedirectHops}
 * hops, default 5 — {@code vision.api.hls-proxy.max-redirect-hops}) is
 * treated as an error, surfaced as {@link
 * HlsUpstreamUnavailableException} and mapped to {@code 502} by {@link
 * ApiExceptionHandler}. A normal non-2xx response actually received from
 * upstream (e.g. {@code 404} for a not-yet-ready segment) is passed through
 * verbatim, exactly as {@link #proxy} passes through the upstream's status
 * and headers on success — this controller adds no caching headers of its
 * own, and forwards (rather than drops) whatever caching header mediamtx
 * itself sent (docs/plans/done/MVP2-PLAN.md V-a proxy audit), so mediamtx's
 * own {@code no-cache} on live LL-HLS playlists reaches the browser instead
 * of silently vanishing.
 *
 * <h2>Authorization (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R7, finding A2)</h2>
 * This endpoint proxies an asset's live video bytes, so it is gated exactly like {@code
 * StreamController}'s other stream reads: {@link #proxy} calls {@link
 * StreamAccess#requireVisible(StreamId)} before ever contacting the upstream, so a caller whose
 * {@code VisibilityScope} does not reach the stream's device gets {@code 404} (existence is never
 * revealed — the same "out-of-scope read answers 404, not 403" rule every other scoped read in this
 * module follows), not a proxied video feed. A {@code streamId} path segment that is not a valid
 * {@link StreamId} (malformed, or simply not a UUID this app minted) is treated exactly like a
 * stream {@link StreamAccess} has never heard of: nothing to check, so the request falls through to
 * the ordinary upstream fetch — production {@code streamId} path segments are always {@link
 * StreamId#value()}'s canonical UUID string (see {@code MediamtxUrls}), so this can only be reached
 * by a request this app never generated itself, which mediamtx has nothing to serve at either way.
 */
@RestController
public class HlsProxyController {

    private static final System.Logger LOG = System.getLogger(HlsProxyController.class.getName());
    private static final String HLS_PREFIX = "/hls/";
    /** Statuses this proxy follows itself, matching the set {@link HttpClient.Redirect#NORMAL} follows. */
    private static final Set<Integer> REDIRECT_STATUS_CODES = Set.of(301, 302, 303, 307, 308);

    private final URI hlsUpstreamBase;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final int errorBodyPreviewMaxChars;
    private final int maxRedirectHops;

    /**
     * Stamped on every proxied fetch so the idle policy counts an HLS viewer as demand
     * (docs/plans/done/STREAM-STATE-PLAN.md &sect;3.2). {@code null} when the policy is off, or when this
     * deployment wired no video-demand port at all — in which case this controller behaves exactly
     * as it did before, the same optional-collaborator posture {@code StreamDetectionSupport} takes.
     */
    private final LiveHlsAndReaderVideoDemand videoDemand;

    /**
     * The scope gate this controller's own javadoc ("Authorization") describes — unlike {@link
     * #videoDemand}, this is never optional: an authorization seam that could be silently skipped by
     * construction would defeat the point of adding it, so every constructor below requires it.
     */
    private final StreamAccess streamAccess;

    /**
     * Test seam (docs/plans/done/SCALE-100-PLAN.md §5 S7): defaults every tunable to {@link
     * VisionApiProperties.HlsProxy#defaults()} — today's exact pre-extraction values — so the
     * existing test suite, which constructs this controller with only its upstream {@link URI} and a
     * {@link StreamAccess}, keeps compiling and behaving identically. Package-private: production
     * wiring always supplies an explicit {@link VisionApiProperties.HlsProxy} via the constructor
     * below.
     *
     * @param hlsUpstreamBase base HTTP URL of the mediamtx sidecar's HLS egress that this
     *                        controller forwards to, e.g. {@code http://localhost:18888};
     *                        never exposed to browsers
     * @param streamAccess    the live-operations authority seam this controller's {@code proxy}
     *                        handler gates every fetch behind
     */
    HlsProxyController(URI hlsUpstreamBase, StreamAccess streamAccess) {
        this(hlsUpstreamBase, VisionApiProperties.HlsProxy.defaults(), null, streamAccess);
    }

    /**
     * The shape before {@code videoDemand} was added (docs/plans/done/STREAM-STATE-PLAN.md &sect;3.2),
     * kept as a convenience constructor defaulting it to {@code null} — no demand stamping, i.e.
     * exactly this controller's pre-S4 behaviour otherwise. Same N-1-arg idiom the domain records use
     * for the collaborators that are genuinely optional; {@code streamAccess} is not one of them (see
     * its own field javadoc), so every constructor requires it explicitly.
     *
     * @param hlsUpstreamBase base HTTP URL of the mediamtx sidecar's HLS egress
     * @param hlsProxy        this controller's timeouts and bounds
     * @param streamAccess    the live-operations authority seam this controller's {@code proxy}
     *                        handler gates every fetch behind
     */
    public HlsProxyController(URI hlsUpstreamBase, VisionApiProperties.HlsProxy hlsProxy, StreamAccess streamAccess) {
        this(hlsUpstreamBase, hlsProxy, null, streamAccess);
    }

    /**
     * {@code @Autowired} disambiguates this from the package-private test-seam constructor above —
     * Spring cannot pick one of two candidate constructors on its own (same reasoning as {@code
     * LiveUpdateRegistry}'s own production constructor).
     *
     * @param hlsUpstreamBase base HTTP URL of the mediamtx sidecar's HLS egress that this
     *                        controller forwards to; never exposed to browsers
     * @param hlsProxy        this controller's upstream {@code HttpClient} timeouts and
     *                        buffer/redirect bounds ({@code vision.api.hls-proxy.*}), supplied by
     *                        {@code vision-app}'s {@code PublishWiring#hlsProxySettings}
     * @param videoDemand     stamped on every proxied fetch so an HLS viewer counts as video demand;
     *                        {@code null} when no video-demand port is wired
     * @param streamAccess    the live-operations authority seam this controller's {@code proxy}
     *                        handler gates every fetch behind
     */
    @Autowired
    public HlsProxyController(URI hlsUpstreamBase, VisionApiProperties.HlsProxy hlsProxy,
                               LiveHlsAndReaderVideoDemand videoDemand, StreamAccess streamAccess) {
        this.videoDemand = videoDemand; // nullable -- see the field's own javadoc
        this.hlsUpstreamBase = Objects.requireNonNull(hlsUpstreamBase, "hlsUpstreamBase must not be null");
        this.streamAccess = Objects.requireNonNull(streamAccess, "streamAccess must not be null");
        Objects.requireNonNull(hlsProxy, "hlsProxy must not be null");
        this.requestTimeout = hlsProxy.requestTimeout();
        this.errorBodyPreviewMaxChars = hlsProxy.errorBodyPreviewMaxChars();
        this.maxRedirectHops = hlsProxy.maxRedirectHops();
        this.httpClient = HttpClient.newBuilder()
                // No cookieHandler: see class javadoc "One shared client, no shared cookie jar".
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(hlsProxy.connectTimeout())
                .build();
    }

    /** Releases the shared client's selector thread and pooled connections when this bean is destroyed, since (unlike the old per-request client) this one is held open for the whole app lifetime. */
    @PreDestroy
    void closeHttpClient() {
        httpClient.close();
    }

    @GetMapping("/hls/{streamId}/**")
    public ResponseEntity<InputStreamResource> proxy(@PathVariable String streamId, HttpServletRequest request) {
        requireVisibleStream(streamId);
        stampVideoDemand(streamId);
        URI upstreamUri = buildUpstreamUri(request);
        try {
            UpstreamResult result = fetch(upstreamUri, request.getHeader(HttpHeaders.COOKIE), request.getHeader(HttpHeaders.RANGE));
            HttpResponse<InputStream> upstreamResponse = result.finalResponse();
            int status = upstreamResponse.statusCode();
            InputStream upstreamBody = upstreamResponse.body();

            byte[] errorPreview = null;
            if (status < 200 || status >= 300) {
                // Only place this controller buffers anything: a bounded diagnostic preview,
                // never the whole (possibly large) error body. Re-stitched onto the rest of the
                // stream below so the browser still receives the complete body.
                errorPreview = upstreamBody.readNBytes(errorBodyPreviewMaxChars);
            }
            logProxyOutcome(request, upstreamResponse, errorPreview);

            InputStream responseBody = errorPreview == null
                    ? upstreamBody
                    : new SequenceInputStream(new ByteArrayInputStream(errorPreview), upstreamBody);

            HttpHeaders headers = responseHeaders(upstreamResponse, result.setCookies(), request.isSecure());
            return ResponseEntity.status(status).headers(headers).body(new InputStreamResource(responseBody));
        } catch (IOException e) {
            throw new HlsUpstreamUnavailableException(
                    "Upstream HLS server unreachable for stream " + streamId + " at " + upstreamUri + ": "
                            + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HlsUpstreamUnavailableException(
                    "Interrupted while fetching upstream HLS for stream " + streamId, e);
        }
    }

    /**
     * DEBUG-logs every proxied request's path and upstream status; WARN-logs the two failure
     * shapes an operator actually hits in practice -- a non-2xx upstream response (with a preview of
     * its body, since mediamtx's own error bodies are short and diagnostic) and an upstream {@code
     * Content-Type} of {@code text/html}, which means some *other* service (not mediamtx) is
     * actually bound to the configured HLS port -- this happened for real when uvicorn squatted on
     * mediamtx's default {@code 8888} (see this module's {@code hls-base} config comments).
     *
     * @param nonSuccessPreview the bounded preview read from a non-2xx body, or {@code null} for a 2xx response
     */
    private static void logProxyOutcome(HttpServletRequest request, HttpResponse<InputStream> upstreamResponse, byte[] nonSuccessPreview) {
        String path = request.getRequestURI();
        int status = upstreamResponse.statusCode();
        LOG.log(System.Logger.Level.DEBUG, () -> "Proxied " + path + " -> upstream status " + status);

        Optional<String> contentType = upstreamResponse.headers().firstValue("content-type");
        if (contentType.isPresent() && contentType.get().toLowerCase(Locale.ROOT).contains("text/html")) {
            LOG.log(System.Logger.Level.WARNING, () -> "Upstream served HTML for " + path + " (Content-Type: "
                    + contentType.get() + ") -- wrong service on the HLS port?");
        }

        if (nonSuccessPreview != null) {
            String bodyPreview = new String(nonSuccessPreview, StandardCharsets.UTF_8);
            LOG.log(System.Logger.Level.WARNING, () -> "Upstream returned " + status + " for " + path
                    + (bodyPreview.isEmpty() ? "" : ": " + bodyPreview));
        }
    }

    /**
     * The scope gate this controller's class javadoc ("Authorization") describes: {@code 404}s
     * before the upstream is ever contacted when {@code streamId} names a stream this caller's {@link
     * StreamAccess#requireVisible(StreamId) VisibilityScope} may not reach. A {@code streamId} that
     * does not parse as a {@link StreamId} is treated the same as one {@link StreamAccess} has never
     * heard of (see class javadoc) — nothing to check, so this method simply returns rather than
     * rejecting a request {@link #stampVideoDemand} and every existing wire-level test already
     * tolerate.
     */
    private void requireVisibleStream(String streamId) {
        StreamId id;
        try {
            id = StreamId.of(streamId);
        } catch (IllegalArgumentException e) {
            return;
        }
        streamAccess.requireVisible(id);
    }

    /**
     * Records that somebody just fetched this stream's HLS — before the upstream call, not after, so
     * a viewer still counts while mediamtx is slow or erroring. A malformed id is ignored rather than
     * thrown on: this is a side observation, and failing the whole proxy request over it would turn a
     * bookkeeping detail into a broken video player.
     */
    private void stampVideoDemand(String streamId) {
        if (videoDemand == null) {
            return;
        }
        try {
            videoDemand.touched(StreamId.of(streamId));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, () -> "ignoring unparseable stream id in HLS path: " + streamId);
        }
    }

    private URI buildUpstreamUri(HttpServletRequest request) {
        String requestUri = request.getRequestURI(); // raw/undecoded, per servlet spec
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        String pathAfterContext = requestUri.substring(contextPath.length());
        String remainder = pathAfterContext.substring(HLS_PREFIX.length()); // still raw, e.g. "<streamId>/index.m3u8"
        String query = request.getQueryString(); // already raw/encoded, or null
        String target = withoutTrailingSlash(hlsUpstreamBase.toString()) + "/" + remainder
                + (query != null ? "?" + query : "");
        return URI.create(target);
    }

    /**
     * Fetches {@code initialUri}, following redirects itself (see class javadoc) up to {@link
     * #maxRedirectHops} hops. {@code cookieHeader}/{@code rangeHeader} are the browser's own
     * request headers, forwarded on every hop; a redirect hop's {@code Set-Cookie} values are
     * folded into the {@code Cookie} header carried to the next hop ({@link #mergeCookies}) and
     * also accumulated, oldest first, into the result for relaying back to the browser.
     */
    private UpstreamResult fetch(URI initialUri, String cookieHeader, String rangeHeader) throws IOException, InterruptedException {
        URI uri = initialUri;
        String cookie = cookieHeader;
        List<String> setCookies = new ArrayList<>();

        for (int attempt = 0; attempt <= maxRedirectHops; attempt++) {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri).timeout(requestTimeout).GET();
            if (cookie != null && !cookie.isBlank()) {
                requestBuilder.header(HttpHeaders.COOKIE, cookie);
            }
            if (rangeHeader != null && !rangeHeader.isBlank()) {
                requestBuilder.header(HttpHeaders.RANGE, rangeHeader);
            }
            HttpResponse<InputStream> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
            List<String> hopSetCookies = response.headers().allValues("set-cookie");
            setCookies.addAll(hopSetCookies);

            Optional<URI> redirectTarget = redirectLocation(response, uri);
            if (redirectTarget.isEmpty()) {
                return new UpstreamResult(response, setCookies);
            }
            // Redirect hop: this controller never forwards its body to the browser, so it's
            // closed/drained here rather than read into the diagnostic-preview path above.
            response.body().close();
            cookie = mergeCookies(cookie, hopSetCookies);
            uri = redirectTarget.get();
        }
        throw new IOException("Too many redirects (> " + maxRedirectHops + ") fetching upstream HLS at " + initialUri);
    }

    private static Optional<URI> redirectLocation(HttpResponse<InputStream> response, URI requestUri) {
        if (!REDIRECT_STATUS_CODES.contains(response.statusCode())) {
            return Optional.empty();
        }
        return response.headers().firstValue("location").map(requestUri::resolve);
    }

    /**
     * Folds a redirect hop's {@code Set-Cookie} values into the {@code Cookie} header carried to
     * the next hop, so a session cookie mediamtx sets partway through a redirect (pinning the
     * viewer to a node) reaches that node's own request -- replicating, by hand and scoped to this
     * one request, the one thing a shared {@link java.net.CookieHandler} would otherwise have done
     * unsafely (see class javadoc). Cookie attributes ({@code Path}, {@code Max-Age}, ...) are
     * discarded; only the {@code name=value} pair is carried forward, which is all an outgoing
     * {@code Cookie} header can express anyway.
     */
    private static String mergeCookies(String existingCookieHeader, List<String> setCookies) {
        if (setCookies.isEmpty()) {
            return existingCookieHeader;
        }
        Map<String, String> cookies = new LinkedHashMap<>();
        putCookiePairs(existingCookieHeader, cookies);
        for (String setCookie : setCookies) {
            putCookiePair(setCookie.split(";", 2)[0], cookies);
        }
        if (cookies.isEmpty()) {
            return null;
        }
        StringBuilder merged = new StringBuilder();
        cookies.forEach((name, value) -> {
            if (!merged.isEmpty()) {
                merged.append("; ");
            }
            merged.append(name).append('=').append(value);
        });
        return merged.toString();
    }

    private static void putCookiePairs(String cookieHeader, Map<String, String> target) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return;
        }
        for (String pair : cookieHeader.split(";")) {
            putCookiePair(pair, target);
        }
    }

    private static void putCookiePair(String pair, Map<String, String> target) {
        String trimmed = pair.trim();
        int eq = trimmed.indexOf('=');
        if (eq > 0) {
            target.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
        }
    }

    private static HttpHeaders responseHeaders(HttpResponse<InputStream> upstreamResponse, List<String> setCookies,
                                                 boolean viewerRequestWasSecure) {
        HttpHeaders headers = new HttpHeaders();
        var upstream = upstreamResponse.headers();
        upstream.firstValue("content-type").ifPresent(v -> headers.add(HttpHeaders.CONTENT_TYPE, v));
        // docs/plans/done/MVP2-PLAN.md V-a proxy audit: mediamtx marks every LL-HLS live media
        // playlist response "Cache-Control: no-cache" (never cacheable -- the whole point
        // of polling/blocking-reloading it) and completed segments/older non-LL playlists
        // "public, max-age=<segment-duration>" (genuinely safe to cache, they're immutable
        // once named). Forwarding it verbatim, rather than silently dropping it as before,
        // is what makes "adds no caching to live playlists" true by construction instead of
        // by the accident of the browser also receiving no Last-Modified/ETag to key a
        // heuristic cache on -- a stock (non-lowLatencyMode) hls.js still re-polls the exact
        // same index.m3u8 URL on a timer pre-V-b, which a browser HTTP cache CAN legally
        // serve stale without this header, silently freezing the live edge.
        upstream.firstValue("cache-control").ifPresent(v -> headers.add(HttpHeaders.CACHE_CONTROL, v));
        // Byte-range support (recording playback path -- live HLS never triggers these).
        // Content-Length is forwarded unchanged even on the buffered-preview error path above,
        // since re-splitting an unchanged body into two InputStreams doesn't change its total size.
        upstream.firstValue("content-range").ifPresent(v -> headers.add(HttpHeaders.CONTENT_RANGE, v));
        upstream.firstValue("accept-ranges").ifPresent(v -> headers.add(HttpHeaders.ACCEPT_RANGES, v));
        upstream.firstValue("content-length").ifPresent(v -> headers.add(HttpHeaders.CONTENT_LENGTH, v));
        setCookies.forEach(setCookie ->
                headers.add(HttpHeaders.SET_COOKIE, relayableSetCookie(setCookie, viewerRequestWasSecure)));
        return headers;
    }

    /**
     * Rewrites one upstream {@code Set-Cookie} so it survives the scheme the viewer actually used.
     *
     * <p>mediamtx emits each HLS session cookie <em>twice</em> — once bare, once hardened with
     * {@code Secure; SameSite=None; Partitioned} — and both copies share a name and path, so the
     * hardened one replaces the usable one in the viewer's jar. Over https that is exactly right.
     * Over plain http the browser drops it (and rejects {@code SameSite=None} without {@code Secure}
     * outright), so the viewer never sends {@code hlsSession} back, and mediamtx answers the media
     * playlist with {@code 401} — HLS playback is dead on any deployment that isn't behind TLS.
     * Measured on 2026-08-18: every HLS request in a 100-viewer sweep failed this way, while the
     * pre-{@code SCALE-100} proxy passed because its per-request cookie jar never echoed mediamtx's
     * {@code cookieCheck} probe, so mediamtx fell back to putting the session in the playlist URLs.
     *
     * <p>Dropping the three attributes when the viewer is on http is what a reverse proxy is for
     * (nginx spells it {@code proxy_cookie_flags}); the cookie is then stored and returned, and the
     * session survives the next hop. Behind a TLS-terminating front proxy {@code isSecure()} reports
     * this hop, not the viewer's — the cookie is relayed unhardened and the browser still receives
     * it over https, which is weaker than end-to-end {@code Secure} but never broken. Configure
     * {@code server.forward-headers-strategy} to make this hop report the viewer's scheme instead.
     */
    private static String relayableSetCookie(String setCookie, boolean viewerRequestWasSecure) {
        if (viewerRequestWasSecure) {
            return setCookie;
        }
        String[] parts = setCookie.split(";");
        StringBuilder rewritten = new StringBuilder(parts[0].trim());
        for (int i = 1; i < parts.length; i++) {
            String attribute = parts[i].trim();
            if (isHttpsOnlyCookieAttribute(attribute)) {
                continue;
            }
            rewritten.append("; ").append(attribute);
        }
        return rewritten.toString();
    }

    /** {@code Partitioned} and {@code SameSite=None} are only honoured alongside {@code Secure}, so all three go together. */
    private static boolean isHttpsOnlyCookieAttribute(String attribute) {
        String normalized = attribute.toLowerCase(Locale.ROOT);
        return normalized.equals("secure")
                || normalized.equals("partitioned")
                || normalized.replace(" ", "").equals("samesite=none");
    }

    private static String withoutTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** The final (non-redirect) upstream response, plus every {@code Set-Cookie} seen across the whole redirect chain, oldest hop first. */
    private record UpstreamResult(HttpResponse<InputStream> finalResponse, List<String> setCookies) {
    }
}
