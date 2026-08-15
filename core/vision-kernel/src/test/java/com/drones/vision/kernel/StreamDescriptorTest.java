package com.drones.vision.kernel;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StreamDescriptorTest {

    @Test
    void optionsAreDefensivelyCopied() {
        Map<String, String> options = new HashMap<>();
        options.put("width", "640");

        StreamDescriptor descriptor = new StreamDescriptor("sim", URI.create("sim://cam"), options);

        options.put("height", "480");

        assertEquals(1, descriptor.options().size(), "later mutation of the source map must not affect the descriptor");
        assertThrows(UnsupportedOperationException.class, () -> descriptor.options().put("fps", "30"),
                "returned options map must be immutable");
    }

    @Test
    void rejectsBlankOrUppercaseProtocol() {
        URI uri = URI.create("sim://cam");
        Map<String, String> options = Map.of();

        assertThrows(IllegalArgumentException.class, () -> new StreamDescriptor(null, uri, options));
        assertThrows(IllegalArgumentException.class, () -> new StreamDescriptor("", uri, options));
        assertThrows(IllegalArgumentException.class, () -> new StreamDescriptor("RTSP", uri, options));
    }

    @Test
    void rejectsNullUriOrOptions() {
        assertThrows(IllegalArgumentException.class, () -> new StreamDescriptor("rtsp", null, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new StreamDescriptor("rtsp", URI.create("rtsp://cam"), null));
    }
}
