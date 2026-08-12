package com.drones.vision.adapter.publishhls;

import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link MediamtxLiveFrameGrabber} that don't need a real mediamtx server. A real
 * end-to-end grab against a live proxied path belongs in {@link
 * MediamtxProxyPublisherDockerIntegrationTest} (docker-gated) — a live frame grab is meaningless
 * without something actually publishing to the path first.
 */
class MediamtxLiveFrameGrabberTest {

    /** Port 1 on loopback refuses connections immediately on Linux — same idiom this module's other tests use. */
    @Test
    void grabReturnsEmptyAndDoesNotThrowWhenMediamtxIsUnreachable() {
        MediamtxLiveFrameGrabber grabber = new MediamtxLiveFrameGrabber(URI.create("rtsp://127.0.0.1:1"));

        Optional<VideoFrame> result = assertDoesNotThrow(() -> grabber.grab(StreamId.random()));

        assertEquals(Optional.empty(), result);
    }

    @Test
    void grabReturnsEmptyAndDoesNotThrowForAMalformedRtspBase() {
        MediamtxLiveFrameGrabber grabber = new MediamtxLiveFrameGrabber(URI.create("not-a-real-uri"));

        Optional<VideoFrame> result = assertDoesNotThrow(() -> grabber.grab(StreamId.random()));

        assertEquals(Optional.empty(), result);
    }

    @Test
    void grabRejectsANullStreamId() {
        MediamtxLiveFrameGrabber grabber = new MediamtxLiveFrameGrabber(URI.create("rtsp://127.0.0.1:1"));

        assertThrows(NullPointerException.class, () -> grabber.grab(null));
    }

    @Test
    void constructorRejectsANullRtspBase() {
        assertThrows(NullPointerException.class, () -> new MediamtxLiveFrameGrabber(null));
    }
}
