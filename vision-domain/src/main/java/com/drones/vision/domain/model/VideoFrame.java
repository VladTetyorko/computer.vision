package com.drones.vision.domain.model;

import java.nio.ByteBuffer;
import java.time.Instant;

/**
 * A single frame of video: immutable metadata plus a zero-copy-friendly payload.
 *
 * <p>{@code sequence} is a per-stream monotonic counter and {@code capturedAt}
 * is the capture timestamp; together with {@code streamId} they let a
 * {@link Detection} (via {@link DetectionResult}) reference a frame instead of
 * holding it, so detections can be persisted/replayed independently of frame
 * lifetime and frames can be released (or returned to a pool) as soon as they
 * are no longer needed by the video path.
 *
 * <h2>Buffer ownership and thread-safety</h2>
 * {@code data} is never exposed as the caller's original, mutable buffer.
 * The compact constructor stores {@code data.asReadOnlyBuffer()}: a
 * read-only view over the same backing memory with its own position, limit,
 * and mark, independent of the buffer the source adapter is still writing
 * into. This allows adapters to use direct or pooled buffers at high FPS
 * without incurring a copy (zero-copy-friendly) while guaranteeing that no
 * consumer of a {@code VideoFrame} can ever mutate the underlying pixel
 * data.
 *
 * <p>The {@link #data()} accessor does not return that stored buffer
 * directly — it returns {@code data.duplicate()}, a further independent
 * view (sharing content, but with its own position/limit/mark) that
 * preserves the read-only property. This matters because a {@link
 * java.nio.Buffer}'s position is mutable state: if the same {@code
 * ByteBuffer} instance were handed to multiple readers (e.g. the video
 * publisher and the detection pipeline reading the same frame concurrently,
 * or a single reader calling {@code data()} more than once), one reader
 * advancing the position would corrupt reads for every other holder of the
 * frame. Duplicating on every access means every caller gets its own
 * cursor into the same immutable bytes, making {@code VideoFrame} safe to
 * share freely across threads and to read repeatedly.
 *
 * @param streamId   stream this frame belongs to
 * @param sequence   per-stream monotonic sequence number; must be non-negative
 * @param capturedAt capture timestamp
 * @param width      frame width in pixels; must be positive
 * @param height     frame height in pixels; must be positive
 * @param format     pixel encoding of {@code data}
 * @param data       frame payload; stored as a read-only buffer, see class javadoc
 */
public record VideoFrame(StreamId streamId, long sequence, Instant capturedAt, int width, int height,
                          PixelFormat format, ByteBuffer data) {

    public VideoFrame {
        if (streamId == null) {
            throw new IllegalArgumentException("VideoFrame streamId must not be null");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("VideoFrame sequence must not be negative: " + sequence);
        }
        if (capturedAt == null) {
            throw new IllegalArgumentException("VideoFrame capturedAt must not be null");
        }
        if (width <= 0) {
            throw new IllegalArgumentException("VideoFrame width must be positive: " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("VideoFrame height must be positive: " + height);
        }
        if (format == null) {
            throw new IllegalArgumentException("VideoFrame format must not be null");
        }
        if (data == null) {
            throw new IllegalArgumentException("VideoFrame data must not be null");
        }
        data = data.asReadOnlyBuffer();
    }

    /**
     * Returns a fresh, read-only, independent view over this frame's pixel
     * data.
     *
     * <p>Every call returns a new {@link ByteBuffer#duplicate()} of the
     * stored read-only buffer: the returned buffer shares the same backing
     * bytes but has its own position, limit, and mark, and attempts to
     * write to it throw {@link java.nio.ReadOnlyBufferException}. Callers
     * are free to read (e.g. {@code get}, mark/reset, slice) without
     * affecting any other reader of this frame or any subsequent call to
     * this accessor.
     *
     * @return a read-only duplicate of this frame's payload
     */
    @Override
    public ByteBuffer data() {
        return data.duplicate();
    }
}
