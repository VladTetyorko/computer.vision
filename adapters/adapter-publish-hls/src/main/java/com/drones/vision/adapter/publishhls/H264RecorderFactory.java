package com.drones.vision.adapter.publishhls;

import org.bytedeco.javacv.FFmpegFrameRecorder;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;

/**
 * Creates and configures the {@link FFmpegFrameRecorder} {@link MediamtxStreamPublisher} pushes
 * encoded frames through: format/codec/rate-control options, plus the per-stream measured frame
 * rate and its derived GOP size (docs/MVP2-PLAN.md V-a).
 *
 * <p>Package-private, stateless. {@link #create} is the only method that touches the network
 * ({@link FFmpegFrameRecorder#start()}); {@link #configureRecorder} deliberately doesn't call it,
 * so a test can assert the exact options handed to a real recorder without needing a live
 * connection — the test seam {@link MediamtxStreamPublisher} used before this class existed.
 */
final class H264RecorderFactory {

    /**
     * docs/MVP2-PLAN.md V-a: an HLS segment can never be shorter than the keyframe interval it's
     * cut on, so this bounds how low mediamtx's own {@code hlsSegmentDuration} (compose {@code
     * MTX_HLSSEGMENTDURATION}, see docker-compose.yml) can usefully go — 1s here matches mediamtx's
     * own 1s default/configured segment duration exactly. Was 2s, which forced ~2s (or coarser,
     * once VBV/network jitter is added) segments regardless of mediamtx's own configuration, the
     * single biggest contributor to the "5-10s of latency" previously documented in README.md's
     * Quickstart.
     */
    static final int GOP_SECONDS = 1;
    /** Bounds the underlying TCP connect/I/O for the RTSP push, in microseconds, so a dead mediamtx can't hang a publish call. */
    static final String CONNECT_TIMEOUT_MICROS = "5000000";
    /**
     * x264 rate control is CRF (constant quality), not bitrate-targeted: without an explicit
     * target, {@link FFmpegFrameRecorder} falls back to its ~400 kbps default — thumbnail-grade for
     * 720p, which crushed both the video and the burned-in detection boxes into macroblocks
     * (observed live). CRF keeps quality constant regardless of resolution; 21 is visually clean
     * for surveillance-style footage.
     */
    static final String X264_CRF = "21";
    /** VBV cap so a busy scene can't flood the network: CRF decides quality, this bounds the worst-case bitrate. */
    static final String X264_MAXRATE_BITS_PER_SECOND = "6000000";
    /** VBV buffer, conventionally 2× maxrate; with {@code tune=zerolatency} x264 still honors the cap per-frame. */
    static final String X264_BUFSIZE_BITS = "12000000";
    /**
     * Disables x264's adaptive scene-cut keyframe insertion (default threshold 40, inherited from
     * the {@code veryfast} preset if left unset). docs/MVP2-PLAN.md V-a: mediamtx cuts a new HLS
     * segment at the first keyframe at-or-after its configured {@code hlsSegmentDuration}, so a
     * closed, strictly periodic GOP (one keyframe every {@link #GOP_SECONDS} exactly, never early)
     * keeps segment boundaries — and therefore segment durations — predictable; scene-cut-triggered
     * early keyframes are the standard live/adaptive-streaming footgun this avoids (recommended
     * practice for HLS/DASH authoring generally, not specific to this codebase).
     */
    static final String X264_SCENECUT_THRESHOLD = "0";

    private H264RecorderFactory() {
    }

    /**
     * Creates, configures, and starts a recorder pushing to {@code pushUrl}. On a failed {@link
     * FFmpegFrameRecorder#start()} the half-initialized recorder is released before the exception
     * is rethrown — this factory never leaks a native recorder regardless of the caller's own
     * cleanup.
     */
    static FFmpegFrameRecorder create(String pushUrl, int width, int height, double frameRateFps) throws Exception {
        return create(pushUrl, width, height, frameRateFps, PublishSettings.Encoder.defaults());
    }

    /**
     * Same as {@link #create(String, int, int, double)}, but the x264 rate-control/GOP options come
     * from {@code encoder} (docs/LAYERING-REFACTOR-PLAN.md wave F3) instead of this class's own
     * {@code static final} constants — {@link MediamtxStreamPublisher} calls this overload with its
     * configured {@link PublishSettings#encoder()}.
     */
    static FFmpegFrameRecorder create(String pushUrl, int width, int height, double frameRateFps,
                                       PublishSettings.Encoder encoder) throws Exception {
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(pushUrl, width, height);
        configureRecorder(recorder, frameRateFps, encoder);
        try {
            recorder.start();
        } catch (Exception e) {
            releaseQuietly(recorder);
            throw e;
        }
        return recorder;
    }

