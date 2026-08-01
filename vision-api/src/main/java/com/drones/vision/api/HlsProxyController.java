package com.drones.vision.api;

import com.drones.vision.api.exceptions.ApiExceptionHandler;
import com.drones.vision.api.exceptions.HlsUpstreamUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

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
 * falls through to Spring's normal 404 handling.
 *
 * <h2>Redirects and cookies</h2>
 * mediamtx pins HLS viewers to a specific internal node with a {@code
 * Set-Cookie} on an initial {@code 302} redirect. This controller follows
 * such redirects itself, server-side, via {@link
 * java.net.http.HttpClient.Redirect#NORMAL} — the browser only ever sees
 * this app's origin and a final {@code 200} — using a fresh {@link
 * CookieManager} per incoming request (seeded from that request's own
 * {@code Cookie} header) so cookies set partway through a redirect chain are
 * carried to the next hop without leaking between unrelated browser
 * requests. Every {@code Set-Cookie} observed across the whole redirect
 * chain (via {@link HttpResponse#previousResponse()}) is relayed back to the
 * browser, oldest hop first, so the browser (and thus its next request) ends
 * up pinned the same way a direct client of mediamtx would be.
 *
 * <h2>Body size</h2>
 * Bodies are buffered fully in memory ({@link HttpResponse.BodyHandlers#ofByteArray()})
 * rather than streamed — acceptable at this scale (playlists are tiny,
 * fMP4 segments are at most a few MB) and far simpler than a true streaming
 * proxy.
 *
 * <h2>Failure handling</h2>
 * Only a failure to reach the upstream at all (connection refused, DNS
 * failure, timeout, broken redirect chain) is treated as an error, surfaced
 * as {@link HlsUpstreamUnavailableException} and mapped to {@code 502} by
 * {@link ApiExceptionHandler}. A normal non-2xx response actually received
 * from upstream (e.g. {@code 404} for a not-yet-ready segment) is passed
 * through verbatim, exactly as {@link #proxy} passes through the upstream's
 * {@code Content-Type}, {@code Cache-Control}, and status on success — this
 * controller adds no caching headers of its own, and forwards (rather than
 * drops) whatever caching header mediamtx itself sent (docs/MVP2-PLAN.md
 * V-a proxy audit), so mediamtx's own {@code no-cache} on live LL-HLS
 * playlists reaches the browser instead of silently vanishing.
 */
@RestController
public class HlsProxyController {

    private static final System.Logger LOG = System.getLogger(HlsProxyController.class.getName());
    private static final String HLS_PREFIX = "/hls/";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    /** How much of a non-2xx upstream error body to include in the WARN log line — enough to identify the problem, not a full dump. */
    private static final int ERROR_BODY_PREVIEW_MAX_CHARS = 200;

    private final URI hlsUpstreamBase;

    /**
     * @param hlsUpstreamBase base HTTP URL of the mediamtx sidecar's HLS egress that this
     *                        controller forwards to, e.g. {@code http://localhost:18888};
     *                        never exposed to browsers
     */
    public HlsProxyController(URI hlsUpstreamBase) {
        this.hlsUpstreamBase = Objects.requireNonNull(hlsUpstreamBase, "hlsUpstreamBase must not be null");
    }

    @GetMapping("/hls/{streamId}/**")
    public ResponseEntity<byte[]> proxy(@PathVariable String streamId, HttpServletRequest request) {
        URI upstreamUri = buildUpstreamUri(request);
        try {
            HttpResponse<byte[]> upstreamResponse = fetch(upstreamUri, request.getHeader(HttpHeaders.COOKIE));
            logProxyOutcome(request, upstreamResponse);

            HttpHeaders headers = new HttpHeaders();
            upstreamResponse.headers().firstValue("content-type")
                    .ifPresent(contentType -> headers.add(HttpHeaders.CONTENT_TYPE, contentType));
            // docs/MVP2-PLAN.md V-a proxy audit: mediamtx marks every LL-HLS live media
            // playlist response "Cache-Control: no-cache" (never cacheable — the whole point
            // of polling/blocking-reloading it) and completed segments/older non-LL playlists
            // "public, max-age=<segment-duration>" (genuinely safe to cache, they're immutable
            // once named). Forwarding it verbatim, rather than silently dropping it as before,
            // is what makes "adds no caching to live playlists" true by construction instead of
            // by the accident of the browser also receiving no Last-Modified/ETag to key a
            // heuristic cache on — a stock (non-lowLatencyMode) hls.js still re-polls the exact
            // same index.m3u8 URL on a timer pre-V-b, which a browser HTTP cache CAN legally
            // serve stale without this header, silently freezing the live edge.
            upstreamResponse.headers().firstValue("cache-control")
                    .ifPresent(cacheControl -> headers.add(HttpHeaders.CACHE_CONTROL, cacheControl));
            collectSetCookies(upstreamResponse).forEach(setCookie -> headers.add(HttpHeaders.SET_COOKIE, setCookie));

            return ResponseEntity.status(upstreamResponse.statusCode()).headers(headers).body(upstreamResponse.body());
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
     * shapes an operator actually hits in practice — a non-2xx upstream response (with a preview of
     * its body, since mediamtx's own error bodies are short and diagnostic) and an upstream {@code
     * Content-Type} of {@code text/html}, which means some *other* service (not mediamtx) is
     * actually bound to the configured HLS port — this happened for real when uvicorn squatted on
     * mediamtx's default {@code 8888} (see this module's {@code hls-base} config comments).
     */
    private static void logProxyOutcome(HttpServletRequest request, HttpResponse<byte[]> upstreamResponse) {
        String path = request.getRequestURI();
        int status = upstreamResponse.statusCode();
        LOG.log(System.Logger.Level.DEBUG, () -> "Proxied " + path + " -> upstream status " + status);

        Optional<String> contentType = upstreamResponse.headers().firstValue("content-type");
        if (contentType.isPresent() && contentType.get().toLowerCase(Locale.ROOT).contains("text/html")) {
            LOG.log(System.Logger.Level.WARNING, () -> "Upstream served HTML for " + path + " (Content-Type: "
                    + contentType.get() + ") -- wrong service on the HLS port?");
        }

        if (status < 200 || status >= 300) {
            String bodyPreview = bodyPreview(upstreamResponse.body());
            LOG.log(System.Logger.Level.WARNING, () -> "Upstream returned " + status + " for " + path
                    + (bodyPreview.isEmpty() ? "" : ": " + bodyPreview));
        }
    }

    /** First {@value #ERROR_BODY_PREVIEW_MAX_CHARS} characters of a (presumed textual) upstream error body, or {@code ""} for an empty/absent one. */
    private static String bodyPreview(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String text = new String(body, 0, Math.min(body.length, ERROR_BODY_PREVIEW_MAX_CHARS), StandardCharsets.UTF_8);
        return text.length() > ERROR_BODY_PREVIEW_MAX_CHARS ? text.substring(0, ERROR_BODY_PREVIEW_MAX_CHARS) : text;
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

    private HttpResponse<byte[]> fetch(URI upstreamUri, String cookieHeader) throws IOException, InterruptedException {
        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        seedCookies(cookieManager, upstreamUri, cookieHeader);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .cookieHandler(cookieManager)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        HttpRequest httpRequest = HttpRequest.newBuilder(upstreamUri).timeout(REQUEST_TIMEOUT).GET().build();
        return client.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
    }

    /** Seeds the per-request {@link CookieManager} from the browser's own {@code Cookie} header, so it rides along on the initial upstream request and any redirect hop that follows. */
    private static void seedCookies(CookieManager cookieManager, URI upstreamUri, String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return;
        }
        for (String pair : cookieHeader.split(";")) {
            String trimmed = pair.trim();
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue; // malformed pair; skip rather than fail the whole proxy request
            }
            String name = trimmed.substring(0, eq).trim();
            String value = trimmed.substring(eq + 1).trim();
            try {
                HttpCookie cookie = new HttpCookie(name, value);
                // HttpCookie(name, value) defaults to RFC 2965 version 1, which CookieManager
                // then serializes back into an outgoing Cookie header using the legacy
                // $Version="1"; name="value";$Path="/" syntax -- not what the browser actually
                // sent and not what a plain HTTP server like mediamtx expects. Version 0 gets
                // the modern "name=value" syntax real servers understand.
                cookie.setVersion(0);
                cookie.setPath("/");
                cookieManager.getCookieStore().add(upstreamUri, cookie);
            } catch (IllegalArgumentException e) {
                LOG.log(System.Logger.Level.DEBUG, () -> "Skipping malformed incoming cookie: " + name);
            }
        }
    }

    /** Walks the whole redirect chain (oldest hop first) collecting every {@code Set-Cookie} value observed. */
    private static List<String> collectSetCookies(HttpResponse<byte[]> response) {
        List<String> setCookies = new ArrayList<>();
        for (Optional<HttpResponse<byte[]>> hop = Optional.of(response); hop.isPresent(); hop = hop.get().previousResponse()) {
            setCookies.addAll(0, hop.get().headers().allValues("set-cookie"));
        }
        return setCookies;
    }

    private static String withoutTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
