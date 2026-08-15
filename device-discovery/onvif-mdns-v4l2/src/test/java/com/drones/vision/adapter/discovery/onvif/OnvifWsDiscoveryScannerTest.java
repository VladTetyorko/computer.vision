package com.drones.vision.adapter.discovery.onvif;

import com.drones.vision.kernel.CategoryId;
import com.drones.vision.warehouse.domain.model.DiscoveredDevice;
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
}
