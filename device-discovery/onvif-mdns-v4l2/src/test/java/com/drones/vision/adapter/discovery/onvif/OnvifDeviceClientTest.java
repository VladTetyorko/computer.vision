package com.drones.vision.adapter.discovery.onvif;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-parsing unit tests (mirroring {@code OnvifWsDiscoveryScannerTest}'s {@code
 * parseProbeMatch} tests) plus real loopback {@link HttpServer} integration tests for {@link
 * OnvifDeviceClient}'s {@code GetCapabilities}/{@code GetProfiles}/{@code GetStreamUri} chain —
 * a real server stands in for an ONVIF camera, per this repo's "prefer real loopback over mocks"
 * convention. No docker/external prerequisite; nothing here is gated.
 */
class OnvifDeviceClientTest {

    private static final String PROFILES_RESPONSE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://www.w3.org/2003/05/soap-envelope"
                                xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
                                xmlns:tt="http://www.onvif.org/ver10/schema">
              <SOAP-ENV:Body>
                <trt:GetProfilesResponse>
                  <trt:Profiles token="Profile_1" fixed="true">
                    <tt:Name>MainStream</tt:Name>
                  </trt:Profiles>
                  <trt:Profiles token="Profile_2" fixed="true">
                    <tt:Name>SubStream</tt:Name>
                  </trt:Profiles>
                </trt:GetProfilesResponse>
              </SOAP-ENV:Body>
            </SOAP-ENV:Envelope>
            """;

    private static final String STREAM_URI_RESPONSE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://www.w3.org/2003/05/soap-envelope"
                                xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
                                xmlns:tt="http://www.onvif.org/ver10/schema">
              <SOAP-ENV:Body>
                <trt:GetStreamUriResponse>
                  <trt:MediaUri>
                    <tt:Uri>rtsp://192.0.2.10:554/onvif1</tt:Uri>
                    <tt:InvalidAfterConnect>false</tt:InvalidAfterConnect>
                    <tt:InvalidAfterReboot>false</tt:InvalidAfterReboot>
                    <tt:Timeout>PT60S</tt:Timeout>
                  </trt:MediaUri>
                </trt:GetStreamUriResponse>
              </SOAP-ENV:Body>
            </SOAP-ENV:Envelope>
            """;

