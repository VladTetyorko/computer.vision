package com.drones.vision.adapter.rtsp;

import com.drones.vision.domain.model.FeedId;
import com.drones.vision.domain.model.FeedSpec;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.port.out.FeedTransmitterPort;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;

/**
 * {@link FeedTransmitterPort} implementation that pushes a local video file
 * to an RTSP target (typically a mediamtx sidecar) so this same module's
 * {@link FfmpegVideoSource} — or any other real RTSP client — can ingest it
 * exactly like a live camera.
 *
 * <p>This is the TX (transmit) half of the RX/TX doctrine in {@code
 * docs/CYCLES-PLAN.md} §0: <b>simulation infrastructure, not egress</b> —
 * unrelated to {@code StreamPublisherPort} (viewer-facing HLS egress, see
 * {@code adapter-publish-hls}'s {@code MediamtxStreamPublisher}). Supports
 * {@link FeedSpec#protocol()} {@code "rtsp"} with a {@link FeedSpec#source()}
 * whose URI scheme is itself {@code file}.
 *
 * <p>Recognized {@link FeedSpec#options()} keys:
 * <ul>
 *   <li>{@code loop} — {@code "true"}/{@code "false"}, default <b>{@code
 *       true}</b> (malformed values fall back to the default, same lenient
 *       idiom as {@link FfmpegVideoSource}). This default is the opposite of
 *       {@link FfmpegVideoSource}'s ({@code false}) deliberately: a
 *       transmitted simulation feed exists to be watched/rehearsed
 *       indefinitely, so it should keep transmitting past end-of-file unless
 *       explicitly told not to, whereas a plain RX-side file playback only
 *       loops when the caller (the simulation layer) asks for it.</li>
 * </ul>
 *
 * <h2>Target</h2>
 * {@link #start(FeedId, FeedSpec)} pushes to {@code <rtspTargetBase>/feed-<id>}
 * and returns a {@link StreamDescriptor} pointing at that same URI so the RX
 * side can open it immediately. Setup (validating the source file) is
 * blocking; the actual grab/encode/push runs on a dedicated background
 * thread started before this method returns — it does not wait for the
 * first frame to actually reach the wire. Depending on the RTSP server, the
 * target may only become readable once data starts flowing (e.g. mediamtx
 * only serves a path once it has received at least one frame for it) — a
 * caller opening the returned descriptor immediately should retry/poll
 * rather than assume instant readiness.
 *
 * <p><b>Real-time pacing:</b> shares {@link RealtimePacer} with {@link
 * FfmpegGrabLoop}'s own {@code file}-scheme pacing (docs/LAYERING-REFACTOR-PLAN.md
 * §5.1 — the identical timestamp-delta pacing logic was duplicated verbatim
 * between the two before that extraction) so the file is pushed at its own
 * native frame rate rather than as fast as disk I/O allows. Sharing is
 * intra-module, not cross-adapter — both call sites live in this one module.
 *
 * <p><b>Encoder settings</b> (format/codec/preset/tune/pixel format) are
 * deliberately duplicated from {@code adapter-publish-hls}'s {@code
 * MediamtxStreamPublisher} — proven via that module's docker-gated
 * integration test — rather than shared, per {@code CLAUDE.md}'s rule that
 * adapters never depend on each other. The one deviation: frame rate/GOP are
 * derived from the source file's own reported frame rate (falling back to
 * {@link FfmpegSettings.Transmit#fallbackFps()} if the source doesn't report
 * a usable one) rather than a fixed value, because unlike {@code
 * MediamtxStreamPublisher} (which re-encodes an arbitrary, bursty upstream
 * cadence and so pins a nominal rate plus PTS-collision bookkeeping), this
 * class's frames are already paced to the source's real cadence and are
 * pushed with the recorder's own auto-incrementing per-call timestamp (no
 * explicit {@code setTimestamp}), which is collision-free by construction —
 * matching the encoder's assumed rate to the source's actual rate avoids
 * timeline drift with no extra bookkeeping needed.
 *
 * <p>Each {@link #start(FeedId, FeedSpec)} call spins up one dedicated
 * platform thread ({@code rtsp-feed-<id>}, decoding/encoding is CPU-bound).
 * An unrecoverable grab/encode/push failure simply stops the feed —
 * best-effort, no error channel back to the caller, per {@link
 * FeedTransmitterPort}'s contract; it is logged once at {@code WARNING}.
 *
 * <p>Plain class with no framework dependency — instantiated directly by
 * {@code vision-app}'s wiring configuration, constructor-injected with the
 * RTSP target base (e.g. {@code rtsp://localhost:8554}), the same pattern
 * {@code MediamtxStreamPublisher} uses for its mediamtx base URLs. Every
 * environment/tuning literal (encoder settings, timeouts) lives in {@link
 * FfmpegSettings}, injectable via the {@link #RtspFeedTransmitter(URI,
 * FfmpegSettings)} constructor — see that record's {@code Transmit} nested
 * type for this class's own tunables.
 */
