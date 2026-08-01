package com.drones.vision.adapter.publishhls;

import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link MediamtxReplayFrameExtractor} that don't need a real mediamtx server — the
 * "no playback configured" and "mediamtx unreachable" honest-absence paths. A real end-to-end grab
 * against a running mediamtx belongs in {@link MediamtxDockerIntegrationTest} (docker-gated).
 */
class MediamtxReplayFrameExtractorTest {

    /**
     * A {@code null} {@code playbackBase} means recording/playback is not configured for this
     * deployment (mirrors {@link MediamtxStreamPublisher#playbackUrl}'s own unconfigured-base
     * posture) — {@link MediamtxReplayFrameExtractor#frameAt} must return {@link Optional#empty()}
     * without attempting any I/O at all.
     */
    @Test
    void frameAtReturnsEmptyWhenPlaybackBaseUnconfigured() {
        MediamtxReplayFrameExtractor extractor = new MediamtxReplayFrameExtractor(null);

        Optional<VideoFrame> result = extractor.frameAt(StreamId.random(), Instant.now());

        assertEquals(Optional.empty(), result);
    }

    /**
     * mediamtx is unreachable for the whole test (port 1 on loopback refuses connections
     * immediately on Linux, the same idiom {@link MediamtxStreamPublisherTest}'s own
     * unreachable-mediamtx test uses). {@link MediamtxReplayFrameExtractor#frameAt} must never
     * throw — an unreachable host, a 404 ("nothing recorded there"), or an undecodable response are
     * all the same honest {@link Optional#empty()} from this port's point of view.
     */
    @Test
    void frameAtReturnsEmptyAndDoesNotThrowWhenMediamtxIsUnreachable() {
        MediamtxReplayFrameExtractor extractor = new MediamtxReplayFrameExtractor(URI.create("http://127.0.0.1:1"));

        Optional<VideoFrame> result = assertDoesNotThrow(
                () -> extractor.frameAt(StreamId.random(), Instant.now()));

        assertEquals(Optional.empty(), result);
    }

    /**
     * A malformed base (no host component at all) must fail the same honest way as an unreachable
     * one — {@code frameAt} never throws just because the configured base itself is nonsense.
     */
    @Test
    void frameAtReturnsEmptyAndDoesNotThrowForAMalformedPlaybackBase() {
        MediamtxReplayFrameExtractor extractor = new MediamtxReplayFrameExtractor(URI.create("not-a-real-uri"));

        Optional<VideoFrame> result = assertDoesNotThrow(
                () -> extractor.frameAt(StreamId.random(), Instant.now()));

        assertEquals(Optional.empty(), result);
    }

    @Test
    void frameAtRejectsNullStreamId() {
        MediamtxReplayFrameExtractor extractor = new MediamtxReplayFrameExtractor(URI.create("http://localhost:19996"));

        assertThrows(NullPointerException.class, () -> extractor.frameAt(null, Instant.now()));
    }

    @Test
    void frameAtRejectsNullInstant() {
        MediamtxReplayFrameExtractor extractor = new MediamtxReplayFrameExtractor(URI.create("http://localhost:19996"));

        assertThrows(NullPointerException.class, () -> extractor.frameAt(StreamId.random(), null));
    }
}
