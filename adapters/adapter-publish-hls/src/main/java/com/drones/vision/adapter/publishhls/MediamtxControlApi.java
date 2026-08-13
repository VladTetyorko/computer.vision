package com.drones.vision.adapter.publishhls;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin client for the four mediamtx v3 Control API operations {@link MediamtxProxyPublisher} needs
 * (docs/plans/active/MEDIA-SOT-PLAN.md &sect;5.3): create/patch a path (idempotent start), read its
 * readiness, and delete it (idempotent stop). Every response shape below was verified against a real
 * {@code bluenviron/mediamtx:1.19.3} by wave M0's {@code curl} transcript,
 * {@code cv-service/spikes/pull/results/mediamtx_api_transcript.txt} — this class codes directly
 * against that measured behaviour, not the API docs.
 *
 * <h2>Auth (M0's blocking correction)</h2>
 * mediamtx's baked-in {@code authInternalUsers} grants unauthenticated {@code api} access only to a
 * caller at {@code 127.0.0.1}/{@code ::1}. A docker-published port does not preserve that view (the
 * container sees the bridge gateway IP, not loopback), and neither does a sibling container calling
 * over the compose network — so every one of these four calls 401s against mediamtx's default
 * config. Fixing mediamtx's own config (mounting a widened {@code mediamtx.yml}, since
 * {@code MTX_AUTHINTERNALUSERS} as an env override was tried by M0 and does not work) is wave M7's
 * job, not this class's. What this class does is (a) send HTTP Basic auth when an API user/password
 * are configured, and (b) turn a 401 into a {@link MediamtxControlApiException} whose message says
 * plainly that the Control API rejected authentication, not a generic HTTP-error string an operator
 * has to decode.
 *
 * <p>Package-private — used only by {@link MediamtxProxyPublisher}, in this package. Holds one
 * {@link HttpClient} for its lifetime (documented safe for concurrent use); otherwise stateless.
 */
final class MediamtxControlApi {

    private static final System.Logger LOG = System.getLogger(MediamtxControlApi.class.getName());

    /**
     * Bounds each individual HTTP call, deliberately well under {@link
     * MediamtxProxySettings#readyTimeout()}'s default so one hung request during readiness polling
     * cannot by itself consume the whole start-call budget.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private static final Pattern READY_FIELD = Pattern.compile("\"ready\"\\s*:\\s*(true|false)");
    private static final Pattern ERROR_FIELD = Pattern.compile("\"error\"\\s*:\\s*\"([^\"]*)\"");
    private static final String PATH_ALREADY_EXISTS_ERROR = "path already exists";

    private final String apiBaseString;
    private final String authorizationHeaderValue;
    private final HttpClient httpClient;

    /**
     * @param apiBase    mediamtx's Control API base, e.g. {@code http://localhost:19997}
     * @param apiUser    optional Basic-auth username; {@code null}/blank sends no {@code
     *                   Authorization} header (today's default — 401s against mediamtx's own
     *                   default config, see class javadoc)
     * @param apiPassword password paired with {@code apiUser}; ignored when {@code apiUser} is unset
     */
    MediamtxControlApi(URI apiBase, String apiUser, String apiPassword) {
        Objects.requireNonNull(apiBase, "apiBase must not be null");
        this.apiBaseString = withoutTrailingSlash(apiBase.toString());
        this.authorizationHeaderValue = basicAuthHeader(apiUser, apiPassword);
        this.httpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
    }