public final class RtspFeedTransmitter implements FeedTransmitterPort {

    private static final System.Logger LOG = System.getLogger(RtspFeedTransmitter.class.getName());

    private static final String PROTOCOL_RTSP = "rtsp";
    private static final String PROTOCOL_FILE = "file";
    private static final String TARGET_PATH_PREFIX = "feed-";

    static final String OPTION_LOOP = "loop";
    static final boolean DEFAULT_LOOP = true; // see class javadoc: opposite default of FfmpegVideoSource, intentionally

    private final URI rtspTargetBase;
    private final FfmpegSettings settings;
    private final Map<FeedId, FeedRuntime> runtimes = new ConcurrentHashMap<>();

    /**
     * Uses {@link FfmpegSettings#defaults()} — the byte-identical defaults this class always had.
     *
     * @param rtspTargetBase base RTSP URL to push feeds to, e.g. {@code rtsp://localhost:8554}; must not be {@code null}
     */
    public RtspFeedTransmitter(URI rtspTargetBase) {
        this(rtspTargetBase, FfmpegSettings.defaults());
    }

    /**
     * @param rtspTargetBase base RTSP URL to push feeds to, e.g. {@code rtsp://localhost:8554}; must not be {@code null}
     * @param settings       encoder/timeout tunables for every feed this instance starts; see {@link FfmpegSettings}
     */
    public RtspFeedTransmitter(URI rtspTargetBase, FfmpegSettings settings) {
        // Intra-module reuse, not a cross-adapter dependency: FfmpegVideoSource lives in this
        // same module/package, so its idempotent native-log-quieting guard is shared directly
        // rather than duplicated a third time (the ensureQuietLogging duplication precedent in
        // FfmpegVideoSource/MediamtxStreamPublisher applies across *different* adapter modules).
        FfmpegVideoSource.ensureQuietLogging();
        this.rtspTargetBase = Objects.requireNonNull(rtspTargetBase, "rtspTargetBase must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
    }

    @Override
    public boolean supports(FeedSpec spec) {
        if (spec == null) {
            return false;
        }
        if (!PROTOCOL_RTSP.equals(spec.protocol())) {
            return false;
        }
        URI source = spec.source();
        return source != null && PROTOCOL_FILE.equalsIgnoreCase(source.getScheme());
    }

    @Override
    public StreamDescriptor start(FeedId id, FeedSpec spec) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (!supports(spec)) {
            throw new IllegalArgumentException("RtspFeedTransmitter does not support spec: " + spec);
        }

        Path sourcePath = Paths.get(spec.source());
        if (!Files.isRegularFile(sourcePath) || !Files.isReadable(sourcePath)) {
            throw new IllegalArgumentException("FeedSpec source is not a readable file: " + sourcePath);
        }

        URI targetUri = targetUri(id);
        FeedRuntime runtime = new FeedRuntime(id, sourcePath, targetUri, spec.options(), settings);
        FeedRuntime previous = runtimes.put(id, runtime);
        if (previous != null) {
            previous.close(); // defensive: an id must not have two live feeds
        }
        runtime.start();
        return new StreamDescriptor(PROTOCOL_RTSP, targetUri, Map.of());
    }

    @Override
    public void stop(FeedId id) {
        FeedRuntime runtime = runtimes.remove(id);
        if (runtime != null) {
            runtime.close();
        }
    }

    private URI targetUri(FeedId id) {
        String base = rtspTargetBase.toString();
        String withoutTrailingSlash = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(withoutTrailingSlash + "/" + TARGET_PATH_PREFIX + id.value());
    }

    /** Per-feed runtime: a dedicated grab+encode+push thread. */
    private static final class FeedRuntime {
        private final FeedId feedId;
        private final Path sourcePath;
        private final URI targetUri;
        private final boolean loop;
        private final FfmpegSettings settings;
        private final AtomicBoolean stopRequested = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final RealtimePacer pacer = new RealtimePacer();
        private volatile Thread transmitThread;

        FeedRuntime(FeedId feedId, Path sourcePath, URI targetUri, Map<String, String> options, FfmpegSettings settings) {
            this.feedId = feedId;
            this.sourcePath = sourcePath;
            this.targetUri = targetUri;
            this.loop = booleanOption(options, OPTION_LOOP, DEFAULT_LOOP);
            this.settings = settings;
        }

        void start() {
            transmitThread = new Thread(this::runTransmitLoop, "rtsp-feed-" + feedId.value());
            transmitThread.setDaemon(true);
            transmitThread.start();
        }

