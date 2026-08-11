package com.drones.vision.perception.domain.model;

import com.drones.vision.kernel.StreamId;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoFrameTest {

    private VideoFrame frame(ByteBuffer data) {
        return new VideoFrame(StreamId.random(), 0L, Instant.now(), 640, 480, PixelFormat.JPEG, data);
    }

    @Test
    void dataAccessorReturnsReadOnlyBuffer() {
        VideoFrame f = frame(ByteBuffer.wrap(new byte[]{1, 2, 3, 4}));

        ByteBuffer view = f.data();

        assertTrue(view.isReadOnly(), "VideoFrame#data() must return a read-only buffer");
        assertThrows(ReadOnlyBufferException.class, () -> view.put((byte) 9));
    }

    @Test
    void dataAccessorReturnsFreshDuplicateEachCall() {
        VideoFrame f = frame(ByteBuffer.wrap(new byte[]{1, 2, 3, 4}));

        ByteBuffer first = f.data();
        ByteBuffer second = f.data();

        assertNotSame(first, second, "each call to data() must return a distinct buffer instance");

        // Advancing the position of one accessor's buffer must not affect another.
        first.get();
        assertEquals(0, second.position(), "advancing one accessor's buffer must not disturb another's position");
        assertEquals(1, first.position());
    }

    @Test
    void mutatingSourceBufferAfterConstructionDoesNotMutateStoredContent() {
        // asReadOnlyBuffer() shares backing memory, so mutating the *content* the
        // original buffer points at after read-only wrapping would still be visible;
        // what must be prevented is the frame's own accessor being used to mutate it,
        // and the frame holding an independent position/limit/mark from the source.
        ByteBuffer source = ByteBuffer.wrap(new byte[]{1, 2, 3, 4});
        VideoFrame f = frame(source);

        // Advancing the caller's own buffer position must not move the frame's view.
        source.get();
        source.get();

        assertEquals(0, f.data().position(), "frame's buffer position must be independent of the source buffer");
    }

    @Test
    void constructorRejectsInvalidArguments() {
        StreamId streamId = StreamId.random();
        Instant now = Instant.now();
        ByteBuffer data = ByteBuffer.wrap(new byte[]{1});

        assertThrows(IllegalArgumentException.class,
                () -> new VideoFrame(null, 0L, now, 640, 480, PixelFormat.JPEG, data));
        assertThrows(IllegalArgumentException.class,
                () -> new VideoFrame(streamId, -1L, now, 640, 480, PixelFormat.JPEG, data));
        assertThrows(IllegalArgumentException.class,
                () -> new VideoFrame(streamId, 0L, null, 640, 480, PixelFormat.JPEG, data));
        assertThrows(IllegalArgumentException.class,
                () -> new VideoFrame(streamId, 0L, now, 0, 480, PixelFormat.JPEG, data));
        assertThrows(IllegalArgumentException.class,
                () -> new VideoFrame(streamId, 0L, now, 640, 0, PixelFormat.JPEG, data));
        assertThrows(IllegalArgumentException.class,
                () -> new VideoFrame(streamId, 0L, now, 640, 480, null, data));
        assertThrows(IllegalArgumentException.class,
                () -> new VideoFrame(streamId, 0L, now, 640, 480, PixelFormat.JPEG, null));
    }
}
