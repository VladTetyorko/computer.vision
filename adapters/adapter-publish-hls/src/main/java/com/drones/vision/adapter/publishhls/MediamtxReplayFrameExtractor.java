package com.drones.vision.adapter.publishhls;

import com.drones.vision.domain.model.PixelFormat;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.domain.port.out.ReplayFrameExtractionPort;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ReplayFrameExtractionPort} that pulls one decoded frame out of a stream's mediamtx
 * recording at a specific instant (docs/CV-TRAINING-V2-PLAN.md §6) — the replay-capture
 * counterpart to live capture's {@code StreamService#latestRawFrame}.
 *
 * <h2>Seek is delegated to mediamtx, not to ffmpeg</h2>
 * {@link #frameAt} requests a one-second window <i>starting at</i> the wanted instant from
 * mediamtx's own playback server ({@link MediamtxPlaybackUrls}, the same {@code /get} query shape
 * {@link MediamtxStreamPublisher#playbackUrl} builds) — mediamtx returns an MP4 clip that already
 * begins where we want, so at most ~1s of video is ever decoded. This class never opens the whole
 * recording or performs an ffmpeg-side {@code setTimestamp} seek across a long file.
 *
 * <h2>A separate class from {@link MediamtxStreamPublisher}, on purpose</h2>
 * That class's {@code StreamPublisherPort} contract is explicitly per-stream, non-concurrent,
 * stateful egress (see its own "Threading" javadoc: "calls for a single {@code streamId} are not
 * concurrent"). A blocking, freely-concurrent fetch-and-decode called from request threads would
 * poison that contract, so it lives here instead. This class is the opposite in every respect:
 * stateless, safe for unbounded concurrent use, one grabber per call, no shared mutable state and
 * no per-stream bookkeeping at all.
 *
 * <h2>Resilience</h2>
 * Mirrors {@link MediamtxStreamPublisher}'s own "never let FFmpeg take down the caller" posture
 * (see its class javadoc, "Resilience"): nothing thrown by JavaCV/FFmpeg — an unreachable mediamtx,
 * a 404 for "nothing recorded there" (surfaced by JavaCV as a grabber start failure), a truncated or
 * undecodable clip, a stalled read — ever escapes {@link #frameAt}. Any failure is logged once at
 * {@code WARNING} naming the request URL and the cause, then {@link Optional#empty()} is returned —
 * honest absence, exactly {@link ReplayFrameExtractionPort}'s own contract. The grabber is always
 * released, success or failure.
 *
 * <p>Plain class with no framework dependency — instantiated directly by {@code vision-app}'s
 * wiring configuration.
 */
public final class MediamtxReplayFrameExtractor implements ReplayFrameExtractionPort {

    private static final System.Logger LOG = System.getLogger(MediamtxReplayFrameExtractor.class.getName());

    /**
     * Window length requested from mediamtx per {@link #frameAt} call. Seeking is mediamtx's job
     * (see class javadoc); this process only ever decodes the first video frame of a ~1s clip.
     */
    private static final long WINDOW_DURATION_SECONDS = 1L;

    /**
     * Bounds the grabber's I/O (connect + read) so a stalled mediamtx response can't hang a
     * caller's request thread forever. {@code rw_timeout} is a generic libavformat/AVIOContext
     * option — not RTSP-specific — already used for the identical purpose by {@code adapter-rtsp}'s
     * {@code FfmpegVideoSource} against its own RTSP sources; it applies just as well to the plain
     * HTTP fetch this class performs against mediamtx's playback server. 15s, in microseconds.
     */
    static final String READ_TIMEOUT_MICROS = "15000000";

    private final URI playbackBase;

    /**
     * @param playbackBase base URL of mediamtx's playback HTTP server, e.g. {@code
     *                      http://localhost:19996}; {@code null} means no playback is configured —
     *                      every {@link #frameAt} call then returns {@link Optional#empty()}
     *                      without attempting a fetch, mirroring {@link
     *                      MediamtxStreamPublisher#playbackUrl}'s own unconfigured-base posture.
     */
    public MediamtxReplayFrameExtractor(URI playbackBase) {
        // Idempotent, shared with MediamtxStreamPublisher (same package): both classes create
        // FFmpeg objects, so both route through the one native-log-quieting entry point instead
        // of each carrying their own copy — see MediamtxStreamPublisher's own javadoc for why the
        // block itself is duplicated *across adapters* but there is no reason to triple it *within*
        // this one.
        MediamtxStreamPublisher.ensureQuietLogging();
        this.playbackBase = playbackBase;
    }

    @Override
    public Optional<VideoFrame> frameAt(StreamId streamId, Instant at) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        Objects.requireNonNull(at, "at must not be null");
        if (playbackBase == null) {
            return Optional.empty();
        }

        String url = MediamtxPlaybackUrls.getUrl(playbackBase, streamId.value().toString(), at, WINDOW_DURATION_SECONDS);
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(url);
        try {
            grabber.setFormat("mp4");
            grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);
            grabber.setOption("rw_timeout", READ_TIMEOUT_MICROS);
            grabber.start();

            // grabImage(), not grab(): grab() also returns audio/data frames, and this class only
            // wants the clip's video content -- same reasoning as adapter-rtsp's FfmpegVideoSource
            // (see its grab-loop comment), applied to a single-shot grab instead of a loop.
            Frame frame = grabber.grabImage();
            if (frame == null || frame.image == null || frame.image.length == 0) {
                LOG.log(System.Logger.Level.WARNING,
                        "No decodable video frame at " + url + " (nothing recorded at that instant, "
                                + "or the clip carries no video)");
                return Optional.empty();
            }

            ByteBuffer copy = FrameConverter.copyBgr24(frame);
            return Optional.of(new VideoFrame(streamId, 0L, at, frame.imageWidth, frame.imageHeight,
                    PixelFormat.BGR24, copy));
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Failed to extract a replay frame for stream " + streamId.value() + " at " + at
                            + " from " + url, e);
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
