package com.drones.vision.adapter.publishhls;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

/**
 * Measures a stream's actual source frame-arrival cadence before {@link MediamtxStreamPublisher}
 * starts its recorder, quantizes each frame's capture instant onto that measured grid for the
 * recorder's PTS/DTS, and watches for the source's cadence sustainedly drifting away from what the
 * recorder was configured with.
 *
 * <h2>Why cadence is measured, not fixed</h2>
 * Was: every recorder was started at a fixed {@link #DEFAULT_FRAME_RATE_FPS} (15.0) regardless of
 * the source's actual rate, and every timestamp was quantized onto that fixed 15fps grid (see
 * {@link #nextTimestampMicros}). A 30fps source got every frame bumped to the next 15fps slot, so
 * the published timeline advanced at half wall-clock speed — 2x slow motion. Fix: measure the
 * source's actual arrival cadence from the first {@value #CADENCE_MEASUREMENT_FRAMES} frames
 * (dropped, not encoded, so there is nothing to quantize onto the wrong grid yet), then start the
 * recorder — and quantize every subsequent timestamp — onto that <i>measured</i> grid instead of a
 * fixed assumption.
 *
 * <h2>PTS quantization bug and its fix</h2>
 * {@code FFmpegFrameRecorder.setTimestamp(long)} doesn't use the microsecond value verbatim — it
 * rounds it down to a whole frame number via {@code round(timestampMicros * frameRateFps /
 * 1_000_000)} before handing that integer PTS/DTS to the muxer. Two frames whose {@code
 * capturedAt} land under one frame period apart (routine with bursty {@link
 * java.util.concurrent.SubmissionPublisher} delivery, e.g. a scheduler catching up after a stall)
 * used to round to the <i>same</i> frame number → the muxer rejects the second write with {@code
 * av_interleaved_write_frame() error -22} (EINVAL, non-monotonic/duplicate DTS). Bumping the
 * microsecond value by 1 does <b>not</b> fix it — still rounds to the same frame number. The
 * actual fix in {@link #nextTimestampMicros}: track the last <i>frame number</i> actually emitted;
 * if the naturally-computed frame number would collide, advance to the next free slot and convert
 * that back to microseconds before returning — frame numbers are always strictly increasing, so
 * they never collide regardless of burst timing.
 *
 * <p>Not thread-safe — mirrors {@link MediamtxStreamPublisher.StreamState}'s own per-stream,
 * single-writer contract ({@link com.drones.vision.perception.domain.port.StreamPublisherPort} guarantees
 * calls for one {@code streamId} are never concurrent).
 */
final class CadenceEstimator {

    /**
     * Fallback frame rate used only when the source's measured cadence is degenerate (see {@link
     * #recordMeasurementSample}) — e.g. all observed {@code capturedAt} deltas are zero/identical,
     * as a naive test double might produce. A real source's frame rate is always measured; this is
     * not the assumption it used to be.
     */
    static final double DEFAULT_FRAME_RATE_FPS = 15.0;
    /** Number of frames whose {@code capturedAt} deltas are sampled to measure a stream's source cadence before its recorder starts. */
    static final int CADENCE_MEASUREMENT_FRAMES = 5;
    /** Sanity clamp bounds for the measured source frame rate handed to {@code setFrameRate}. */
    static final double MIN_MEASURED_FRAME_RATE_FPS = 1.0;
    static final double MAX_MEASURED_FRAME_RATE_FPS = 120.0;
    /** Post-start drift thresholds: sustained ratio of actual-vs-measured cadence outside {@code [LOW, HIGH]} triggers one INFO log. */
    static final double DRIFT_RATIO_HIGH = 1.5;
    /** Smoothing factor for the post-start actual-cadence EWMA used for drift detection; same shape as {@code StreamPipeline}'s measured-fps EWMA. */
    static final double DRIFT_EWMA_ALPHA = 0.2;
    /** How long the drift ratio must stay outside bounds, continuously, before it is logged (not a single blip). */
    static final Duration SUSTAINED_DRIFT_WINDOW = Duration.ofSeconds(2);

