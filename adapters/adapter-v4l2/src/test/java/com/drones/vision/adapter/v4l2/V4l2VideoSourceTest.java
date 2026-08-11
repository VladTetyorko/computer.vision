package com.drones.vision.adapter.v4l2;

import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code supports()} matrix and construction/validation for {@link
 * V4l2VideoSource} -- no real device/grabber I/O (that lives in {@link
 * V4l2LoopbackIntegrationTest}).
 */
class V4l2VideoSourceTest {

    @Test
    void supportsTheExactHandshakeAdapterDiscoveryEmits() {
        V4l2VideoSource source = new V4l2VideoSource();

        // adapter-discovery's V4l2Scanner emits exactly this shape (protocol "v4l2",
        // uri = file:/dev/videoN) -- see V4l2ScannerTest#findsNamedAndUnnamedVideoNodesAndIgnoresNonVideoFiles.
        // supports() must accept it verbatim for "Discover -> register -> stream" to work.
        assertTrue(source.supports(new StreamDescriptor("v4l2", URI.create("file:/dev/video0"), Map.of())));
        assertTrue(source.supports(new StreamDescriptor("v4l2", URI.create("file:/dev/video7"), Map.of())));
    }

    @Test
    void supportsAnyFileSchemeUriRegardlessOfPathShape() {
        V4l2VideoSource source = new V4l2VideoSource();

        assertTrue(source.supports(new StreamDescriptor("v4l2", URI.create("file:///dev/video0"), Map.of())),
                "a triple-slash file: URI (equivalent path, different literal form) must also be accepted");
    }

    @Test
    void rejectsTheOriginallyProposedUsbProtocolString() {
        V4l2VideoSource source = new V4l2VideoSource();

        // docs/plans/done/MVP2-PLAN.md X-b's original brief proposed protocol "usb" -- deliberately NOT
        // supported, since discovery never emits it; see class javadoc for the full writeup.
        assertFalse(source.supports(new StreamDescriptor("usb", URI.create("file:/dev/video0"), Map.of())));
    }

    @Test
    void rejectsTheOriginallyProposedV4l2SchemeUri() {
        V4l2VideoSource source = new V4l2VideoSource();

        // The brief also proposed uri = v4l2:///dev/videoN -- a v4l2: scheme URI, not file: --
        // also deliberately not supported for the same reason.
        assertFalse(source.supports(new StreamDescriptor("v4l2", URI.create("v4l2:///dev/video0"), Map.of())));
    }

    @Test
    void rejectsANonV4l2Protocol() {
        V4l2VideoSource source = new V4l2VideoSource();

        assertFalse(source.supports(new StreamDescriptor("rtsp", URI.create("file:/dev/video0"), Map.of())));
        assertFalse(source.supports(new StreamDescriptor("sim", URI.create("sim://demo"), Map.of())));
        assertFalse(source.supports(new StreamDescriptor("mjpeg", URI.create("http://cam/stream"), Map.of())));
    }

    @Test
    void rejectsANonFileUriScheme() {
        V4l2VideoSource source = new V4l2VideoSource();

        assertFalse(source.supports(new StreamDescriptor("v4l2", URI.create("http://camera.local/video0"), Map.of())));
    }

    @Test
    void rejectsANullDescriptor() {
        assertFalse(new V4l2VideoSource().supports(null));
    }

    @Test
    void openRejectsAnUnsupportedDescriptor() {
        V4l2VideoSource source = new V4l2VideoSource();
        StreamDescriptor descriptor = new StreamDescriptor("usb", URI.create("file:/dev/video0"), Map.of());

        assertThrows(IllegalArgumentException.class, () -> source.open(StreamId.random(), descriptor));
    }

    @Test
    void closeOnAnUnknownOrUnopenedStreamIsANoop() {
        V4l2VideoSource source = new V4l2VideoSource();

        assertDoesNotThrow(() -> source.close(StreamId.random()));
    }

    @Test
    void openAnyRejectsNullIdOrUri() {
        V4l2VideoSource source = new V4l2VideoSource();

        assertThrows(IllegalArgumentException.class,
                () -> source.openAny(null, URI.create("file:/dev/video0"), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> source.openAny(StreamId.random(), null, Map.of()));
    }

    @Test
    void constructorAcceptsExplicitBufferCapacityAndCloseJoinTimeout() {
        assertDoesNotThrow(() -> new V4l2VideoSource(1, 1L));
    }

    @Test
    void constructorRejectsANonPositiveBufferCapacity() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new V4l2VideoSource(0, V4l2VideoSource.CLOSE_JOIN_TIMEOUT_MILLIS));
        assertTrue(ex.getMessage().contains("publisherBufferCapacity"),
                "message should mention publisherBufferCapacity: " + ex.getMessage());
    }

    @Test
    void constructorRejectsANonPositiveCloseJoinTimeout() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new V4l2VideoSource(V4l2VideoSource.PUBLISHER_BUFFER_CAPACITY, 0L));
        assertTrue(ex.getMessage().contains("closeJoinTimeoutMillis"),
                "message should mention closeJoinTimeoutMillis: " + ex.getMessage());
    }
}
