package com.drones.vision.perception.application.pipeline;

import com.drones.vision.perception.domain.model.FeedSpec;
import com.drones.vision.perception.domain.port.FeedTransmitterPort;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeedTransmitterRegistryTest {

    private static FeedSpec spec(String protocol) {
        return new FeedSpec(protocol, URI.create("file:///tmp/clip.mp4"), Map.of());
    }

    @Test
    void selectsFirstTransmitterThatSupportsTheSpec() {
        FeedTransmitterPort rtsp = mock(FeedTransmitterPort.class);
        FeedTransmitterPort mjpeg = mock(FeedTransmitterPort.class);
        when(rtsp.supports(any())).thenReturn(false);
        when(mjpeg.supports(any())).thenReturn(true);

        FeedTransmitterRegistry registry = new FeedTransmitterRegistry(List.of(rtsp, mjpeg));

        assertSame(mjpeg, registry.transmitterFor(spec("mjpeg")));
    }

    @Test
    void prefersEarlierRegisteredTransmitterWhenMultipleSupport() {
        FeedTransmitterPort first = mock(FeedTransmitterPort.class);
        FeedTransmitterPort second = mock(FeedTransmitterPort.class);
        when(first.supports(any())).thenReturn(true);
        when(second.supports(any())).thenReturn(true);

        FeedTransmitterRegistry registry = new FeedTransmitterRegistry(List.of(first, second));

        assertSame(first, registry.transmitterFor(spec("rtsp")));
    }

    @Test
    void throwsIllegalArgumentExceptionNamingTheProtocolWhenNoTransmitterMatches() {
        FeedTransmitterPort rtsp = mock(FeedTransmitterPort.class);
        when(rtsp.supports(any())).thenReturn(false);

        FeedTransmitterRegistry registry = new FeedTransmitterRegistry(List.of(rtsp));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> registry.transmitterFor(spec("mjpeg")));
        assertTrue(e.getMessage().contains("mjpeg"), "expected message to name the unsupported protocol: "
                + e.getMessage());
    }
}