    private final int measurementFrames;
    private final double minMeasuredFrameRateFps;
    private final double maxMeasuredFrameRateFps;
    private final double driftRatioHigh;
    private final double driftRatioLow;
    private final double driftEwmaAlpha;
    private final Duration sustainedDriftWindow;
    private final double defaultFrameRateFps;

    private Instant firstCapturedAt;
    private long lastFrameNumber = -1L;

    // -- pre-start cadence measurement -------------------------------------
    private Instant lastMeasurementCapturedAt;
    private final long[] measurementDeltasMicros;
    private int measurementDeltaCount = 0;
    private boolean measurementComplete = false;
    private double measuredFrameRateFps;

    // -- post-start drift detection -----------------------------------------
    private Instant lastDriftCapturedAt;
    private double driftEwmaFps = -1.0;
    private Instant driftStartedAt;
    private boolean driftAlreadyLogged = false;

    CadenceEstimator() {
        this(PublishSettings.Cadence.defaults());
    }

    /**
     * @param cadence {@code vision.publish.cadence.*} (docs/plans/active/LAYERING-REFACTOR-PLAN.md wave F3) —
     *                replaces this class's own {@code CADENCE_MEASUREMENT_FRAMES}/{@code
     *                MIN_MEASURED_FRAME_RATE_FPS}/{@code MAX_MEASURED_FRAME_RATE_FPS}/{@code
     *                DRIFT_RATIO_HIGH}/{@code DRIFT_EWMA_ALPHA}/{@code SUSTAINED_DRIFT_WINDOW}/{@code
     *                DEFAULT_FRAME_RATE_FPS} constants.
     */
    CadenceEstimator(PublishSettings.Cadence cadence) {
        this.measurementFrames = cadence.measurementFrames();
        this.minMeasuredFrameRateFps = cadence.minMeasuredFrameRateFps();
        this.maxMeasuredFrameRateFps = cadence.maxMeasuredFrameRateFps();
        this.driftRatioHigh = cadence.driftRatioHigh();
        this.driftRatioLow = 1.0 / cadence.driftRatioHigh();
        this.driftEwmaAlpha = cadence.driftEwmaAlpha();
        this.sustainedDriftWindow = cadence.sustainedDriftWindow();
        this.defaultFrameRateFps = cadence.defaultFrameRateFps();
        this.measurementDeltasMicros = new long[measurementFrames - 1];
        this.measuredFrameRateFps = defaultFrameRateFps;
    }

    /**
     * Monotonic, never-backwards microsecond timestamp relative to this stream's first published
     * frame — quantized so it can never collide with the previous frame once FFmpeg gets hold of
     * it. See this class's javadoc, "PTS quantization bug and its fix", for the full rationale.
     */
    long nextTimestampMicros(Instant capturedAt, double frameRateFps) {
        if (firstCapturedAt == null) {
            firstCapturedAt = capturedAt;
        }
        long relativeMicros = Duration.between(firstCapturedAt, capturedAt).toNanos() / 1000L;
        long frameNumber = Math.round(relativeMicros * frameRateFps / 1_000_000.0);
        if (frameNumber <= lastFrameNumber) {
            frameNumber = lastFrameNumber + 1;
            relativeMicros = Math.round(frameNumber * 1_000_000.0 / frameRateFps);
        }
        lastFrameNumber = frameNumber;
        return relativeMicros;
    }

    /**
     * Feeds one frame's {@code capturedAt} into the pre-start cadence measurement. Must be called
     * once per frame, in arrival order, while the owning stream has no recorder yet.
     *
     * @return {@code true} once {@value #CADENCE_MEASUREMENT_FRAMES} frames have been observed and
     *         {@link #measuredFrameRateFps()} is ready to read (the caller may now start the
     *         recorder and write <b>this same</b> frame); {@code false} while still measuring,
     *         meaning the caller must drop this frame. Reconnect note: if a measurement already
     *         completed once for this stream, this returns {@code true} immediately without
     *         re-measuring — the previously measured rate is reused so a reconnect doesn't cost
     *         another burst of dropped frames (mirrors {@link #firstCapturedAt}/{@link
     *         #lastFrameNumber} never resetting on reconnect either).
     */
    boolean recordMeasurementSample(Instant capturedAt) {
        if (measurementComplete) {
            return true;
        }
        if (lastMeasurementCapturedAt != null && measurementDeltaCount < measurementDeltasMicros.length) {
            long deltaMicros = Duration.between(lastMeasurementCapturedAt, capturedAt).toNanos() / 1000L;
            measurementDeltasMicros[measurementDeltaCount++] = deltaMicros;
        }
        lastMeasurementCapturedAt = capturedAt;
        if (measurementDeltaCount < measurementDeltasMicros.length) {
            return false;
        }
        measuredFrameRateFps = computeMeasuredFrameRateFps();
        measurementComplete = true;
        return true;
    }

