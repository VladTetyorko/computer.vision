package com.drones.vision.adapter.discovery.onvif;

import com.drones.vision.kernel.CategoryId;
import com.drones.vision.warehouse.domain.model.DiscoveredDevice;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnvifWsDiscoveryScannerTest {

    private static final String WELL_FORMED_PROBE_MATCH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <soap-env:Envelope xmlns:soap-env="http://www.w3.org/2003/05/soap-envelope"
                                xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                                xmlns:wsdd="http://schemas.xmlsoap.org/ws/2005/04/discovery"
                                xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
              <soap-env:Header>
                <wsa:MessageID>urn:uuid:1234</wsa:MessageID>
                <wsa:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/ProbeMatches</wsa:Action>
              </soap-env:Header>
              <soap-env:Body>
                <wsdd:ProbeMatches>
                  <wsdd:ProbeMatch>
                    <wsa:EndpointReference>
                      <wsa:Address>urn:uuid:4d1a4d38-c1f3-4b1e-8a7c-2f9b1e2c9a10</wsa:Address>
                    </wsa:EndpointReference>
                    <wsdd:Types>dn:NetworkVideoTransmitter</wsdd:Types>
                    <wsdd:Scopes>onvif://www.onvif.org/type/video_encoder onvif://www.onvif.org/name/HallwayCam</wsdd:Scopes>
                    <wsdd:XAddrs>http://192.168.1.50/onvif/device_service</wsdd:XAddrs>
                    <wsdd:MetadataVersion>1</wsdd:MetadataVersion>
                  </wsdd:ProbeMatch>
                </wsdd:ProbeMatches>
              </soap-env:Body>
            </soap-env:Envelope>
            """;

    @Test
    void parsesWellFormedProbeMatch() {
        Optional<DiscoveredDevice> result = OnvifWsDiscoveryScanner.parseProbeMatch(WELL_FORMED_PROBE_MATCH);

        assertTrue(result.isPresent());
        DiscoveredDevice device = result.get();
        assertEquals("onvif", device.method());
        assertEquals("HallwayCam", device.name());
        assertEquals(URI.create("http://192.168.1.50/onvif/device_service"), device.address());
        assertEquals(new CategoryId("ip-camera"), device.suggestedCategory());
        assertNull(device.suggestedStream());
        assertEquals("urn:uuid:4d1a4d38-c1f3-4b1e-8a7c-2f9b1e2c9a10", device.details().get("epr"));
        assertTrue(device.details().get("types").contains("NetworkVideoTransmitter"));
        assertTrue(device.details().get("scopes").contains("HallwayCam"));
    }

    @Test
    void usesFirstOfMultipleXAddrs() {
        String xml = """
                <ProbeMatch>
                  <Scopes>onvif://www.onvif.org/name/MultiAddrCam</Scopes>
                  <XAddrs>http://192.168.1.60/onvif/device_service http://[fe80::1]/onvif/device_service</XAddrs>
                </ProbeMatch>
                """;

        Optional<DiscoveredDevice> result = OnvifWsDiscoveryScanner.parseProbeMatch(xml);

        assertTrue(result.isPresent());
        assertEquals(URI.create("http://192.168.1.60/onvif/device_service"), result.get().address());
    }

    @Test
    void fallsBackToHostWhenNameScopeIsAbsent() {
        String xml = """
                <ProbeMatch>
                  <Scopes>onvif://www.onvif.org/type/video_encoder</Scopes>
                  <XAddrs>http://10.0.0.5:8080/onvif/device_service</XAddrs>
                </ProbeMatch>
                """;

        Optional<DiscoveredDevice> result = OnvifWsDiscoveryScanner.parseProbeMatch(xml);

        assertTrue(result.isPresent());
        assertEquals("10.0.0.5", result.get().name());
    }

    @Test
    void decodesPercentEncodedNameScope() {
        String xml = """
                <ProbeMatch>
                  <Scopes>onvif://www.onvif.org/name/Front%20Door</Scopes>
                  <XAddrs>http://10.0.0.6/onvif/device_service</XAddrs>
                </ProbeMatch>
                """;

        Optional<DiscoveredDevice> result = OnvifWsDiscoveryScanner.parseProbeMatch(xml);

        assertTrue(result.isPresent());
        assertEquals("Front Door", result.get().name());
    }

    @Test
    void malformedOrEmptyResponsesAreSkippedWithoutThrowing() {
        assertFalse(OnvifWsDiscoveryScanner.parseProbeMatch(null).isPresent());
        assertFalse(OnvifWsDiscoveryScanner.parseProbeMatch("").isPresent());
        assertFalse(OnvifWsDiscoveryScanner.parseProbeMatch("   ").isPresent());
        assertFalse(OnvifWsDiscoveryScanner.parseProbeMatch("<not-even-xml").isPresent());
        // No XAddrs element at all.
        assertFalse(OnvifWsDiscoveryScanner.parseProbeMatch(
                "<ProbeMatch><Scopes>onvif://www.onvif.org/name/NoAddr</Scopes></ProbeMatch>").isPresent());
        // XAddrs present but blank.
        assertFalse(OnvifWsDiscoveryScanner.parseProbeMatch("<ProbeMatch><XAddrs>   </XAddrs></ProbeMatch>").isPresent());
        // XAddrs contains an unparsable URI (unescaped invalid percent-sequence).
        assertFalse(OnvifWsDiscoveryScanner.parseProbeMatch(
                "<ProbeMatch><XAddrs>http://10.0.0.1/onvif%zz</XAddrs></ProbeMatch>").isPresent());
        // Garbage payload, nothing resembling XML.
        assertFalse(OnvifWsDiscoveryScanner.parseProbeMatch("not xml at all, just noise").isPresent());
    }

    /**
     * Loopback integration test: a fake UDP "responder" thread waits for the
     * scanner's Probe datagram and replies with a canned ProbeMatch, proving
     * the send/receive/parse path end-to-end through the {@link
     * OnvifWsDiscoveryScanner#OnvifWsDiscoveryScanner(InetSocketAddress)}
     * test seam. Uses direct loopback UDP unicast (not multicast), so unlike
     * the mDNS loopback test this does not depend on multicast routing being
     * configured in the sandbox.
     */
    @Test
    void discoversADeviceThroughALoopbackFakeResponder() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (DatagramSocket responderSocket = new DatagramSocket(new InetSocketAddress(loopback, 0))) {
            int responderPort = responderSocket.getLocalPort();
            String probeMatchXml = """
                    <ProbeMatch>
                      <Scopes>onvif://www.onvif.org/name/LoopbackCam</Scopes>
                      <XAddrs>http://127.0.0.1:8081/onvif/device_service</XAddrs>
                    </ProbeMatch>
                    """;

            Thread responderThread = new Thread(() -> respondOnce(responderSocket, probeMatchXml), "onvif-fake-responder");
            responderThread.setDaemon(true);
            responderThread.start();

            OnvifWsDiscoveryScanner scanner =
                    new OnvifWsDiscoveryScanner(new InetSocketAddress(loopback, responderPort));

            List<DiscoveredDevice> found = scanner.scan(Duration.ofSeconds(2));

            assertEquals(1, found.size());
            DiscoveredDevice device = found.get(0);
            assertEquals("onvif", device.method());
            assertEquals("LoopbackCam", device.name());
            assertEquals(URI.create("http://127.0.0.1:8081/onvif/device_service"), device.address());
            assertEquals(new CategoryId("ip-camera"), device.suggestedCategory());
            assertNull(device.suggestedStream());

            responderThread.join(Duration.ofSeconds(2).toMillis());
        }
    }

    private static void respondOnce(DatagramSocket responderSocket, String probeMatchXml) {
        try {
            byte[] receiveBuffer = new byte[4096];
            DatagramPacket request = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            responderSocket.receive(request); // blocks for the scanner's Probe

            byte[] payload = probeMatchXml.getBytes(StandardCharsets.UTF_8);
            responderSocket.send(new DatagramPacket(payload, payload.length, request.getAddress(), request.getPort()));
        } catch (IOException e) {
            // Socket closed by test teardown, or scanner already gave up waiting: fine to stop.
        }
    }

    // -- full chain: UDP ProbeMatch (fake responder) -> HTTP follow-up (real loopback HttpServer) --

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
                  </trt:MediaUri>
                </trt:GetStreamUriResponse>
              </SOAP-ENV:Body>
            </SOAP-ENV:Envelope>
            """;

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
                        </tt:Media>
                      </tds:Capabilities>
                    </tds:GetCapabilitiesResponse>
                  </SOAP-ENV:Body>
                </SOAP-ENV:Envelope>
                """.formatted(baseUrl);
    }

    private static String probeMatchPointingAt(String deviceServiceUrl, String name) {
        return """
                <ProbeMatch>
                  <Scopes>onvif://www.onvif.org/name/%s</Scopes>
                  <XAddrs>%s</XAddrs>
                </ProbeMatch>
                """.formatted(name, deviceServiceUrl);
    }

    private static void respondSoap(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/soap+xml; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var responseBody = exchange.getResponseBody()) {
            responseBody.write(bytes);
        }
    }

    private static String requestBodyOf(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * End-to-end proof that {@link OnvifWsDiscoveryScanner#scan(Duration)} wires the WS-Discovery
     * {@code ProbeMatch} phase to the {@link OnvifDeviceClient} follow-up: a fake UDP responder
     * (as in {@link #discoversADeviceThroughALoopbackFakeResponder()}) points its {@code XAddrs}
     * at a real loopback {@link HttpServer} standing in for the camera's ONVIF services.
     */
    @Test
    void producesAPlayableRtspStreamThroughTheFullOnvifChain() throws Exception {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        String baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort();
        httpServer.createContext("/onvif/device_service",
                exchange -> respondSoap(exchange, 200, capabilitiesResponse(baseUrl)));
        httpServer.createContext("/onvif/media_service", exchange -> {
            String body = requestBodyOf(exchange);
            respondSoap(exchange, 200, body.contains("GetStreamUri") ? STREAM_URI_RESPONSE : PROFILES_RESPONSE);
        });
        httpServer.start();

        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (DatagramSocket responderSocket = new DatagramSocket(new InetSocketAddress(loopback, 0))) {
            int responderPort = responderSocket.getLocalPort();
            String probeMatchXml = probeMatchPointingAt(baseUrl + "/onvif/device_service", "ChainedCam");

            Thread responderThread = new Thread(() -> respondOnce(responderSocket, probeMatchXml), "onvif-chain-fake-responder");
            responderThread.setDaemon(true);
            responderThread.start();

            OnvifWsDiscoveryScanner scanner = new OnvifWsDiscoveryScanner(new InetSocketAddress(loopback, responderPort));
            List<DiscoveredDevice> found = scanner.scan(Duration.ofMillis(1000));

            assertEquals(1, found.size());
            DiscoveredDevice device = found.get(0);
            assertEquals(new CategoryId("ip-camera"), device.suggestedCategory());
            assertTrue(device.suggestedStream() != null, "expected a suggested stream, got: " + device);
            assertEquals("rtsp", device.suggestedStream().protocol());
            assertEquals(URI.create("rtsp://192.0.2.10:554/onvif1"), device.suggestedStream().uri());
            assertFalse(device.details().containsKey("note"));

            responderThread.join(Duration.ofSeconds(2).toMillis());
        } finally {
            httpServer.stop(0);
        }
    }

    @Test
    void keepsCandidateWithoutStreamAndNotesCredentialsWhenDeviceRequiresAuth() throws Exception {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        String baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort();
        httpServer.createContext("/onvif/device_service", exchange -> respondSoap(exchange, 401, "Unauthorized"));
        httpServer.start();

        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (DatagramSocket responderSocket = new DatagramSocket(new InetSocketAddress(loopback, 0))) {
            int responderPort = responderSocket.getLocalPort();
            String probeMatchXml = probeMatchPointingAt(baseUrl + "/onvif/device_service", "LockedCam");

            Thread responderThread = new Thread(() -> respondOnce(responderSocket, probeMatchXml), "onvif-auth-fake-responder");
            responderThread.setDaemon(true);
            responderThread.start();

            OnvifWsDiscoveryScanner scanner = new OnvifWsDiscoveryScanner(new InetSocketAddress(loopback, responderPort));
            List<DiscoveredDevice> found = scanner.scan(Duration.ofMillis(1000));

            assertEquals(1, found.size());
            DiscoveredDevice device = found.get(0);
            assertNull(device.suggestedStream());
            assertEquals("credentials required — stream URL cannot be suggested", device.details().get("note"));

            responderThread.join(Duration.ofSeconds(2).toMillis());
        } finally {
            httpServer.stop(0);
        }
    }

    @Test
    void keepsCandidateWithoutStreamOnMalformedCapabilitiesResponse() throws Exception {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        String baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort();
        httpServer.createContext("/onvif/device_service",
                exchange -> respondSoap(exchange, 200, "not xml at all, just noise"));
        httpServer.start();

        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (DatagramSocket responderSocket = new DatagramSocket(new InetSocketAddress(loopback, 0))) {
            int responderPort = responderSocket.getLocalPort();
            String probeMatchXml = probeMatchPointingAt(baseUrl + "/onvif/device_service", "GarbledCam");

            Thread responderThread = new Thread(() -> respondOnce(responderSocket, probeMatchXml), "onvif-malformed-fake-responder");
            responderThread.setDaemon(true);
            responderThread.start();

            OnvifWsDiscoveryScanner scanner = new OnvifWsDiscoveryScanner(new InetSocketAddress(loopback, responderPort));
            List<DiscoveredDevice> found = scanner.scan(Duration.ofMillis(1000));

            assertEquals(1, found.size());
            DiscoveredDevice device = found.get(0);
            assertNull(device.suggestedStream());
            assertFalse(device.details().containsKey("note"));

            responderThread.join(Duration.ofSeconds(2).toMillis());
        } finally {
            httpServer.stop(0);
        }
    }

    /**
     * Regression test mirroring {@code MdnsScannerLoopbackTest.scanReturnsWithinTimeoutPlusCallerGrace}:
     * a camera whose device service accepts the connection but never answers must not stall {@link
     * OnvifWsDiscoveryScanner#scan(Duration)} past {@code DiscoveryService}'s {@code timeout + 200ms}
     * grace — the per-device follow-up budget (see class javadoc) is a real {@code HttpClient}
     * request timeout, not merely a best-effort hint.
     */
    @Test
    void scanReturnsWithinTimeoutPlusCallerGraceEvenWhenTheDeviceNeverResponds() throws Exception {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        String baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort();
        httpServer.createContext("/onvif/device_service", exchange -> {
            try {
                Thread.sleep(30_000); // accepts the connection, never writes a response
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        httpServer.start();

        InetAddress loopback = InetAddress.getLoopbackAddress();
        Duration timeout = Duration.ofMillis(800);
        Duration callerGrace = Duration.ofMillis(200); // mirrors DefaultDiscoveryService.GRACE_PERIOD

        try (DatagramSocket responderSocket = new DatagramSocket(new InetSocketAddress(loopback, 0))) {
            int responderPort = responderSocket.getLocalPort();
            String probeMatchXml = probeMatchPointingAt(baseUrl + "/onvif/device_service", "SlowCam");

            Thread responderThread = new Thread(() -> respondOnce(responderSocket, probeMatchXml), "onvif-slow-fake-responder");
            responderThread.setDaemon(true);
            responderThread.start();

            OnvifWsDiscoveryScanner scanner = new OnvifWsDiscoveryScanner(new InetSocketAddress(loopback, responderPort));

            long startNanos = System.nanoTime();
            List<DiscoveredDevice> found = scanner.scan(timeout);
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

            assertTrue(elapsedMillis < timeout.plus(callerGrace).toMillis(),
                    "onvif scan took " + elapsedMillis + "ms for an " + timeout.toMillis()
                            + "ms timeout, which exceeds the caller's " + callerGrace.toMillis()
                            + "ms grace window; found=" + found);
            assertEquals(1, found.size());
            assertNull(found.get(0).suggestedStream());

            responderThread.join(Duration.ofSeconds(2).toMillis());
        } finally {
            httpServer.stop(0);
        }
    }
}
