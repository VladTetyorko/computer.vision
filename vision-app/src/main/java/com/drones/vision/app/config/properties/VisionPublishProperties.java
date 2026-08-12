package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

/**
 * Configuration for stream egress ({@code vision.publish.*}): whether
 * {@code wiring.PublishWiring} wires published frames to a <a
 * href="https://github.com/bluenviron/mediamtx">mediamtx</a> sidecar for
 * HLS viewing, where that sidecar is reachable, and the app-facing URL base
 * viewers are actually given. Extended by docs/plans/active/LAYERING-REFACTOR-PLAN.md
 * §2.2 (wave F3) with {@code adapter-publish-hls}'s encoder/resilience/cadence
 * tunables and {@code MediamtxReplayFrameExtractor}'s window/read-timeout.
 *
 * <p>Selected by {@code wiring.PublishWiring#streamPublisherPort}: {@link
 * #enabled()} {@code true} (the default) wires {@code
 * com.drones.vision.adapter.publishhls.MediamtxStreamPublisher} using
 * {@link Mediamtx#rtspBase()} to push and {@link #viewBase()} (not {@link
 * Mediamtx#hlsBase()}!) as the base viewers are given, plus a {@code
 * PublishSettings} built from {@link #encoder()}/{@link #resilience()}/{@link
 * #cadence()}; {@code false} falls back to {@code
 * com.drones.vision.app.devsupport.NoopStreamPublisher} (e.g. running or
 * testing without mediamtx).
 *
 * <h2>Why two HLS bases</h2>
 * Historically {@link Mediamtx#hlsBase()} served double duty as both "where
 * this app reaches mediamtx" and "the URL browsers are given" — but
 * mediamtx's default HLS port ({@code 8888}) routinely collides with other
 * services already bound to that well-known port on a user's machine,
 * forcing per-machine {@code hls-base} configuration just to view a stream.
 * As of this property, {@link Mediamtx#hlsBase()} is purely the <b>internal
 * upstream</b> address {@code wiring.PublishWiring} wires {@code
 * HlsProxyController} (in {@code vision-api}) to forward to — viewers never
 * see it, so it can point at whatever port mediamtx actually ended up on.
 * {@link #viewBase()} is the URL base actually handed to viewers (by default
 * the app-relative {@code /hls}, i.e. this app's own origin under {@code
 * HlsProxyController}'s {@code /hls/**} mapping), which {@code
 * wiring.PublishWiring} passes as {@code MediamtxStreamPublisher}'s {@code
 * hlsViewBase} constructor argument instead of {@link Mediamtx#hlsBase()}.
 *
 * <h2>WHEP has no third base (docs/plans/done/MVP2-PLAN.md §L)</h2>
 * {@link Mediamtx#whepBase()} does <b>not</b> get the same internal/viewer-facing split as HLS: a
 * WHEP session is a POST/SDP exchange plus ICE, not a byte stream {@code HlsProxyController}-style
 * reverse proxying can forward transparently, so {@code MediamtxStreamPublisher#whepUrl} is built
 * straight from {@link Mediamtx#whepBase()} and handed to the viewer verbatim — there is no
 * app-relative {@code /whep} proxy route. This means {@link Mediamtx#whepBase()} must already be an
 * address the *browser* can reach (not just this app's own JVM), which is the opposite assumption
 * from {@link Mediamtx#hlsBase()}; see {@code docker-compose.yml}'s comments for how that plays out
 * for a fully-containerized run.
 *
 * @param enabled     whether to publish to mediamtx; default {@code true}
 * @param viewBase    URL base handed to viewers for HLS playback; app-relative by
 *                    default so the mediamtx port is never exposed to
 *                    browsers; default {@value #DEFAULT_VIEW_BASE}
 * @param mediamtx    mediamtx sidecar endpoints; defaulted as a whole when absent
 * @param encoder     {@code H264RecorderFactory}'s x264 rate-control/GOP tunables; defaulted as a
 *                    whole when absent
 * @param resilience  {@code PublishBackoff}'s reconnect-throttling bounds; defaulted as a whole
 *                    when absent
 * @param cadence     {@code CadenceEstimator}'s measurement/drift-detection tunables; defaulted as
 *                    a whole when absent
 * @param replay      {@code MediamtxReplayFrameExtractor}'s clip-window/read-timeout; defaulted as
 *                    a whole when absent
 * @param sourceProxy {@code MediamtxProxyPublisher}/{@code PublisherRouter}'s "mediamtx dials the
 *                    camera itself" switch (docs/plans/active/MEDIA-SOT-PLAN.md §3 switch A, §5.5);
 *                    defaulted as a whole when absent
 */