    /** @return the source frame rate this stream's recorder was (or will be) configured with. */
    double measuredFrameRateFps() {
        return measuredFrameRateFps;
    }

    /**
     * Median of the {@value #CADENCE_MEASUREMENT_FRAMES}{@code -1} inter-arrival deltas collected
     * by {@link #recordMeasurementSample}, converted to fps and clamped to {@code [}{@link
     * #MIN_MEASURED_FRAME_RATE_FPS}{@code ,}{@link #MAX_MEASURED_FRAME_RATE_FPS}{@code ]}. Median,
     * not mean: robust against a single anomalous gap (e.g. a scheduler's first tick taking longer
     * than steady state) skewing the estimate from just four samples. Falls back to {@link
     * #DEFAULT_FRAME_RATE_FPS} when the median delta is non-positive — a degenerate source (all
     * {@code capturedAt} identical or non-monotonic) has no measurable rate at all, so there's
     * nothing better to fall back on than the historical fixed assumption.
     */
    private double computeMeasuredFrameRateFps() {
        long[] sorted = measurementDeltasMicros.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        long medianMicros = (sorted.length % 2 == 0)
                ? Math.round((sorted[mid - 1] + sorted[mid]) / 2.0)
                : sorted[mid];
        if (medianMicros <= 0) {
            return defaultFrameRateFps;
        }
        double fps = 1_000_000.0 / medianMicros;
        return clamp(fps, minMeasuredFrameRateFps, maxMeasuredFrameRateFps);
    }

    /**
     * Tracks the stream's actual post-start arrival cadence (a separate EWMA from the one-time
     * pre-start measurement above) and reports whether it has stayed outside {@code [driftRatioLow,
     * driftRatioHigh]} of {@link #measuredFrameRateFps()}, continuously, for at least {@code
     * sustainedDriftWindow}. Deliberately does not react to a single blip (bursty delivery is
     * routine, see {@link #nextTimestampMicros} javadoc) — only a <i>sustained</i> mismatch, meaning
     * the encoder's fixed {@code setFrameRate}/GOP no longer match the source, is worth surfacing.
     * Reports at most once per stream: encoder restart mid-stream is out of scope (known limitation,
     * see MODULE.md), so repeating the signal would add noise without giving the operator anything
     * new to act on.
     *
     * @return {@code true} the one time sustained drift is first detected
     */
    boolean observeSustainedDrift(Instant capturedAt) {
        if (driftAlreadyLogged) {
            return false;
        }
        if (lastDriftCapturedAt != null) {
            long deltaMicros = Duration.between(lastDriftCapturedAt, capturedAt).toNanos() / 1000L;
            if (deltaMicros > 0) {
                double instantaneousFps = 1_000_000.0 / deltaMicros;
                driftEwmaFps = driftEwmaFps < 0
                        ? instantaneousFps
                        : driftEwmaAlpha * instantaneousFps + (1 - driftEwmaAlpha) * driftEwmaFps;
            }
        }
        lastDriftCapturedAt = capturedAt;

        if (driftEwmaFps < 0) {
            return false;
        }
        double ratio = driftEwmaFps / measuredFrameRateFps;
        boolean drifting = ratio > driftRatioHigh || ratio < driftRatioLow;
        if (!drifting) {
            driftStartedAt = null;
            return false;
        }
        if (driftStartedAt == null) {
            driftStartedAt = capturedAt;
            return false;
        }
        if (Duration.between(driftStartedAt, capturedAt).compareTo(sustainedDriftWindow) >= 0) {
            driftAlreadyLogged = true;
            return true;
        }
        return false;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
