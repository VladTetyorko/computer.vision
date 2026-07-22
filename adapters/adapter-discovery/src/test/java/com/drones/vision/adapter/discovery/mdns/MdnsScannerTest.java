package com.drones.vision.adapter.discovery.mdns;

import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.DiscoveredDevice;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MdnsScanner}'s pure hit-to-candidate mapping logic
 * ({@code mapRtspHit}/{@code mapHttpHit}), independent of any real jmdns
 * network activity. See {@link MdnsScannerLoopbackTest} for the (best-effort,
 * possibly disabled) real register-and-find integration test.
 */
class MdnsScannerTest {

    @Test
    void rtspHitBecomesAnIpCameraWithARtspSuggestedStream() {
        DiscoveredDevice device = MdnsScanner.mapRtspHit("Front Door Cam", "192.168.1.20", 554);

        assertEquals("mdns", device.method());
        assertEquals("Front Door Cam", device.name());
        assertEquals(URI.create("rtsp://192.168.1.20:554/"), device.address());
        assertEquals(new CategoryId("ip-camera"), device.suggestedCategory());
        assertEquals("rtsp", device.suggestedStream().protocol());
        assertEquals(URI.create("rtsp://192.168.1.20:554/"), device.suggestedStream().uri());
    }

    @Test
    void httpHitHintingEsp32ByNameBecomesEsp32Cam() {
        DiscoveredDevice device = MdnsScanner.mapHttpHit("esp32-cam-kitchen", "192.168.1.30", 80);

        assertEquals(new CategoryId("esp32-cam"), device.suggestedCategory());
        assertEquals("mjpeg", device.suggestedStream().protocol());
        assertEquals(URI.create("http://192.168.1.30:80/"), device.suggestedStream().uri());
        assertTrue(device.details().get("note").contains(":81/stream"));
    }

    @Test
    void httpHitHintingEsp32ByHostBecomesEsp32Cam() {
        DiscoveredDevice device = MdnsScanner.mapHttpHit("camera-1", "espressif-a1b2c3.local", 80);

        assertEquals(new CategoryId("esp32-cam"), device.suggestedCategory());
    }

    @Test
    void httpHitHintingEsp32IsCaseInsensitive() {
        DiscoveredDevice device = MdnsScanner.mapHttpHit("ESP-CAM-01", "192.168.1.31", 80);

        assertEquals(new CategoryId("esp32-cam"), device.suggestedCategory());
    }

    @Test
    void plainHttpHitBecomesATypelessCandidate() {
        DiscoveredDevice device = MdnsScanner.mapHttpHit("some-printer", "192.168.1.40", 80);

        assertEquals("mdns", device.method());
        assertEquals(URI.create("http://192.168.1.40:80/"), device.address());
        assertNull(device.suggestedCategory());
        assertNull(device.suggestedStream());
    }

    @Test
    void methodKeyIsMdns() {
        assertEquals("mdns", new MdnsScanner().method());
    }
}
