package com.drones.vision.warehouse.domain.model;

import com.drones.vision.kernel.CategoryId;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiscoveredDeviceTest {

    private static URI address() {
        return URI.create("rtsp://192.168.1.20:554/stream");
    }

    @Test
    void detailsAreDefensivelyCopied() {
        Map<String, String> details = new HashMap<>();
        details.put("scope", "onvif://www.onvif.org/type/video_encoder");

        DiscoveredDevice device =
                new DiscoveredDevice("onvif", "cam-1", address(), new CategoryId("ip-camera"), null, details);

        details.put("extra", "value");

        assertEquals(1, device.details().size(), "later mutation of the source map must not affect the candidate");
        assertThrows(UnsupportedOperationException.class, () -> device.details().put("x", "y"),
                "returned details map must be immutable");
    }

    @Test
    void suggestedCategoryAndStreamAreNullable() {
        DiscoveredDevice device = new DiscoveredDevice("v4l2", "video0", address(), null, null, Map.of());

        assertNull(device.suggestedCategory());
        assertNull(device.suggestedStream());
    }

    @Test
    void rejectsBlankMethodOrName() {
        assertThrows(IllegalArgumentException.class,
                () -> new DiscoveredDevice(null, "cam-1", address(), null, null, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DiscoveredDevice("", "cam-1", address(), null, null, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DiscoveredDevice("onvif", null, address(), null, null, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DiscoveredDevice("onvif", "", address(), null, null, Map.of()));
    }

    @Test
    void rejectsNullAddressOrDetails() {
        assertThrows(IllegalArgumentException.class,
                () -> new DiscoveredDevice("onvif", "cam-1", null, null, null, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DiscoveredDevice("onvif", "cam-1", address(), null, null, null));
    }
}