        private void runTransmitLoop() {
            FFmpegFrameGrabber grabber = null;
            FFmpegFrameRecorder recorder = null;
            try {
                grabber = new FFmpegFrameGrabber(sourcePath.toString());
                grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);
                grabber.start();

                FfmpegSettings.Transmit transmit = settings.transmit();
                double sourceFrameRate = grabber.getFrameRate();
                double frameRate = sourceFrameRate > 0 ? sourceFrameRate : transmit.fallbackFps();

                recorder = new FFmpegFrameRecorder(targetUri.toString(), grabber.getImageWidth(), grabber.getImageHeight());
                recorder.setFormat("rtsp");
                recorder.setOption("rtsp_transport", settings.transport());
                recorder.setOption("timeout", FfmpegSettings.microsOption(transmit.connectTimeout()));
                recorder.setVideoCodec(AV_CODEC_ID_H264);
                recorder.setVideoCodecName("libx264");
                recorder.setVideoOption("preset", transmit.preset());
                recorder.setVideoOption("tune", transmit.tune());
                recorder.setFrameRate(frameRate);
                recorder.setGopSize((int) Math.round(frameRate * transmit.gopSeconds()));
                recorder.setPixelFormat(AV_PIX_FMT_YUV420P);
                recorder.start();

                LOG.log(System.Logger.Level.INFO, () -> "Transmitting " + sourcePath + " to " + targetUri);

                while (!stopRequested.get()) {
                    // grabImage(), not grab(): audio frames' look-ahead timestamps would
                    // stall the pacing sleep (see FfmpegVideoSource's grab loop comment).
                    Frame frame = grabber.grabImage();
                    if (frame == null) {
                        if (loop && !stopRequested.get()) {
                            grabber.restart(); // stop() + start(): reopens the file from the beginning
                            pacer.reset(); // reset pacing baseline across the loop restart
                            continue;
                        }
                        break; // end of stream and not looping -- graceful completion
                    }
                    // Real-time pacing (RealtimePacer, shared with FfmpegGrabLoop's file-source
                    // pacing -- see that class's javadoc for the identical rationale).
                    pacer.paceTo(grabber.getTimestamp());

                    if (frame.image == null || frame.image.length == 0) {
                        continue; // audio/data-only frame: nothing to push
                    }
                    // No explicit setTimestamp: the recorder's own per-call frame counter
                    // (advancing 1/frameRate per record()) is collision-free by construction,
                    // since it is driven by call count rather than wall-clock capture time --
                    // unlike MediamtxStreamPublisher, which must reconcile an arbitrary/bursty
                    // upstream cadence against a fixed nominal rate.
                    recorder.record(frame);
                }
            } catch (Exception e) {
                if (!stopRequested.get()) {
                    // Unrecoverable transmit failure: best-effort, no error channel back to the
                    // caller (per FeedTransmitterPort's contract) -- just stop the feed.
                    LOG.log(System.Logger.Level.WARNING, "Feed " + feedId.value() + " stopped due to an error", e);
                }
            } finally {
                releaseQuietly(recorder);
                releaseQuietly(grabber);
            }
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                stopRequested.set(true);
                Thread thread = transmitThread;
                if (thread != null && thread != Thread.currentThread()) {
                    thread.interrupt(); // best-effort; native grab()/record() may not respond to this
                    try {
                        thread.join(settings.closeJoinTimeout().toMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        private static void releaseQuietly(FFmpegFrameRecorder recorder) {
            if (recorder == null) {
                return;
            }
            try {
                recorder.stop();
            } catch (Exception ignored) {
                // best-effort cleanup; nothing more actionable if stop fails
            } finally {
                try {
                    recorder.release();
                } catch (Exception ignored) {
                    // best-effort cleanup; nothing more actionable if release fails
                }
            }
        }

        private static void releaseQuietly(FFmpegFrameGrabber grabber) {
            if (grabber == null) {
                return;
            }
            try {
                grabber.release();
            } catch (Exception ignored) {
                // best-effort cleanup; nothing more actionable if release fails
            }
        }

        /**
         * Lenient boolean option parsing, matching {@link FfmpegVideoSource}'s
         * existing idiom: missing/blank falls back to {@code defaultValue},
         * and so does anything that isn't (case-insensitively) {@code "true"}
         * or {@code "false"} -- a malformed value is never allowed to crash
         * feed setup.
         */
        private static boolean booleanOption(Map<String, String> options, String key, boolean defaultValue) {
            String raw = options.get(key);
            if (raw == null || raw.isBlank()) {
                return defaultValue;
            }
            String trimmed = raw.trim();
            if ("true".equalsIgnoreCase(trimmed)) {
                return true;
            }
            if ("false".equalsIgnoreCase(trimmed)) {
                return false;
            }
            return defaultValue; // malformed: keep the default rather than guessing
        }
    }
}