    /**
     * Applies this class's fixed recorder configuration (format/codec/options) plus the per-stream
     * {@code frameRateFps} — the measured source cadence, see {@link CadenceEstimator} — and its
     * derived GOP size. A package-private test seam: this method deliberately doesn't call {@link
     * FFmpegFrameRecorder#start()} (see class javadoc).
     *
     * <p><b>Latency audit (docs/MVP2-PLAN.md V-a):</b> {@code tune=zerolatency} (verified against
     * x264's own source) already expands to {@code --bframes 0 --no-mbtree --sync-lookahead 0
     * --rc-lookahead 0 --force-cfr}, i.e. zero B-frames and zero rate-control/frame-type lookahead —
     * there is no reordering delay between a frame being captured and it leaving the encoder. {@link
     * FFmpegFrameRecorder#setMaxBFrames} is set to {@code 0} anyway, purely as redundant,
     * independently-testable documentation of that fact (not a behavior change — {@code tune}
     * already forces it) in case a future edit ever changes {@code tune} without re-deriving the
     * consequence. {@code maxrate}/{@code bufsize} (the CRF fix, commit a963521) are left untouched:
     * VBV bufsize bounds instantaneous bitrate *variance* for the rate controller, it is not a
     * frame-reordering/output-delay buffer — with zero lookahead each frame is written essentially
     * as soon as it's encoded, so this does not regress latency.
     */
    static void configureRecorder(FFmpegFrameRecorder recorder, double frameRateFps) {
        configureRecorder(recorder, frameRateFps, PublishSettings.Encoder.defaults());
    }

    /**
     * Same as {@link #configureRecorder(FFmpegFrameRecorder, double)}, but {@code crf}/{@code
     * maxrate}/{@code bufsize}/{@code preset}/{@code gopSeconds}/{@code sc_threshold} come from
     * {@code encoder} (docs/LAYERING-REFACTOR-PLAN.md wave F3) rather than this class's own
     * constants — see that method's own javadoc for everything else (format/codec/tune/pixel format
     * stay fixed, out of {@link PublishSettings.Encoder}'s scope per docs/LAYERING-REFACTOR-PLAN.md
     * &sect;1.3's "no behavior change" guardrail: {@code tune=zerolatency} is load-bearing for this
     * class's own documented zero-reordering-delay guarantee, not a deployment knob).
     */
    static void configureRecorder(FFmpegFrameRecorder recorder, double frameRateFps, PublishSettings.Encoder encoder) {
        recorder.setFormat("rtsp");
        recorder.setOption("rtsp_transport", "tcp");
        recorder.setOption("timeout", CONNECT_TIMEOUT_MICROS);
        recorder.setVideoCodec(AV_CODEC_ID_H264);
        recorder.setVideoCodecName("libx264");
        // veryfast, not ultrafast: at CRF rate control the preset trades CPU
        // for compression efficiency, and ultrafast needs roughly double the
        // bits for the same quality; veryfast is still comfortably real-time
        // for a handful of 720p streams on CPU.
        recorder.setVideoOption("preset", encoder.preset());
        recorder.setVideoOption("tune", "zerolatency");
        recorder.setVideoOption("crf", Integer.toString(encoder.crf()));
        recorder.setVideoOption("maxrate", Long.toString(encoder.maxrateBitsPerSecond()));
        recorder.setVideoOption("bufsize", Long.toString(encoder.bufsizeBits()));
        recorder.setVideoOption("sc_threshold", Integer.toString(encoder.scenecutThreshold()));
        recorder.setMaxBFrames(0);
        recorder.setFrameRate(frameRateFps);
        recorder.setGopSize((int) Math.round(frameRateFps * encoder.gopSeconds()));
        recorder.setPixelFormat(AV_PIX_FMT_YUV420P);
    }

    private static void releaseQuietly(FFmpegFrameRecorder recorder) {
        try {
            recorder.release();
        } catch (Exception ignored) {
            // best-effort cleanup; nothing more we can do
        }
    }
}
