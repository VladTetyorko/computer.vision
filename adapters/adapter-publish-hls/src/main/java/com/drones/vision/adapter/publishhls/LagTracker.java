package com.drones.vision.adapter.publishhls;

import java.util.Arrays;

/**
 * Pure, fixed-capacity ring buffer of one stream's recent capture→encode lag
 * samples (milliseconds), plus p50/p95 percentile readout. Backs {@link
 * MediamtxStreamPublisher}'s docs/plans/done/MVP2-PLAN.md V-c latency measurement: "lag"
 * here means the time between a {@code VideoFrame}'s {@code capturedAt} and
 * the moment it is handed to {@code FFmpegFrameRecorder.record} — i.e. the
 * capture→ingest→pipeline→overlay→publisher-handoff span, not anything
 * downstream of the encoder (mediamtx segmenting, HLS/WHEP transport, player
 * buffering — see this module's MODULE.md "V-c: latency measurement" section
 * for how to combine this number with the player's own "behind live"
 * estimate, V-b, to see the full glass-to-glass picture).
 *
 * <p>{@link #record(long)} is {@code O(1)} and allocates nothing — safe to
 * call on every published frame. {@link #p50()}/{@link #p95()} sort a
 * defensive copy of the window ({@code O(n log n)}), so they are meant to be
 * called only occasionally (e.g. once per periodic summary log), never
 * per-frame.
 *
 * <p>Not thread-safe — mirrors {@link MediamtxStreamPublisher.StreamState}'s
 * own per-stream, single-writer contract ({@link
 * com.drones.vision.perception.domain.port.StreamPublisherPort} guarantees calls
 * for one {@code streamId} are never concurrent).
 */
final class LagTracker {

    private final long[] samplesMillis;
    private int count;
    private int nextIndex;

    LagTracker(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.samplesMillis = new long[capacity];
    }

    /**
     * Records one lag sample, overwriting the oldest sample once the window
     * is full (drop-oldest, not drop-newest — the periodic summary should
     * always reflect the most recent behavior, not stall on an old spike).
     */
    void record(long lagMillis) {
        samplesMillis[nextIndex] = lagMillis;
        nextIndex = (nextIndex + 1) % samplesMillis.length;
        if (count < samplesMillis.length) {
            count++;
        }
    }

    /** @return the number of samples currently in the window (never exceeds the configured capacity). */
    int sampleCount() {
        return count;
    }

    /** @return the median (50th percentile) lag of the current window, or {@code 0} if no samples yet. */
    long p50() {
        return percentile(0.50);
    }

    /** @return the 95th percentile lag of the current window, or {@code 0} if no samples yet. */
    long p95() {
        return percentile(0.95);
    }

    /** Nearest-rank percentile (ceiling) over a sorted copy of the {@code count} valid samples. */
    private long percentile(double p) {
        if (count == 0) {
            return 0L;
        }
        long[] sorted = Arrays.copyOf(samplesMillis, count);
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(p * count) - 1;
        rank = Math.max(0, Math.min(count - 1, rank));
        return sorted[rank];
    }
}