    private static String basicAuthHeader(String apiUser, String apiPassword) {
        if (apiUser == null || apiUser.isBlank()) {
            return null;
        }
        String credentials = apiUser + ":" + (apiPassword == null ? "" : apiPassword);
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Creates a path whose {@code source} is {@code sourceUrl} (docs/plans/active/MEDIA-SOT-PLAN.md
     * &sect;5.3 "create path" — verified {@code {"status":"ok"}}/HTTP 200). If the path already
     * exists — the normal case restarting a stream against a path mediamtx never tore down — falls
     * through to {@code PATCH} with the same body: the "already exists &rarr; patch" idempotent-start
     * fallback, verified reachable and returning {@code {"status":"ok"}} by M0. Any other non-2xx
     * response throws {@link MediamtxControlApiException}.
     */
    void createOrUpdatePath(String pathName, String sourceUrl, boolean sourceOnDemand, String rtspTransport) {
        String body = pathRequestBody(sourceUrl, sourceOnDemand, rtspTransport);
        HttpResponse<String> created = send(pathName, "create path",
                authorized(HttpRequest.newBuilder(addPathUri(pathName)))
                        .POST(HttpRequest.BodyPublishers.ofString(body)));
        if (created.statusCode() == 200) {
            return;
        }
        if (created.statusCode() == 400 && bodyIndicatesPathAlreadyExists(created.body())) {
            HttpResponse<String> patched = send(pathName, "patch path",
                    authorized(HttpRequest.newBuilder(patchPathUri(pathName)))
                            .method("PATCH", HttpRequest.BodyPublishers.ofString(body)));
            if (patched.statusCode() == 200) {
                return;
            }
            throw unexpectedStatus("patch path", pathName, patched);
        }
        throw unexpectedStatus("create path", pathName, created);
    }

    /**
     * @return {@code true} once mediamtx reports the path {@code ready} (docs/plans/active/MEDIA-SOT-PLAN.md
     *         &sect;5.3 "readiness"; {@code {"ready":bool,...}}/HTTP 200). A 404 ({@code path not
     *         found}) is reported as simply not-ready — right after creation there can be a brief
     *         window before mediamtx registers the path, and callers of this method are polling in
     *         a loop, so this is not itself an exceptional condition worth throwing over.
     */
    boolean isReady(String pathName) {
        HttpResponse<String> response =
                send(pathName, "readiness check", authorized(HttpRequest.newBuilder(getPathUri(pathName))).GET());
        if (response.statusCode() == 404) {
            return false;
        }
        if (response.statusCode() != 200) {
            throw unexpectedStatus("readiness check", pathName, response);
        }
        return extractReadyField(response.body());
    }

    /**
     * Deletes a path. A 404 — the path was already deleted, or never existed — is treated as success
     * (docs/plans/active/MEDIA-SOT-PLAN.md &sect;5.3 "delete path": verified 404 for both a
     * just-deleted and a never-existing path), matching {@link
     * com.drones.vision.domain.port.out.StreamPublisherPort#streamEnded}'s own idempotency contract.
     */
    void deletePath(String pathName) {
        HttpResponse<String> response =
                send(pathName, "delete path", authorized(HttpRequest.newBuilder(deletePathUri(pathName))).DELETE());
        if (response.statusCode() == 200 || response.statusCode() == 404) {
            return;
        }
        throw unexpectedStatus("delete path", pathName, response);
    }

    private HttpRequest.Builder authorized(HttpRequest.Builder builder) {
        builder.timeout(REQUEST_TIMEOUT).header("Content-Type", "application/json");
        if (authorizationHeaderValue != null) {
            builder.header("Authorization", authorizationHeaderValue);
        }
        return builder;
    }

    private HttpResponse<String> send(String pathName, String operation, HttpRequest.Builder requestBuilder) {
        try {
            return httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new MediamtxControlApiException("mediamtx Control API " + operation + " failed for path '"
                    + pathName + "' at " + apiBaseString + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MediamtxControlApiException(
                    "mediamtx Control API " + operation + " for path '" + pathName + "' at " + apiBaseString
                            + " was interrupted", e);
        }
    }

    private MediamtxControlApiException unexpectedStatus(String operation, String pathName,
            HttpResponse<String> response) {
        if (response.statusCode() == 401) {
            LOG.log(System.Logger.Level.WARNING,
                    "mediamtx Control API rejected authentication for " + operation + " on path '" + pathName + "'");
            return new MediamtxControlApiException(
                    "mediamtx Control API rejected authentication (HTTP 401) for " + operation + " on path '"
                            + pathName + "' at " + apiBaseString + " -- mediamtx's Control API is IP-gated by its "
                            + "default authInternalUsers config (only 127.0.0.1/::1 get unauthenticated access; "
                            + "MTX_AUTHINTERNALUSERS alone does not widen this). Configure "
                            + "vision.publish.mediamtx.api-user/api-password to match mediamtx's own configured "
                            + "API credentials, or mount a mediamtx.yml that widens the api user's ips.");
        }
        return new MediamtxControlApiException(
                "mediamtx Control API " + operation + " for path '" + pathName + "' at " + apiBaseString
                        + " returned unexpected HTTP " + response.statusCode() + ": " + response.body());
    }

    private URI addPathUri(String pathName) {
        return URI.create(apiBaseString + "/v3/config/paths/add/" + pathName);
    }

    private URI patchPathUri(String pathName) {
        return URI.create(apiBaseString + "/v3/config/paths/patch/" + pathName);
    }

    private URI getPathUri(String pathName) {
        return URI.create(apiBaseString + "/v3/paths/get/" + pathName);
    }

    private URI deletePathUri(String pathName) {
        return URI.create(apiBaseString + "/v3/config/paths/delete/" + pathName);
    }

    private static String pathRequestBody(String sourceUrl, boolean sourceOnDemand, String rtspTransport) {
        return "{\"source\":\"" + jsonEscape(sourceUrl) + "\",\"sourceOnDemand\":" + sourceOnDemand
                + ",\"rtspTransport\":\"" + jsonEscape(rtspTransport) + "\"}";
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String withoutTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** Package-private test seam: whether a create-path error body is mediamtx's "already exists" error. */
    static boolean bodyIndicatesPathAlreadyExists(String body) {
        Matcher matcher = ERROR_FIELD.matcher(body == null ? "" : body);
        return matcher.find() && matcher.group(1).equals(PATH_ALREADY_EXISTS_ERROR);
    }

    /** Package-private test seam: extracts the {@code "ready"} boolean field from a readiness response body. */
    static boolean extractReadyField(String body) {
        Matcher matcher = READY_FIELD.matcher(body == null ? "" : body);
        return matcher.find() && Boolean.parseBoolean(matcher.group(1));
    }
}
