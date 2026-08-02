package com.drones.vision.adapter.publishhls;

import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit-level checks of the {@link H264RecorderFactory#configureRecorder} seam. Constructing an
 * {@link FFmpegFrameRecorder} only assigns fields — no network I/O happens until {@code start()},
 * which none of these tests ever call — so this needs neither a live mediamtx nor even a reachable
 * socket. (Moved out of {@code MediamtxStreamPublisherTest} when {@code configureRecorder} and its
 * constants were split into this class, docs/LAYERING-REFACTOR-PLAN.md §5.1.)
 */
class H264RecorderFactoryTest {

    /**
     * Regression test for the real bug this class was fixed for: the recorder used to always
     * start at a fixed 15.0fps, so a 30fps source got every timestamp bumped onto the 15fps grid
     * and played back at half wall-clock speed. Whatever fps the pre-start cadence measurement
     * ({@link CadenceEstimator}) produces must reach both {@code setFrameRate} and a
     * proportionally-sized GOP (not the old fixed 15fps).
     */
    @Test
    void configureRecorderAppliesMeasuredFrameRateAndProportionalGop() {
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder("rtsp://127.0.0.1:1/ignored", 64, 48);

        H264RecorderFactory.configureRecorder(recorder, 30.0);

        assertEquals(30.0, recorder.getFrameRate());
        // GOP_SECONDS(1, docs/MVP2-PLAN.md V-a: was 2) * measured fps -- a 1s
        // keyframe interval matches mediamtx's own 1s hlsSegmentDuration, so
        // an HLS segment is never coarser than 1s regardless of the source's
        // measured cadence.
        assertEquals(30, recorder.getGopSize());
    }

    /**
     * Regression guard for the pixelation bug: without an explicit rate
     * control, {@link FFmpegFrameRecorder} encodes at its ~400 kbps default,
     * which macroblocks 720p footage (and the detection boxes burned into
     * it). CRF mode plus a VBV cap must reach the encoder's option map.
     */
    @Test
    void configureRecorderUsesCrfRateControlNotTheDefault400kbps() {
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder("rtsp://127.0.0.1:1/ignored", 64, 48);

        H264RecorderFactory.configureRecorder(recorder, 30.0);

        assertEquals(H264RecorderFactory.X264_CRF, recorder.getVideoOption("crf"));
        assertEquals(H264RecorderFactory.X264_MAXRATE_BITS_PER_SECOND, recorder.getVideoOption("maxrate"));
        assertEquals(H264RecorderFactory.X264_BUFSIZE_BITS, recorder.getVideoOption("bufsize"));
        assertEquals("veryfast", recorder.getVideoOption("preset"));
    }

    /**
     * docs/MVP2-PLAN.md V-a latency audit: {@code tune=zerolatency} (which
     * x264 itself expands to {@code --bframes 0 --no-mbtree --sync-lookahead
     * 0 --rc-lookahead 0 --force-cfr}, verified against x264's own source —
     * see {@code H264RecorderFactory.configureRecorder}'s javadoc) is
     * the single option responsible for zero B-frame reordering delay and
     * zero rate-control lookahead buffering; {@code setMaxBFrames(0)} and
     * {@code sc_threshold=0} are this task's redundant-but-explicit,
     * independently testable reinforcements of the same intent (closed,
     * strictly periodic GOPs with no frame reordering) rather than new
     * behavior on their own.
     */
    @Test
    void configureRecorderMinimizesLatencyWithZerolatencyTuneNoBFramesAndClosedGop() {
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder("rtsp://127.0.0.1:1/ignored", 64, 48);

        H264RecorderFactory.configureRecorder(recorder, 30.0);

        assertEquals("zerolatency", recorder.getVideoOption("tune"));
        assertEquals(0, recorder.getMaxBFrames());
        assertEquals(H264RecorderFactory.X264_SCENECUT_THRESHOLD, recorder.getVideoOption("sc_threshold"));
    }

    /**
     * The keyframe interval an HLS segment is cut on can never be shorter
     * than the GOP itself (docs/MVP2-PLAN.md V-a) — this asserts the actual
     * wall-clock duration a GOP spans at a slow, real-world-plausible source
     * cadence is close to 1s (mediamtx's own configured {@code
     * hlsSegmentDuration}, see docker-compose.yml), not the old 2s.
     */
    @Test
    void configureRecorderGopSpansApproximatelyOneSecondAtTypicalFrameRates() {
        FFmpegFrameRecorder recorder15 = new FFmpegFrameRecorder("rtsp://127.0.0.1:1/ignored", 64, 48);
        H264RecorderFactory.configureRecorder(recorder15, 15.0);
        assertEquals(15, recorder15.getGopSize());

        FFmpegFrameRecorder recorder24 = new FFmpegFrameRecorder("rtsp://127.0.0.1:1/ignored", 64, 48);
        H264RecorderFactory.configureRecorder(recorder24, 24.0);
        assertEquals(24, recorder24.getGopSize());
    }
}
