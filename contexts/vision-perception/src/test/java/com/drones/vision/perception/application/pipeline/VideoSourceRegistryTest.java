package com.drones.vision.perception.application.pipeline;

import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.perception.domain.port.VideoSourcePort;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.drones.vision.perception.application.stream.UnsupportedProtocolException;

class VideoSourceRegistryTest {

    private static StreamDescriptor descriptor(String protocol) {
        return new StreamDescriptor(protocol, URI.create(protocol + "://cam"), Map.of());
    }

    @Test
    void selectsFirstAdapterThatSupportsTheDescriptor() {
        VideoSourcePort rtsp = mock(VideoSourcePort.class);
        VideoSourcePort sim = mock(VideoSourcePort.class);
        when(rtsp.supports(any())).thenReturn(false);
        when(sim.supports(any())).thenReturn(true);

        VideoSourceRegistry registry = new VideoSourceRegistry(List.of(rtsp, sim));

        assertSame(sim, registry.sourceFor(descriptor("sim")));
    }

    @Test
    void prefersEarlierRegisteredAdapterWhenMultipleSupport() {
        VideoSourcePort first = mock(VideoSourcePort.class);
        VideoSourcePort second = mock(VideoSourcePort.class);
        when(first.supports(any())).thenReturn(true);
        when(second.supports(any())).thenReturn(true);

        VideoSourceRegistry registry = new VideoSourceRegistry(List.of(first, second));

        assertSame(first, registry.sourceFor(descriptor("sim")));
    }

    @Test
    void throwsUnsupportedProtocolExceptionWhenNoAdapterMatches() {
        VideoSourcePort rtsp = mock(VideoSourcePort.class);
        when(rtsp.supports(any())).thenReturn(false);

        VideoSourceRegistry registry = new VideoSourceRegistry(List.of(rtsp));

        UnsupportedProtocolException e =
                assertThrows(UnsupportedProtocolException.class, () -> registry.sourceFor(descriptor("mjpeg")));
        assertEquals("mjpeg", e.protocol());
    }
}
