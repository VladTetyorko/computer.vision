package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FeedSpecTest {

    @Test
    void optionsAreDefensivelyCopied() {
        Map<String, String> options = new HashMap<>();
        options.put("loop", "true");

        FeedSpec spec = new FeedSpec("rtsp", URI.create("file:///tmp/clip.mp4"), options);

        options.put("extra", "value");

        assertEquals(1, spec.options().size(), "later mutation of the source map must not affect the spec");
        assertThrows(UnsupportedOperationException.class, () -> spec.options().put("fps", "30"),
                "returned options map must be immutable");
    }

    @Test
    void rejectsBlankOrUppercaseProtocol() {
        URI source = URI.create("file:///tmp/clip.mp4");
        Map<String, String> options = Map.of();

        assertThrows(IllegalArgumentException.class, () -> new FeedSpec(null, source, options));
        assertThrows(IllegalArgumentException.class, () -> new FeedSpec("", source, options));
        assertThrows(IllegalArgumentException.class, () -> new FeedSpec("RTSP", source, options));
    }

    @Test
    void rejectsNullSourceOrOptions() {
        assertThrows(IllegalArgumentException.class, () -> new FeedSpec("rtsp", null, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new FeedSpec("rtsp", URI.create("file:///tmp/clip.mp4"), null));
    }
}
