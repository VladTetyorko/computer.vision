package com.drones.vision.adapter.publishhls;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MediamtxControlApi} against a real, in-process HTTP server (JDK's own {@code
 * com.sun.net.httpserver.HttpServer} — no new test dependency, and a real socket instead of a mocked
 * {@link java.net.http.HttpClient}) that plays back exactly the response shapes wave M0 measured
 * against a real mediamtx 1.19.3 (docs/conclusions/CV-PULL-SPIKE.md &sect;5, transcript at
 * {@code cv-service/spikes/pull/results/mediamtx_api_transcript.txt}) — including the 401
 * authentication gate that transcript's own item 0 recorded as blocking every call until fixed.
 */
class MediamtxControlApiTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void createPathSucceedsOnFirstAttempt() throws IOException {
        List<String> requests = new CopyOnWriteArrayList<>();
        server = startServer(exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            respond(exchange, 200, "{\"status\":\"ok\"}");
        });
        MediamtxControlApi api = new MediamtxControlApi(baseUri(server), null, null);

        assertDoesNotThrow(() -> api.createOrUpdatePath("stream-1", "rtsp://camera/feed", false, "automatic"));

        assertEquals(List.of("POST /v3/config/paths/add/stream-1"), requests);
    }

    /** M0's own confirmed idempotent-start fallback: a 400 "path already exists" falls through to PATCH. */
    @Test
    void createPathFallsThroughToPatchWhenPathAlreadyExists() throws IOException {
        List<String> requests = new CopyOnWriteArrayList<>();
        server = startServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(exchange.getRequestMethod() + " " + path);
            if (path.contains("/add/")) {
                respond(exchange, 400, "{\"status\":\"error\",\"error\":\"path already exists\"}");
            } else {
                respond(exchange, 200, "{\"status\":\"ok\"}");
            }
        });
        MediamtxControlApi api = new MediamtxControlApi(baseUri(server), null, null);

        assertDoesNotThrow(() -> api.createOrUpdatePath("stream-1", "rtsp://camera/feed", false, "automatic"));

        assertEquals(List.of("POST /v3/config/paths/add/stream-1", "PATCH /v3/config/paths/patch/stream-1"), requests);
    }

    @Test
    void createPathThrowsOnA400ThatIsNotThePathAlreadyExistsCase() throws IOException {
        server = startServer(exchange -> respond(exchange, 400, "{\"status\":\"error\",\"error\":\"invalid source\"}"));
        MediamtxControlApi api = new MediamtxControlApi(baseUri(server), null, null);

        MediamtxControlApiException exception = assertThrows(MediamtxControlApiException.class,
                () -> api.createOrUpdatePath("stream-1", "not-a-url", false, "automatic"));
        assertTrue(exception.getMessage().contains("400"));
    }

    @Test
    void isReadyReturnsTrueWhenMediamtxReportsReady() throws IOException {
        server = startServer(exchange -> respond(exchange, 200, "{\"name\":\"stream-1\",\"ready\":true}"));
        MediamtxControlApi api = new MediamtxControlApi(baseUri(server), null, null);

        assertTrue(api.isReady("stream-1"));
    }

    @Test
    void isReadyReturnsFalseWhenMediamtxReportsNotReady() throws IOException {
        server = startServer(exchange -> respond(exchange, 200, "{\"name\":\"stream-1\",\"ready\":false}"));
        MediamtxControlApi api = new MediamtxControlApi(baseUri(server), null, null);

        assertFalse(api.isReady("stream-1"));
    }

    /** A 404 while polling ("path not found") is not-ready, not an error -- callers are polling in a loop. */
    @Test
    void isReadyReturnsFalseOn404WithoutThrowing() throws IOException {
        server = startServer(exchange -> respond(exchange, 404, "{\"status\":\"error\",\"error\":\"path not found\"}"));
        MediamtxControlApi api = new MediamtxControlApi(baseUri(server), null, null);

        assertFalse(assertDoesNotThrow(() -> api.isReady("never-existed")));
    }

    @Test
    void deletePathSucceedsOn200() throws IOException {
        server = startServer(exchange -> respond(exchange, 200, "{\"status\":\"ok\"}"));
        MediamtxControlApi api = new MediamtxControlApi(baseUri(server), null, null);

        assertDoesNotThrow(() -> api.deletePath("stream-1"));
    }

    /** M0's confirmed idempotent-stop behaviour: 404 for both a just-deleted and a never-existing path. */
    @Test
    void deletePathTreats404AsSuccess() throws IOException {
        server = startServer(exchange -> respond(exchange, 404, "{\"status\":\"error\",\"error\":\"path not found\"}"));
        MediamtxControlApi api = new MediamtxControlApi(baseUri(server), null, null);

        assertDoesNotThrow(() -> api.deletePath("never-existed"));
    }

    @Test
    void deletePathThrowsOnAnUnexpectedStatus() throws IOException {
        server = startServer(exchange -> respond(exchange, 500, "{\"status\":\"error\",\"error\":\"internal\"}"));
        MediamtxControlApi api = new MediamtxControlApi(baseUri(server), null, null);

        assertThrows(MediamtxControlApiException.class, () -> api.deletePath("stream-1"));
    }

    /**
     * The blocking correction M0 recorded (docs/conclusions/CV-PULL-SPIKE.md &sect;5): mediamtx's
     * Control API 401s every call by default. This must surface as a message an operator can act on,
     * not a bare "HTTP 401".
     */
    @Test
    void authenticationFailureThrowsAnExceptionThatNamesAuthenticationAsTheCause() throws IOException {
        server = startServer(exchange -> respond(exchange, 401, "{\"status\":\"error\",\"error\":\"authentication error\"}"));
        MediamtxControlApi api = new MediamtxControlApi(baseUri(server), null, null);

        MediamtxControlApiException exception = assertThrows(MediamtxControlApiException.class,
                () -> api.isReady("stream-1"));

        assertTrue(exception.getMessage().toLowerCase(Locale.ROOT).contains("authentication"),
                "expected the message to say the Control API rejected authentication, got: " + exception.getMessage());
    }

    @Test
    void sendsBasicAuthorizationHeaderWhenCredentialsAreConfigured() throws IOException {
        List<String> authHeaders = new CopyOnWriteArrayList<>();
        server = startServer(exchange -> {
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"status\":\"ok\"}");
        });
        MediamtxControlApi api = new MediamtxControlApi(baseUri(server), "operator", "secret");

        api.createOrUpdatePath("stream-1", "rtsp://camera/feed", false, "automatic");

        String expected = "Basic " + Base64.getEncoder().encodeToString("operator:secret".getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(expected), authHeaders);
    }

    @Test
    void sendsNoAuthorizationHeaderByDefault() throws IOException {
        List<String> authHeaders = new CopyOnWriteArrayList<>();
        server = startServer(exchange -> {
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"status\":\"ok\"}");
        });
        MediamtxControlApi api = new MediamtxControlApi(baseUri(server), null, null);

        api.createOrUpdatePath("stream-1", "rtsp://camera/feed", false, "automatic");

        assertEquals(Collections.singletonList(null), authHeaders);
    }

    @Test
    void unreachableMediamtxThrowsADiagnosableExceptionRatherThanHanging() {
        MediamtxControlApi api = new MediamtxControlApi(URI.create("http://127.0.0.1:1"), null, null);

        assertThrows(MediamtxControlApiException.class,
                () -> api.createOrUpdatePath("stream-1", "rtsp://camera/feed", false, "automatic"));
    }

    @Test
    void extractReadyFieldParsesTrueAndFalse() {
        assertTrue(MediamtxControlApi.extractReadyField("{\"name\":\"x\",\"ready\":true,\"other\":1}"));
        assertFalse(MediamtxControlApi.extractReadyField("{\"ready\":false}"));
    }

    @Test
    void extractReadyFieldDefaultsFalseWhenTheFieldIsMissingOrTheBodyIsNull() {
        assertFalse(MediamtxControlApi.extractReadyField("{}"));
        assertFalse(MediamtxControlApi.extractReadyField(null));
    }

    @Test
    void bodyIndicatesPathAlreadyExistsMatchesOnlyThatExactErrorText() {
        assertTrue(MediamtxControlApi.bodyIndicatesPathAlreadyExists(
                "{\"status\":\"error\",\"error\":\"path already exists\"}"));
        assertFalse(MediamtxControlApi.bodyIndicatesPathAlreadyExists(
                "{\"status\":\"error\",\"error\":\"invalid source\"}"));
        assertFalse(MediamtxControlApi.bodyIndicatesPathAlreadyExists(null));
    }

    private static HttpServer startServer(HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private static URI baseUri(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