@ConfigurationProperties(prefix = "vision.publish")
public record VisionPublishProperties(@DefaultValue("true") boolean enabled,
                                       @DefaultValue(VisionPublishProperties.DEFAULT_VIEW_BASE) URI viewBase,
                                       Mediamtx mediamtx,
                                       Encoder encoder,
                                       Resilience resilience,
                                       Cadence cadence,
                                       Replay replay,
                                       SourceProxy sourceProxy) {

    static final String DEFAULT_VIEW_BASE = "/hls";

    public VisionPublishProperties {
        if (mediamtx == null) {
            mediamtx = new Mediamtx(URI.create(Mediamtx.DEFAULT_RTSP_BASE), URI.create(Mediamtx.DEFAULT_HLS_BASE),
                    URI.create(Mediamtx.DEFAULT_WHEP_BASE), URI.create(Mediamtx.DEFAULT_PLAYBACK_BASE),
                    URI.create(Mediamtx.DEFAULT_API_BASE), null, null);
        }
        if (viewBase == null) {
            viewBase = URI.create(DEFAULT_VIEW_BASE);
        }
        if (sourceProxy == null) {
            sourceProxy = new SourceProxy(SourceProxy.DEFAULT_ENABLED, SourceProxy.DEFAULT_ON_DEMAND,
                    SourceProxy.DEFAULT_RTSP_TRANSPORT, SourceProxy.DEFAULT_READY_TIMEOUT_DURATION);
        }
        if (encoder == null) {
            encoder = new Encoder(Encoder.DEFAULT_CRF_INT, Encoder.DEFAULT_MAXRATE_BPS_LONG,
                    Encoder.DEFAULT_BUFSIZE_BITS_LONG, Encoder.DEFAULT_PRESET, Encoder.DEFAULT_GOP_SECONDS_INT,
                    Encoder.DEFAULT_SCENECUT_THRESHOLD_INT);
        }
        if (resilience == null) {
            resilience = new Resilience(Resilience.DEFAULT_INITIAL_BACKOFF_DURATION,
                    Resilience.DEFAULT_MAX_BACKOFF_DURATION);
        }
        if (cadence == null) {
            cadence = new Cadence(Cadence.DEFAULT_MEASUREMENT_FRAMES_INT, Cadence.DEFAULT_MIN_MEASURED_FPS_DOUBLE,
                    Cadence.DEFAULT_MAX_MEASURED_FPS_DOUBLE, Cadence.DEFAULT_DRIFT_RATIO_HIGH_DOUBLE,
                    Cadence.DEFAULT_DRIFT_EWMA_ALPHA_DOUBLE, Cadence.DEFAULT_SUSTAINED_DRIFT_WINDOW_DURATION,
                    Cadence.DEFAULT_DEFAULT_FRAME_RATE_FPS_DOUBLE);
        }
        if (replay == null) {
            replay = new Replay(Replay.DEFAULT_WINDOW_DURATION, Replay.DEFAULT_READ_TIMEOUT_DURATION);
        }
    }

    /**
     * @param rtspBase     base RTSP URL of the mediamtx sidecar to push published frames to,
     *                     e.g. {@code rtsp://localhost:8554}; default {@value Mediamtx#DEFAULT_RTSP_BASE}
     * @param hlsBase      internal address where mediamtx serves HLS, used only as the upstream
     *                     {@code HlsProxyController} forwards to — viewers never see it directly;
     *                     e.g. {@code http://localhost:8888}; default {@value Mediamtx#DEFAULT_HLS_BASE}
     * @param whepBase     mediamtx's WebRTC/WHEP egress base (docs/plans/done/MVP2-PLAN.md §L), e.g. {@code
     *                     http://localhost:8889}; default {@value Mediamtx#DEFAULT_WHEP_BASE}.
     *                     <b>Unlike {@code hlsBase}</b>, this is not an internal-only address behind a
     *                     proxy: {@code wiring.PublishWiring} hands it straight to
     *                     {@code MediamtxStreamPublisher} as the base {@code whepUrl} is built from, and
     *                     that URL goes to the browser verbatim — so
     *                     this must already be an address the *viewer's* browser can reach, not just
     *                     this app's own JVM.
     * @param playbackBase base HTTP URL of mediamtx's playback server (docs/plans/done/OPS-CORE-PLAN.md §R,
     *                     docs/plans/done/CV-TRAINING-V2-PLAN.md §7), e.g. {@code http://localhost:19996};
     *                     default {@value Mediamtx#DEFAULT_PLAYBACK_BASE}
     * @param apiBase      base HTTP URL of mediamtx's Control API (docs/plans/active/MEDIA-SOT-PLAN.md §5.3),
     *                     e.g. {@code http://localhost:19997} — {@code MediamtxProxyPublisher} calls this
     *                     to create/patch/delete a proxied path and poll its readiness; only reached when
     *                     {@link #sourceProxy()}'s {@code enabled} is {@code true}. Default {@value
     *                     Mediamtx#DEFAULT_API_BASE}
     * @param apiUser      optional mediamtx Control API Basic-auth username. mediamtx's baked-in {@code
     *                     authInternalUsers} grants unauthenticated {@code api} access only to a caller
     *                     at {@code 127.0.0.1}/{@code ::1} — a docker-published port, or a sibling
     *                     container, does not satisfy that, so this app's own {@code docker-compose.yml}
     *                     instead mounts a widened {@code mediamtx.yml} (see that file's own comments)
     *                     and leaves this unset. Set this pair as the alternative to widening
     *                     {@code mediamtx.yml} when mediamtx is reached over something less trusted than
     *                     a private compose network. {@code null}/blank (the default) sends no {@code
     *                     Authorization} header
     * @param apiPassword  password paired with {@code apiUser}; required (non-blank) whenever {@code
     *                     apiUser} is set — {@code MediamtxProxySettings}'s own compact constructor fails
     *                     fast on a half-configured pair rather than 401ing at the first stream start
     */
    public record Mediamtx(@DefaultValue(Mediamtx.DEFAULT_RTSP_BASE) URI rtspBase,
                            @DefaultValue(Mediamtx.DEFAULT_HLS_BASE) URI hlsBase,
                            @DefaultValue(Mediamtx.DEFAULT_WHEP_BASE) URI whepBase,
                            @DefaultValue(Mediamtx.DEFAULT_PLAYBACK_BASE) URI playbackBase,
                            @DefaultValue(Mediamtx.DEFAULT_API_BASE) URI apiBase,
                            String apiUser,
                            String apiPassword) {

        static final String DEFAULT_RTSP_BASE = "rtsp://localhost:8554";
        static final String DEFAULT_HLS_BASE = "http://localhost:8888";
        static final String DEFAULT_WHEP_BASE = "http://localhost:8889";
        static final String DEFAULT_PLAYBACK_BASE = "http://localhost:19996";
        static final String DEFAULT_API_BASE = "http://localhost:19997";
    }

    /**
     * "mediamtx dials the camera itself" switch (docs/plans/active/MEDIA-SOT-PLAN.md §3 switch A, §5.5,
     * D3/D10) — read by {@code wiring.PublishWiring#streamPublisherPort} to decide whether the {@code
     * StreamPublisherPort} bean is a plain {@code MediamtxStreamPublisher} or a {@code PublisherRouter}
     * wrapping it alongside a {@code MediamtxProxyPublisher}.
     *
     * @param enabled       whether any device is ever routed to the proxy publisher — {@code
     *                      PublisherRouter} additionally requires the device's stream protocol to be
     *                      {@code rtsp} before it actually proxies one (see that class's own javadoc);
     *                      default {@code false} (D1: today's behaviour, unchanged)
     * @param onDemand      mediamtx {@code sourceOnDemand} for a created/patched path (D10): {@code
     *                      false} (default) makes mediamtx dial the camera as soon as the path is
     *                      created, so {@code MTX_PATHDEFAULTS_RECORD=yes} records it without waiting
     *                      for a viewer. {@code true} defers the dial until a reader connects, and
     *                      {@code MediamtxProxyPublisher#streamStarted} skips the readiness poll
     *                      entirely in that case — see that method's own javadoc for why
     * @param rtspTransport {@code rtspTransport} sent to mediamtx's Control API when creating/patching
     *                      a path — i.e. how <i>mediamtx</i> dials the camera, not how this JVM talks to
     *                      mediamtx; default {@value SourceProxy#DEFAULT_RTSP_TRANSPORT}
     * @param readyTimeout  how long {@code MediamtxProxyPublisher#streamStarted} polls readiness before
     *                      failing the start call rather than returning a viewer URL that would play
     *                      nothing; default 10s. Ignored when {@code onDemand} is {@code true}
     */
    public record SourceProxy(@DefaultValue("false") boolean enabled, @DefaultValue("false") boolean onDemand,
                               @DefaultValue(SourceProxy.DEFAULT_RTSP_TRANSPORT) String rtspTransport,
                               @DefaultValue("10s") Duration readyTimeout) {
        static final boolean DEFAULT_ENABLED = false;
        static final boolean DEFAULT_ON_DEMAND = false;
        static final String DEFAULT_RTSP_TRANSPORT = "automatic";
        static final Duration DEFAULT_READY_TIMEOUT_DURATION = Duration.ofSeconds(10);
    }

    /**
     * @param crf               x264 constant-quality rate factor; default {@value #DEFAULT_CRF}
     * @param maxrateBps        VBV cap bounding worst-case bitrate, bits/second; default {@value #DEFAULT_MAXRATE_BPS}
     * @param bufsizeBits       VBV buffer size, bits; default {@value #DEFAULT_BUFSIZE_BITS}
     * @param preset            x264 encoder preset; default {@value #DEFAULT_PRESET}
     * @param gopSeconds        keyframe interval in seconds; default {@value #DEFAULT_GOP_SECONDS}
     * @param scenecutThreshold x264 adaptive scene-cut threshold; default {@value #DEFAULT_SCENECUT_THRESHOLD}
     */
    public record Encoder(@DefaultValue(Encoder.DEFAULT_CRF) int crf,
                           @DefaultValue(Encoder.DEFAULT_MAXRATE_BPS) long maxrateBps,
                           @DefaultValue(Encoder.DEFAULT_BUFSIZE_BITS) long bufsizeBits,
                           @DefaultValue(Encoder.DEFAULT_PRESET) String preset,
                           @DefaultValue(Encoder.DEFAULT_GOP_SECONDS) int gopSeconds,
                           @DefaultValue(Encoder.DEFAULT_SCENECUT_THRESHOLD) int scenecutThreshold) {
        static final String DEFAULT_CRF = "21";
        static final String DEFAULT_MAXRATE_BPS = "6000000";
        static final String DEFAULT_BUFSIZE_BITS = "12000000";
        static final String DEFAULT_PRESET = "veryfast";
        static final String DEFAULT_GOP_SECONDS = "1";
        static final String DEFAULT_SCENECUT_THRESHOLD = "0";
        static final int DEFAULT_CRF_INT = 21;
        static final long DEFAULT_MAXRATE_BPS_LONG = 6_000_000L;
        static final long DEFAULT_BUFSIZE_BITS_LONG = 12_000_000L;
        static final int DEFAULT_GOP_SECONDS_INT = 1;
        static final int DEFAULT_SCENECUT_THRESHOLD_INT = 0;
    }

    /**
     * @param initialBackoff how long to wait before the first reconnect attempt after a publish
     *                       failure; default 500ms
     * @param maxBackoff     cap the doubling backoff never exceeds; default 10s
     */
    public record Resilience(@DefaultValue("500ms") Duration initialBackoff, @DefaultValue("10s") Duration maxBackoff) {
        static final Duration DEFAULT_INITIAL_BACKOFF_DURATION = Duration.ofMillis(500);
        static final Duration DEFAULT_MAX_BACKOFF_DURATION = Duration.ofSeconds(10);
    }

    /**
     * @param measurementFrames       frames sampled to measure a stream's source cadence; default {@value #DEFAULT_MEASUREMENT_FRAMES}
     * @param minMeasuredFps          lower sanity-clamp bound for the measured rate; default {@value #DEFAULT_MIN_MEASURED_FPS}
     * @param maxMeasuredFps          upper sanity-clamp bound for the measured rate; default {@value #DEFAULT_MAX_MEASURED_FPS}
     * @param driftRatioHigh          post-start drift ratio ceiling; default {@value #DEFAULT_DRIFT_RATIO_HIGH}
     * @param driftEwmaAlpha          smoothing factor for the post-start actual-cadence EWMA; default {@value #DEFAULT_DRIFT_EWMA_ALPHA}
     * @param sustainedDriftWindow    how long the drift ratio must stay outside bounds before logged; default 2s
     * @param defaultFrameRateFps     fallback rate used when the measured cadence is degenerate; default {@value #DEFAULT_DEFAULT_FRAME_RATE_FPS}
     */
    public record Cadence(@DefaultValue(Cadence.DEFAULT_MEASUREMENT_FRAMES) int measurementFrames,
                           @DefaultValue(Cadence.DEFAULT_MIN_MEASURED_FPS) double minMeasuredFps,
                           @DefaultValue(Cadence.DEFAULT_MAX_MEASURED_FPS) double maxMeasuredFps,
                           @DefaultValue(Cadence.DEFAULT_DRIFT_RATIO_HIGH) double driftRatioHigh,
                           @DefaultValue(Cadence.DEFAULT_DRIFT_EWMA_ALPHA) double driftEwmaAlpha,
                           @DefaultValue("2s") Duration sustainedDriftWindow,
                           @DefaultValue(Cadence.DEFAULT_DEFAULT_FRAME_RATE_FPS) double defaultFrameRateFps) {
        static final String DEFAULT_MEASUREMENT_FRAMES = "5";
        static final String DEFAULT_MIN_MEASURED_FPS = "1.0";
        static final String DEFAULT_MAX_MEASURED_FPS = "120.0";
        static final String DEFAULT_DRIFT_RATIO_HIGH = "1.5";
        static final String DEFAULT_DRIFT_EWMA_ALPHA = "0.2";
        static final String DEFAULT_DEFAULT_FRAME_RATE_FPS = "15.0";
        static final int DEFAULT_MEASUREMENT_FRAMES_INT = 5;
        static final double DEFAULT_MIN_MEASURED_FPS_DOUBLE = 1.0;
        static final double DEFAULT_MAX_MEASURED_FPS_DOUBLE = 120.0;
        static final double DEFAULT_DRIFT_RATIO_HIGH_DOUBLE = 1.5;
        static final double DEFAULT_DRIFT_EWMA_ALPHA_DOUBLE = 0.2;
        static final Duration DEFAULT_SUSTAINED_DRIFT_WINDOW_DURATION = Duration.ofSeconds(2);
        static final double DEFAULT_DEFAULT_FRAME_RATE_FPS_DOUBLE = 15.0;
    }

    /**
     * @param window      length of the clip window requested from mediamtx per replay-frame fetch;
     *                    default 1s
     * @param readTimeout bounds the replay grabber's connect+read I/O; default 15s
     */
    public record Replay(@DefaultValue("1s") Duration window, @DefaultValue("15s") Duration readTimeout) {
        static final Duration DEFAULT_WINDOW_DURATION = Duration.ofSeconds(1);
        static final Duration DEFAULT_READ_TIMEOUT_DURATION = Duration.ofSeconds(15);
    }
}
