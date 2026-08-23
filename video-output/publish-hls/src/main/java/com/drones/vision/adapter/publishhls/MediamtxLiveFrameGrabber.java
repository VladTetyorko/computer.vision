package com.drones.vision.adapter.publishhls;

import com.drones.vision.perception.domain.model.PixelFormat;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.VideoFrame;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Grabs one decoded frame on demand from a mediamtx path's <b>live</b> RTSP output — the gap {@link
 * MediamtxProxyPublisher} opens (docs/plans/done/MEDIA-SOT-PLAN.md M6): in proxy mode nothing in
 * the JVM ever decodes a stream's video, so the pipeline's own cached {@code latestFrame}/{@code
 * latestRawFrame} (backing the snapshot endpoint and training capture, {@code StreamPipeline} in
 * {@code vision-application}) would sit permanently empty for a proxied stream without this class
 * filling the gap.
 *
 * <h2>Un-annotated by construction</h2>
 * A proxied path's source is the camera's own feed (see {@link
 * MediamtxProxyPublisher#streamStarted}) — no overlay ever burns into it, unlike the push-mode path
 * where {@code StreamPipeline} draws boxes before handing frames to {@link
 * MediamtxStreamPublisher#publish}. Every frame this class returns is therefore clean, full-resolution
 * camera pixels — exactly what training capture wants, and the same guarantee push mode's own cached
 * raw frame already makes, now extended to proxied streams.
 *
 * <h2>Style reused from {@link MediamtxReplayFrameExtractor}, source deliberately different</h2>
 * Same grabber setup discipline (bounded connect/read I/O, {@code grabImage()} not {@code grab()} —
 * a live RTSP session can carry audio/data ahead of the first video packet, and this class only wants
 * video), the same {@link FrameConverter#copyBgr24} pixel copy, the same "never let FFmpeg take down
 * the caller" resilience posture, and the same shared {@link
 * MediamtxStreamPublisher#ensureQuietLogging()} native-log quieting. <b>Not</b> the same source,
 * though: this reads mediamtx's live RTSP path — the same {@code {rtspBase}/{streamId}} address
 * {@link MediamtxStreamPublisher} pushes to, used here as a read client instead — never the
 * playback/recording server. A proxied path's recording only finalizes on unpublish (see this
 * module's MODULE.md, "Recording finalization is not instantaneous"), so the playback server cannot
 * serve a still-live frame; only a direct RTSP read of the live path can.
 *
 * <p>Stateless, safe for unbounded concurrent use — mirrors {@link MediamtxReplayFrameExtractor}'s
 * own "one grabber per call" threading note, for the same reason: this is a request-thread, on-demand
 * fetch, not the per-stream serialized egress {@link
 * com.drones.vision.perception.domain.port.StreamPublisherPort} contracts.
 *
 * <p>Plain class with no framework dependency — instantiated directly by {@code vision-app}'s wiring
 * configuration.
 */
public final class MediamtxLiveFrameGrabber {

    private static final System.Logger LOG = System.getLogger(MediamtxLiveFrameGrabber.class.getName());

    /** Bounds the grabber's connect step; mirrors {@link H264RecorderFactory#CONNECT_TIMEOUT_MICROS}'s 5s. */
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** Bounds the grabber's read step once connected, so a stalled live path can't hang a caller's request thread. */
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(5);

    /**
     * TCP, not "automatic"/UDP: this class decodes exactly one frame per call, so the reliability a
     * live single-shot grab needs outweighs UDP's lower latency — a lost packet on a one-shot grab is
     * a failed snapshot, not a skippable frame in a continuous stream the next frame papers over.
     */
    private static final String RTSP_TRANSPORT = "tcp";

    private final URI rtspBase;
    private final String connectTimeoutMicros;
    private final String readTimeoutMicros;

    /**
     * @param rtspBase mediamtx's RTSP base, e.g. {@code rtsp://localhost:8554} — the same value
     *                 {@link MediamtxStreamPublisher} is constructed with as {@code rtspPushBase};
     *                 a path is readable at the same address it is publishable to.
     */
    public MediamtxLiveFrameGrabber(URI rtspBase) {
        this(rtspBase, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    /**
     * @param connectTimeout bounds the grabber's connect step
     * @param readTimeout    bounds the grabber's read step once connected
     */
    public MediamtxLiveFrameGrabber(URI rtspBase, Duration connectTimeout, Duration readTimeout) {
        MediamtxStreamPublisher.ensureQuietLogging();
        this.rtspBase = Objects.requireNonNull(rtspBase, "rtspBase must not be null");
        this.connectTimeoutMicros = Long.toString(
                Objects.requireNonNull(connectTimeout, "connectTimeout must not be null").toNanos() / 1000L);
        this.readTimeoutMicros = Long.toString(
                Objects.requireNonNull(readTimeout, "readTimeout must not be null").toNanos() / 1000L);
    }

    /**
     * @param streamId the stream to grab a live frame for
     * @return a decoded, un-annotated {@link VideoFrame} (sequence {@code 0}; {@code capturedAt}
     *         stamped at grab time — a live RTSP presentation timestamp has no wallclock anchor
     *         without RTCP sender reports, which a plain grab does not expose either,
     *         docs/plans/done/MEDIA-SOT-PLAN.md &sect;6 — "recent enough" is what a one-shot
     *         snapshot needs, not frame-accurate capture time), or {@link Optional#empty()} if the
     *         path is unreachable, not currently publishing, or carries no decodable video — never
     *         throws
     */
    public Optional<VideoFrame> grab(StreamId streamId) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        String url = MediamtxUrls.pushUrl(rtspBase, streamId);
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(url);
        try {
            grabber.setOption("rtsp_transport", RTSP_TRANSPORT);
            grabber.setOption("timeout", connectTimeoutMicros);
            grabber.setOption("rw_timeout", readTimeoutMicros);
            grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);
            grabber.start();

            // grabImage(), not grab(): grab() also returns audio/data frames -- same reasoning
            // MediamtxReplayFrameExtractor and adapter-rtsp's FfmpegVideoSource already document for
            // their own grab loops/calls.
            Frame frame = grabber.grabImage();
            if (frame == null || frame.image == null || frame.image.length == 0) {
                LOG.log(System.Logger.Level.WARNING, "No decodable video frame available live at " + url);
                return Optional.empty();
            }

            ByteBuffer copy = FrameConverter.copyBgr24(frame);
            return Optional.of(new VideoFrame(streamId, 0L, Instant.now(), frame.imageWidth, frame.imageHeight,
                    PixelFormat.BGR24, copy));
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Failed to grab a live frame for stream " + streamId.value() + " from " + url, e);
            return Optional.empty();
        } finally {
            releaseQuietly(grabber);
        }
    }

    private static void releaseQuietly(FFmpegFrameGrabber grabber) {
        try {
            grabber.release();
        } catch (Exception ignored) {
            // best-effort cleanup; nothing more actionable if release fails
        }
    }
}
