package com.drones.vision.adapter.rtsp;

import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.domain.port.out.VideoSourcePort;

import org.bytedeco.ffmpeg.global.avutil;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link VideoSourcePort} implementation backed by JavaCV/FFmpeg — the platform's FFmpeg ingest
 * adapter. Covers four protocols:
 * <ul>
 *   <li>{@code rtsp} — real RTSP/RTP camera streams (IP cameras, drone companions); dials out (the
 *       app connects to the camera).</li>
 *   <li>{@code file} — a local video file played back as a simulated live source (drone simulation
 *       with zero hardware): looped on request and paced to its own native frame rate rather than
 *       decoded flat out.</li>
 *   <li>{@code srt} — SRT (Secure Reliable Transport), docs/DRONE-INFRA-PLAN.md I-h: the de-facto
 *       low-latency transport for drone video over lossy cellular/long-range links (DJI
 *       transmission, Herelink, cheap SRT encoders, OBS). Either dials out ({@code mode=caller}, the
 *       app connects to the encoder) or binds/listens ({@code mode=listener}, the natural choice for
 *       {@code srt://0.0.0.0:port} — the encoder dials in).</li>
 *   <li>{@code udp} — raw UDP/MPEG-TS, docs/DRONE-INFRA-PLAN.md I-h: the classic ground-station/
 *       encoder output ({@code ffmpeg … -f mpegts udp://…}, analog-to-digital boxes). Always
 *       binds/listens — UDP is connectionless, so opening a {@code udp://host:port} URL for reading
 *       means "receive datagrams sent to this host:port", not "connect to a remote peer".</li>
 * </ul>
 *
 * <p>Supports {@link StreamDescriptor#protocol()} {@code "rtsp"} (any URI) and {@code
 * "file"}/{@code "srt"}/{@code "udp"} (the {@link StreamDescriptor#uri()} scheme must itself match
 * the protocol string). This class is the thin {@link VideoSourcePort} entry point only — protocol
 * dispatch ({@link #supports}) and per-stream lifecycle ({@link #open}/{@link #close}); see:
 * <ul>
 *   <li>{@link FfmpegGrabberOptions} for the full per-protocol {@link StreamDescriptor#options()}
 *       surface (rtsp/srt/udp tuning) and their verified FFmpeg defaults/rationale,</li>
 *   <li>{@link FfmpegGrabLoop} for the per-open grab loop (real-time pacing, {@code loop} handling,
 *       backpressure, open-failure logging), and</li>
 *   <li>{@link FfmpegSettings} for every environment/tuning literal, injectable via the {@link
 *       #FfmpegVideoSource(FfmpegSettings)} constructor.</li>
 * </ul>
 *
 * <p>Plain class with no framework dependency — instantiated directly by {@code vision-app}'s
 * wiring configuration.
 */
public final class FfmpegVideoSource implements VideoSourcePort {

    static final String PROTOCOL_RTSP = "rtsp";
    static final String PROTOCOL_FILE = "file";
    static final String PROTOCOL_SRT = "srt";
    static final String PROTOCOL_UDP = "udp";

    static final String OPTION_LOOP = "loop";
    static final boolean DEFAULT_LOOP = false;

    private final FfmpegSettings settings;
    private final Map<StreamId, FfmpegGrabLoop> runtimes = new ConcurrentHashMap<>();
    /**
     * Consecutive open-failure count per stream — purely to give {@link FfmpegGrabLoop}'s WARN log
     * "attempt N" context (e.g. a dead RTSP path retried endlessly by the {@code vision-application}
     * stream supervisor, {@code SupervisedPublisher}, on a 1s→30s backoff). Reset to zero the moment
     * a grabber actually starts successfully, and removed entirely on an explicit {@link
     * #close(StreamId)}.
     */
    private final Map<StreamId, AtomicInteger> consecutiveOpenFailures = new ConcurrentHashMap<>();

    /** Uses {@link FfmpegSettings#defaults()} — the byte-identical defaults this class always had. */
    public FfmpegVideoSource() {
        this(FfmpegSettings.defaults());
    }

    /** @param settings tunables for every stream this instance opens; see {@link FfmpegSettings} */
    public FfmpegVideoSource(FfmpegSettings settings) {
        ensureQuietLogging();
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
    }

    // -- native log quieting --------------------------------------------------
    // FFmpeg's native library is chatty by default: every FFmpegFrameGrabber
    // start dumps an AV_LOG_INFO codec/format banner straight to the
    // process's stdout/stderr -- easily mistaken for an error by anyone
    // watching application logs. We only want warnings and worse from the
    // native layer, so the native log threshold is lowered once per JVM,
    // before the first grabber use.
    //
    // Deliberately duplicated (not shared) in adapter-rtsp and
    // adapter-publish-hls: per CLAUDE.md's dependency rule, adapters must
    // not depend on each other, so this ~5-line block is intentionally
    // copy-pasted rather than factored into a shared utility module. See
    // adapter-publish-hls's MediamtxStreamPublisher for its twin.
    private static boolean quietLoggingConfigured = false;

    /**
     * Idempotent; lowers FFmpeg's native log threshold to {@code AV_LOG_ERROR} so grabber start/stop
     * no longer dumps INFO-level banners to stdout/stderr — only warnings and errors from the native
     * layer still print. Safe to call repeatedly (e.g. once per constructed instance, including
     * across many instances in a single test run): the guard makes every call after the first a
     * no-op. Also called directly by {@link RtspFeedTransmitter} (same module, intra-module reuse,
     * not the cross-adapter-module duplication the rule above guards against).
     */
    static synchronized void ensureQuietLogging() {
        if (quietLoggingConfigured) {
            return;
        }
        // ERROR, not WARNING: decoding any yuvj-tagged media (MJPEG streams, many
        // mp4s) makes swscale print "deprecated pixel format used, make sure you
        // did set range correctly" once per converted frame -- a known-benign
        // warning that JavaCV's high-level API offers no per-context way to avoid.
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);
        quietLoggingConfigured = true;
    }

    @Override
    public boolean supports(StreamDescriptor descriptor) {
        if (descriptor == null) {
            return false;
        }
        String protocol = descriptor.protocol();
        if (PROTOCOL_RTSP.equals(protocol)) {
            return true; // any URI accepted -- rtsp:// is the only realistic scheme in practice
        }
        // file/srt/udp all require the URI's own scheme to match the protocol string
        // exactly (unlike rtsp above) -- each protocol string doubles as the required
        // scheme name here, so one branch covers all three.
        if (PROTOCOL_FILE.equals(protocol) || PROTOCOL_SRT.equals(protocol) || PROTOCOL_UDP.equals(protocol)) {
            URI uri = descriptor.uri();
            return uri != null && protocol.equalsIgnoreCase(uri.getScheme());
        }
        return false;
    }

    @Override
    public Flow.Publisher<VideoFrame> open(StreamId id, StreamDescriptor descriptor) {
        if (!supports(descriptor)) {
            throw new IllegalArgumentException("FfmpegVideoSource does not support descriptor: " + descriptor);
        }
        return openAny(id, descriptor.uri(), descriptor.options());
    }

    /**
     * Test seam: runs the exact production grab loop against any URI (an {@code rtsp://} camera, a
     * local {@code file:} video, ...) without the {@link #supports(StreamDescriptor)} protocol
     * check. This lets integration tests exercise the real FFmpeg decode path against a small local
     * file without a live camera.
     *
     * @param id      identity to associate with the opened stream
     * @param uri     resource to open; RTSP-specific grabber options and real-time pacing (see class
     *                javadoc) are only applied based on {@code uri.getScheme()}
     * @param options adapter options, see {@link FfmpegGrabberOptions}; may be empty
     * @return a per-open publisher of frames; see {@link VideoSourcePort} for delivery/backpressure
     *         semantics
     */
    Flow.Publisher<VideoFrame> openAny(StreamId id, URI uri, Map<String, String> options) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (uri == null) {
            throw new IllegalArgumentException("uri must not be null");
        }
        Map<String, String> effectiveOptions = options == null ? Map.of() : options;
        FfmpegGrabLoop runtime = new FfmpegGrabLoop(id, uri, effectiveOptions, settings, consecutiveOpenFailures);
        FfmpegGrabLoop previous = runtimes.put(id, runtime);
        if (previous != null) {
            previous.close(); // defensive: an id must not have two live runtimes
        }
        runtime.start();
        return runtime.publisher();
    }

    @Override
    public void close(StreamId id) {
        FfmpegGrabLoop runtime = runtimes.remove(id);
        if (runtime != null) {
            runtime.close();
        }
        consecutiveOpenFailures.remove(id);
    }
}