    private static final String SOAP_AUTH_FAULT_RESPONSE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://www.w3.org/2003/05/soap-envelope">
              <SOAP-ENV:Body>
                <SOAP-ENV:Fault>
                  <SOAP-ENV:Code><SOAP-ENV:Value>SOAP-ENV:Sender</SOAP-ENV:Value></SOAP-ENV:Code>
                  <SOAP-ENV:Reason><SOAP-ENV:Text>Sender not Authorized</SOAP-ENV:Text></SOAP-ENV:Reason>
                </SOAP-ENV:Fault>
              </SOAP-ENV:Body>
            </SOAP-ENV:Envelope>
            """;

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static String capabilitiesResponse(String baseUrl) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://www.w3.org/2003/05/soap-envelope"
                                    xmlns:tds="http://www.onvif.org/ver10/device/wsdl"
                                    xmlns:tt="http://www.onvif.org/ver10/schema">
                  <SOAP-ENV:Body>
                    <tds:GetCapabilitiesResponse>
                      <tds:Capabilities>
                        <tt:Device>
                          <tt:XAddr>%1$s/onvif/device_service</tt:XAddr>
                        </tt:Device>
                        <tt:Media>
                          <tt:XAddr>%1$s/onvif/media_service</tt:XAddr>
                          <tt:StreamingCapabilities>
                            <tt:RTPMulticast>false</tt:RTPMulticast>
                          </tt:StreamingCapabilities>
                        </tt:Media>
                      </tds:Capabilities>
                    </tds:GetCapabilitiesResponse>
                  </SOAP-ENV:Body>
                </SOAP-ENV:Envelope>
                """.formatted(baseUrl);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/soap+xml; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var responseBody = exchange.getResponseBody()) {
            responseBody.write(bytes);
        }
    }

    private static String requestBodyOf(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    // -- pure parsing --------------------------------------------------

    @Test
    void parseMediaXAddrPicksTheMediaCapabilityAddressNotDevice() {
        Optional<URI> mediaAddress = OnvifDeviceClient.parseMediaXAddr(capabilitiesResponse("http://192.168.1.50"));

        assertEquals(Optional.of(URI.create("http://192.168.1.50/onvif/media_service")), mediaAddress);
    }

    @Test
    void parseMediaXAddrReturnsEmptyWhenNoMediaCapability() {
        String xml = """
                <tds:Capabilities xmlns:tds="http://www.onvif.org/ver10/device/wsdl"
                                   xmlns:tt="http://www.onvif.org/ver10/schema">
                  <tt:Device><tt:XAddr>http://192.168.1.50/onvif/device_service</tt:XAddr></tt:Device>
                </tds:Capabilities>
                """;

        assertTrue(OnvifDeviceClient.parseMediaXAddr(xml).isEmpty());
    }

    @Test
    void parseMediaXAddrReturnsEmptyOnGarbageInput() {
        assertTrue(OnvifDeviceClient.parseMediaXAddr("not xml at all, just noise").isEmpty());
        assertTrue(OnvifDeviceClient.parseMediaXAddr(null).isEmpty());
    }

    @Test
    void parseFirstProfileTokenReturnsTheFirstOfSeveralProfiles() {
        assertEquals(Optional.of("Profile_1"), OnvifDeviceClient.parseFirstProfileToken(PROFILES_RESPONSE));
    }

    @Test
    void parseFirstProfileTokenReturnsEmptyWhenNoProfiles() {
        String xml = """
                <trt:GetProfilesResponse xmlns:trt="http://www.onvif.org/ver10/media/wsdl"/>
                """;

        assertTrue(OnvifDeviceClient.parseFirstProfileToken(xml).isEmpty());
        assertTrue(OnvifDeviceClient.parseFirstProfileToken(null).isEmpty());
    }

    @Test
    void parseStreamUriExtractsTheInnerUriNotTheMediaUriWrapper() {
        assertEquals(Optional.of(URI.create("rtsp://192.0.2.10:554/onvif1")),
                OnvifDeviceClient.parseStreamUri(STREAM_URI_RESPONSE));
    }

    @Test
    void parseStreamUriReturnsEmptyOnMalformedOrEmptyInput() {
        assertTrue(OnvifDeviceClient.parseStreamUri("not xml at all").isEmpty());
        assertTrue(OnvifDeviceClient.parseStreamUri("<trt:MediaUri></trt:MediaUri>").isEmpty());
        assertTrue(OnvifDeviceClient.parseStreamUri(null).isEmpty());
    }

    @Test
    void isAuthRefusedTrueOn401RegardlessOfBody() {
        assertTrue(OnvifDeviceClient.isAuthRefused(401, ""));
        assertTrue(OnvifDeviceClient.isAuthRefused(401, "anything"));
    }

    @Test
    void isAuthRefusedTrueOnSoapAuthFaultBody() {
        assertTrue(OnvifDeviceClient.isAuthRefused(200, SOAP_AUTH_FAULT_RESPONSE));
    }

    @Test
    void isAuthRefusedFalseOnUnrelatedFault() {
        String unrelatedFault = """
                <SOAP-ENV:Fault xmlns:SOAP-ENV="http://www.w3.org/2003/05/soap-envelope">
                  <SOAP-ENV:Reason><SOAP-ENV:Text>ActionNotSupported</SOAP-ENV:Text></SOAP-ENV:Reason>
                </SOAP-ENV:Fault>
                """;

        assertFalse(OnvifDeviceClient.isAuthRefused(500, unrelatedFault));
    }

    @Test
    void isAuthRefusedFalseOnSuccessResponse() {
        assertFalse(OnvifDeviceClient.isAuthRefused(200, PROFILES_RESPONSE));
        assertFalse(OnvifDeviceClient.isAuthRefused(200, null));
    }

    // -- full chain, real loopback HTTP ---------------------------------

    @Test
    void probeStreamReturnsTheRtspUriThroughTheFullChain() throws Exception {
        server.createContext("/onvif/device_service", exchange -> respond(exchange, 200, capabilitiesResponse(baseUrl)));
        server.createContext("/onvif/media_service", exchange -> {
            String body = requestBodyOf(exchange);
            respond(exchange, 200, body.contains("GetStreamUri") ? STREAM_URI_RESPONSE : PROFILES_RESPONSE);
        });
        server.start();

        StreamProbeOutcome outcome = new OnvifDeviceClient()
                .probeStream(URI.create(baseUrl + "/onvif/device_service"), Duration.ofSeconds(5));

        assertTrue(outcome instanceof StreamProbeOutcome.Found, "expected Found, got: " + outcome);
        assertEquals(URI.create("rtsp://192.0.2.10:554/onvif1"), ((StreamProbeOutcome.Found) outcome).uri());
    }

    @Test
    void probeStreamReturnsAuthRequiredWhenDeviceServiceAnswers401() throws Exception {
        server.createContext("/onvif/device_service", exchange -> respond(exchange, 401, "Unauthorized"));
        server.start();

        StreamProbeOutcome outcome = new OnvifDeviceClient()
                .probeStream(URI.create(baseUrl + "/onvif/device_service"), Duration.ofSeconds(5));

        assertTrue(outcome instanceof StreamProbeOutcome.AuthRequired, "expected AuthRequired, got: " + outcome);
    }

    @Test
    void probeStreamReturnsAuthRequiredOnSoapAuthFaultBody() throws Exception {
        server.createContext("/onvif/device_service", exchange -> respond(exchange, 200, SOAP_AUTH_FAULT_RESPONSE));
        server.start();

        StreamProbeOutcome outcome = new OnvifDeviceClient()
                .probeStream(URI.create(baseUrl + "/onvif/device_service"), Duration.ofSeconds(5));

        assertTrue(outcome instanceof StreamProbeOutcome.AuthRequired, "expected AuthRequired, got: " + outcome);
    }

    @Test
    void probeStreamReturnsUnavailableOnMalformedCapabilitiesResponse() throws Exception {
        server.createContext("/onvif/device_service", exchange -> respond(exchange, 200, "not xml at all, just noise"));
        server.start();

        StreamProbeOutcome outcome = new OnvifDeviceClient()
                .probeStream(URI.create(baseUrl + "/onvif/device_service"), Duration.ofSeconds(5));

        assertTrue(outcome instanceof StreamProbeOutcome.Unavailable, "expected Unavailable, got: " + outcome);
    }

    /**
     * Proves the per-device budget is a real request timeout, not just a "skip if already
     * exhausted" check: the handler sleeps for 2s but the client is given a 200ms budget, so the
     * call must abort near 200ms rather than waiting out the handler.
     */
    @Test
    void probeStreamReturnsUnavailableWhenTheDeviceIsSlowerThanItsBudget() throws Exception {
        server.createContext("/onvif/device_service", exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, capabilitiesResponse(baseUrl));
        });
        server.start();

        long startNanos = System.nanoTime();
        StreamProbeOutcome outcome = new OnvifDeviceClient()
                .probeStream(URI.create(baseUrl + "/onvif/device_service"), Duration.ofMillis(200));
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        assertTrue(outcome instanceof StreamProbeOutcome.Unavailable, "expected Unavailable, got: " + outcome);
        assertTrue(elapsedMillis < 1500,
                "expected the call to abort near its 200ms budget, took " + elapsedMillis + "ms");
    }

    @Test
    void probeStreamReturnsUnavailableImmediatelyWhenTheBudgetIsAlreadyExhausted() {
        StreamProbeOutcome zero = new OnvifDeviceClient()
                .probeStream(URI.create(baseUrl + "/onvif/device_service"), Duration.ZERO);
        StreamProbeOutcome negative = new OnvifDeviceClient()
                .probeStream(URI.create(baseUrl + "/onvif/device_service"), Duration.ofMillis(-1));

        assertTrue(zero instanceof StreamProbeOutcome.Unavailable);
        assertTrue(negative instanceof StreamProbeOutcome.Unavailable);
    }
}
