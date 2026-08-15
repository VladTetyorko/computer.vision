package com.drones.vision.adapter.rtsp;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;

import java.net.URI;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Per-protocol {@link FFmpegFrameGrabber} option/default configuration for {@link
 * FfmpegVideoSource} — promoted out of that class (docs/plans/active/LAYERING-REFACTOR-PLAN.md §5.1) so its
 * option surface (rtsp/srt/udp tuning) doesn't crowd the actual {@code VideoSourcePort} entry point.
 * Package-private static test seams, same idiom as before this split: constructing an {@link
 * FFmpegFrameGrabber} and calling {@code setOption}/{@code setMaxDelay} only assigns fields — no
 * native/network I/O happens until {@link FFmpegFrameGrabber#start()}, which none of these methods
 * call — so a test can assert exactly what a real grabber would be started with, without a live
 * camera or RTSP/SRT/UDP server.
 *
 * <p>Every tunable <b>default value</b> below now lives in {@link FfmpegSettings} (see that record
 * for the full field-by-field provenance and docs/plans/active/LAYERING-REFACTOR-PLAN.md §2.2's frozen {@code
 * vision.rtsp} property-key contract); this class still owns every FFmpeg AVOption <i>name</i> and
 * the fixed protocol-vocabulary constants ({@code caller}/{@code listener}, {@code mpegts}) — those
 * are registry/protocol constants, explicitly out of the config-extraction scope
 * (docs/plans/active/LAYERING-REFACTOR-PLAN.md §1.3).
 */
final class FfmpegGrabberOptions {

    // -- docs/plans/done/MVP2-PLAN.md V-c: low-latency RTSP demuxer tuning (rtsp scheme only, never file) --
    // Every default value now in FfmpegSettings was verified against FFmpeg 6.1.1's own source
    // (libavformat/options_table.h, demux.c, rtsp.c) rather than assumed from common blog-post
    // folklore -- see this module's MODULE.md "V-c: RTSP demuxer latency tuning" section for the
    // full writeup, including one genuine correction to a commonly-repeated claim (max_delay's
    // "7 second default").

    /**
     * FFmpeg's own {@code probesize} AVOption ({@code libavformat/options_table.h}) defaults to
     * 5,000,000 bytes (5MB) — verified against FFmpeg 6.1.1's source, not assumed. This bounds how
     * many raw bytes {@code avformat_find_stream_info} (called by {@link FFmpegFrameGrabber#start()})
     * is willing to buffer while probing for stream parameters (confirmed a real, not cosmetic, byte
     * cap: {@code libavformat/demux.c}'s {@code read_frame_internal} logs "Probe buffer size limit
     * ... reached" and stops once {@code read_size >= probesize}). RTSP already learns codec identity
     * from its SDP {@code DESCRIBE} response before any RTP packets are read, so a single-video-stream
     * camera needs nowhere near 5MB of probing; {@link FfmpegSettings#probesizeBytes()} bounds it down
     * to shorten how long a slow/high-bitrate source can stall connect-time probing. Device option key
     * matches FFmpeg's own AVOption name, same idiom as {@link #OPTION_RTSP_TRANSPORT}/{@link
     * #OPTION_TIMEOUT_MICROS} below.
     */
    static final String OPTION_PROBESIZE_BYTES = "probesize";

    /**
     * FFmpeg's {@code analyzeduration} AVOption defaults to {@code 0} ("unset"), which {@code
     * libavformat/demux.c}'s {@code avformat_find_stream_info} then resolves to {@code
     * 5*AV_TIME_BASE} = 5,000,000us (5s) for most formats — <b>and, verified against the same
     * source, a full {@code 7*AV_TIME_BASE} = 7,000,000us (7s) specifically when the payload is
     * detected as {@code mpeg}/{@code mpegts}</b>, a common RTSP payload for many IP cameras and
     * drone companions. This is the real source of the "several-second RTSP connect stall" this
     * module set out to fix — not, as it turns out, {@code max_delay} (see {@link
     * #OPTION_MAX_DELAY_MICROS} below for why). Bounding this down ({@link
     * FfmpegSettings#analyzeDuration()}) is the single biggest connect-time lever of the four
     * options in this block.
     */
    static final String OPTION_ANALYZE_DURATION_MICROS = "analyzeduration";

    /**
     * FFmpeg's RTSP-demuxer-private {@code reorder_queue_size} AVOption caps how many RTP packets the
     * demuxer buffers to reorder out-of-order arrivals. Verified against FFmpeg 6.1.1's {@code
     * libavformat/rtsp.c} ({@code ff_rtsp_open_transport_ctx}): left unset (its own default, {@code
     * -1}), this resolves <i>adaptively</i> — {@code 0} packets when {@code rtsp_transport=tcp}
     * ({@link FfmpegSettings#transport()}'s own default — TCP already guarantees in-order delivery,
     * so a reorder queue buys nothing) or FFmpeg's own {@code RTP_REORDER_QUEUE_DEFAULT_SIZE} = 500
     * packets for {@code udp}.
     * <p>{@link FfmpegSettings#reorderQueueSize()} pins {@code 0}: for this module's actual
     * TCP-default path that is a no-op — documents, doesn't change, today's behavior (same spirit as
     * {@code MediamtxStreamPublisher}'s {@code setMaxBFrames(0)}, docs/plans/done/MVP2-PLAN.md V-a) — but it is
     * a genuine guard against a silent multi-hundred-packet buffer if a device option ever overrides
     * {@link #OPTION_RTSP_TRANSPORT} to {@code udp}: without this pin, that source would inherit the
     * 500-packet default, which at typical RTP packet rates can mean seconds of demuxer-side
     * buffering. <b>Trade-off, stated honestly</b>: a {@code udp} source on a genuinely lossy/jittery
     * link also loses that 500-packet reorder tolerance if this pin is left in place — override
     * {@code reorder_queue_size} back up via the same device-option map for that case; this module
     * does not attempt to auto-detect it.
     */
    static final String OPTION_REORDER_QUEUE_SIZE = "reorder_queue_size";

    /**
     * Bounds (microseconds) how long the RTSP demuxer waits for a straggling/out-of-order RTP packet
     * before force-delivering what it already has ({@code libavformat/rtsp.c}'s {@code
     * ff_rtsp_fetch_packet}: {@code wait_end = first_queue_time + max_delay}).
     * <p><b>Must be applied via {@link FFmpegFrameGrabber#setMaxDelay(int)}, not {@link
     * FFmpegFrameGrabber#setOption}</b> — verified against JavaCV 1.5.10's own source ({@code
     * FFmpegFrameGrabber#startUnsafe}): although {@code max_delay} is a valid generic AVOption that
     * {@code setOption} would apply during {@code avformat_open_input}, JavaCV unconditionally
     * overwrites it immediately afterwards with {@code oc.max_delay(this.maxDelay)} — the grabber's
     * own {@code maxDelay} Java field, default {@code -1} — silently discarding anything set the
     * {@code setOption} way. This is exactly the "check how options are set — setOption vs specific
     * setters" pitfall docs/plans/done/MVP2-PLAN.md V-c's brief warns about, caught by reading JavaCV's source
     * rather than assuming.
     * <p><b>{@link FfmpegSettings#maxDelay()} is deliberately not the commonly-cited "500ms, vs. a 7s
     * default".</b> Verified against {@code rtsp.c}: the RTSP demuxer's own built-in default (unset
     * {@code max_delay < 0}) already resolves to {@code DEFAULT_REORDERING_DELAY} = 100,000us
     * (100ms) — not several seconds. The real "7 second" figure is genuine (see {@link
     * #OPTION_ANALYZE_DURATION_MICROS} above) but belongs to {@code analyzeduration}'s mpegts-payload
     * branch, not this option. Explicitly setting 500ms here would have <i>loosened</i>, not
     * tightened, the demuxer's own already-tight default. {@link FfmpegSettings#maxDelay()} instead
     * pins the value at that already-good 100ms — living documentation against a future FFmpeg
     * version silently changing {@code DEFAULT_REORDERING_DELAY} (same intent as {@link
     * #OPTION_REORDER_QUEUE_SIZE} above), not a behavior change. Going tighter than 100ms was
     * considered and rejected: {@code wait_end} is what gives a jittery/far source's late RTP
     * packets a chance to arrive before the demuxer gives up and force-delivers (logging FFmpeg's
     * own "max delay reached" warning) — shrinking it further trades a little latency for materially
     * worse robustness on exactly the non-ideal links this tuning targets.
     */
    static final String OPTION_MAX_DELAY_MICROS = "max_delay";

    static final String OPTION_RTSP_TRANSPORT = "rtsp_transport";
    static final String OPTION_TIMEOUT_MICROS = "timeout";

    // -- docs/plans/active/DRONE-INFRA-PLAN.md I-h: SRT (srt scheme only) --
    // Every FFmpeg AVOption name/default below was verified against this module's actual pinned
    // ffmpeg-platform-gpl 6.1.1-1.5.10 native binary (libavformat.so.60 built with --enable-libsrt,
    // confirmed present by extracting the jar and grepping its strings for SRTO_LATENCY/
    // SRTO_PASSPHRASE/SRTO_MODE-family symbols and the libsrt.c help text), not assumed from FFmpeg's
    // own docs/wiki -- same discipline as the RTSP V-c block above.

    /**
     * {@code StreamDescriptor#options()} key for SRT receive latency, in <b>milliseconds</b> — this
     * is the platform's own contract unit (docs/plans/active/DRONE-INFRA-PLAN.md I-h: "{@code latency} (ms, SRT's
     * core knob)"). <b>FFmpeg's own {@code latency} AVOption (libavformat's {@code libsrt.c},
     * confirmed present in this build's binary via the {@code "receive latency (in
     * microseconds)"}/{@code "peer latency (in microseconds)"} help strings on its sibling {@code
     * rcvlatency}/{@code peerlatency} options) is in <b>microseconds</b>, not milliseconds</b> — this
     * class's {@link #configureSrtOptions} converts (×1000) before calling {@code setOption}. Getting
     * this conversion wrong silently changes the requested latency budget by a factor of 1000, so it
     * is called out here and at the call site, not just implied by the constant name. {@link
     * FfmpegSettings#srtLatency()}'s default (120ms) matches libsrt's own built-in default exactly.
     */
    static final String OPTION_SRT_LATENCY_MILLIS = "latency";

    /**
     * {@code StreamDescriptor#options()} key mapping directly to FFmpeg's {@code mode} AVOption
     * (verified present in this build's binary: the {@code caller}/{@code listener}/{@code
     * rendezvous} AVOption constant names appear verbatim in {@code libavformat.so.60}'s strings).
     * Only {@code caller} (the app dials the encoder) and {@code listener} (the app binds; encoder
     * dials in) are exposed per docs/plans/active/DRONE-INFRA-PLAN.md I-h's frozen contract — {@code rendezvous}
     * exists in FFmpeg but is out of scope here. When absent, {@link #defaultSrtMode(URI)} infers
     * {@code listener} for an any-address host ({@code 0.0.0.0}/{@code ::}/unset — the natural
     * reading of {@code srt://0.0.0.0:port}: the app binds and waits) and {@code caller} otherwise (a
     * real host means the app dials out to it).
     */
    static final String OPTION_SRT_MODE = "mode";
    static final String SRT_MODE_CALLER = "caller";
    static final String SRT_MODE_LISTENER = "listener";

    /**
     * {@code StreamDescriptor#options()} key mapping directly to FFmpeg's {@code streamid} AVOption
     * (verified present: {@code SRTO_STREAMID}/{@code srt_streamid} appear in this build's binary
     * strings) — an arbitrary string an SRT caller passes to a listener to identify itself/select a
     * resource, used by e.g. SRT relay/gateway services. Pure passthrough, no default: absent means
     * "don't set one".
     */
    static final String OPTION_SRT_STREAMID = "streamid";

    /**
     * {@code StreamDescriptor#options()} key mapping directly to FFmpeg's {@code passphrase}
     * AVOption (verified present in this build's binary strings) — enables SRT's built-in AES
     * encryption. Pure passthrough, no default: absent means "no encryption". When present, {@link
     * #configureSrtOptions} also sets FFmpeg's {@code pbkeylen} AVOption (verified present: {@code
     * SRTO_PBKEYLEN}/{@code "Crypto key len in bytes {16,24,32} Default: 16 (128-bit)"} appear in
     * this build's binary strings) to {@link #DEFAULT_SRT_PBKEYLEN} — FFmpeg/libsrt already default
     * an unset {@code pbkeylen} to 16 bytes (128-bit) internally when a passphrase is set, so this is
     * not strictly required for encryption to work, but pinning it explicitly documents the chosen
     * key length rather than relying on an unstated library default (same "living documentation"
     * idiom as the RTSP V-c block's {@code reorder_queue_size}/{@code max_delay} pins above). Not
     * extracted into {@link FfmpegSettings}: this is a library-default pin, not a deployment
     * tunable — see this class's own top-level javadoc.
     */
    static final String OPTION_SRT_PASSPHRASE = "passphrase";
    private static final String FFMPEG_OPTION_SRT_PBKEYLEN = "pbkeylen";
    private static final String DEFAULT_SRT_PBKEYLEN = "16";

    // -- docs/plans/active/DRONE-INFRA-PLAN.md I-h: UDP/MPEG-TS (udp scheme only) --

    /**
     * {@code StreamDescriptor#options()} key mapping directly to FFmpeg's {@code fifo_size} AVOption
     * on the {@code udp} protocol (verified present in this build's binary strings: {@code "set the
     * UDP receiving circular buffer size, expressed as a number of packets with size of 188
     * bytes"}) — the demuxer-side receive ring buffer, in 188-byte MPEG-TS packets. FFmpeg's own
     * unset default is {@code 7*4096} = 28,672 packets (~5.4MB, confirmed via the same option table)
     * — generous loss tolerance at the cost of several seconds of possible buffering latency on a
     * live drone feed. {@link FfmpegSettings#udpFifoSizePackets()}'s default (512 packets, ~96KB)
     * trades most of that tolerance away for materially lower latency, matching this module's
     * low-latency-by-default posture for live sources (same intent as the RTSP V-c block's {@code
     * probesize}/{@code analyzeduration} tightening above).
     */
    static final String OPTION_UDP_FIFO_SIZE = "fifo_size";

    /**
     * {@code StreamDescriptor#options()} key mapping directly to FFmpeg's {@code overrun_nonfatal}
     * AVOption on the {@code udp} protocol (verified present: {@code "survive in case of UDP
     * receiving circular buffer overrun"} / {@code "Circular buffer overrun. Surviving due to
     * overrun_nonfatal option"} appear in this build's binary strings). FFmpeg's own default is
     * {@code false} — a full receive ring buffer (more likely here given the tightened {@link
     * #OPTION_UDP_FIFO_SIZE} default above) aborts the stream entirely. Defaulted to {@code true}
     * ({@link #DEFAULT_UDP_OVERRUN_NONFATAL}): a live drone feed dropping a burst of packets under
     * transient jitter/loss should keep decoding what it can, the same "stay up through jitter"
     * posture as the RTSP {@code reorder_queue_size}/{@code max_delay} tuning above, not hard-fail
     * the whole stream. Not extracted into {@link FfmpegSettings}: this is a fixed posture choice
     * (survive vs. abort), not a scalar deployment tunable like the buffer/timeout values around
     * it — see this class's own top-level javadoc.
     */
    static final String OPTION_UDP_OVERRUN_NONFATAL = "overrun_nonfatal";
    static final String DEFAULT_UDP_OVERRUN_NONFATAL = "1"; // true; survive fifo overruns rather than aborting

    /**
     * {@code StreamDescriptor#options()} key mapping directly to FFmpeg's {@code buffer_size}
     * AVOption on the {@code udp} protocol — the underlying OS socket receive buffer size in bytes
     * ({@code SO_RCVBUF}). Pure passthrough, no forced default: FFmpeg's own unset value ({@code -1})
     * means "leave the OS default alone", which this module does not override on principle — unlike
     * {@link #OPTION_UDP_FIFO_SIZE} (an application-level ring buffer this module actively wants
     * small for latency), the OS socket buffer is a system-level knob a specific deployment may need
     * to size up for a lossy/bursty link; this module has no basis to pick a better default than
     * "unset" for that case.
     */
    static final String OPTION_UDP_BUFFER_SIZE = "buffer_size";

    /**
     * FFmpeg's {@code udp} protocol {@code timeout} AVOption (verified present in this build's binary
     * strings: {@code "set raise error timeout, in microseconds (only in read mode)"}) — bounds how
     * long a udp read blocks with zero incoming packets before failing with an error, instead of
     * FFmpeg's own unset default of {@code 0} (no timeout: block forever).
     * <p><b>Not exposed as a {@code StreamDescriptor#options()} key</b> — unlike the three options
     * above, this is an internal safety default only ({@link FfmpegSettings#udpTimeout()}), always
     * applied and not currently overridable. It was added after a serious finding while writing this
     * module's own tests: JavaCV's {@code FFmpegFrameGrabber}/{@code FFmpegFrameRecorder#start()}
     * both synchronize on a shared, static, process-wide lock (verified via a real thread dump: both
     * block on the same {@code org.bytedeco.ffmpeg.global.avcodec} class monitor) for the *entire
     * duration* of their native {@code avformat_open_input} call — so a {@code udp} source that
     * nobody is ever sending a packet to would, without this bound, hold that lock <i>forever</i>,
     * silently blocking every <i>other</i> video source's own open/reconnect attempts in the same JVM
     * (the application's stream supervisor retries a failed {@code open} indefinitely with its own
     * 1s-30s backoff — each retry would re-enter the same stuck lock). This is not hypothetical: it
     * was reproduced directly while developing this module's own udp loopback test (a receiver whose
     * native probe never resolved wedged an unrelated, later test in the same JVM for 10+ minutes) —
     * see this module's MODULE.md Gotchas for the full investigation. {@link
     * FfmpegSettings#udpTimeout()}'s default (5s) is a deliberately generous "is this source dead"
     * bound, not a latency knob — it exists purely so one open attempt fails cleanly (letting the
     * caller/supervisor retry) instead of hanging the whole process.
     */
    private static final String OPTION_UDP_TIMEOUT_MICROS = "timeout";

    /**
     * FFmpeg format name forced on every {@code udp} scheme open via {@link
     * FFmpegFrameGrabber#setFormat(String)} — a raw {@code udp://} URL carries no container-level
     * self-description the way {@code rtsp://}'s SDP {@code DESCRIBE} response does, so without an
     * explicit format hint {@code avformat_open_input}'s format probe must guess from whatever
     * datagrams it happens to receive during probing. docs/plans/active/DRONE-INFRA-PLAN.md I-h's frozen contract
     * already assumes MPEG-TS for {@code udp} ("MPEG-TS assumed"), so forcing it here is not a guess
     * — it names the one container this protocol is contracted to carry, and removes format
     * auto-detection as a failure mode entirely rather than trusting it to keep guessing right.
     */
    private static final String FORMAT_MPEGTS = "mpegts";

    private FfmpegGrabberOptions() {
    }

    /**
     * Builds an {@link FFmpegFrameGrabber} for {@code uri} with every scheme-appropriate option
     * applied (RTSP/SRT/UDP tuning per {@code uri.getScheme()}, or none for a plain {@code file}
     * source) — the single entry point {@link FfmpegGrabLoop} uses to construct its grabber.
     */
    static FFmpegFrameGrabber newGrabber(URI uri, Map<String, String> options, FfmpegSettings settings) {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(resolveFilename(uri));
        grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);
        String scheme = uri.getScheme();
        if (FfmpegVideoSource.PROTOCOL_RTSP.equalsIgnoreCase(scheme)) {
            configureRtspOptions(grabber, options, settings);
        } else if (FfmpegVideoSource.PROTOCOL_SRT.equalsIgnoreCase(scheme)) {
            configureSrtOptions(grabber, uri, options, settings);
        } else if (FfmpegVideoSource.PROTOCOL_UDP.equalsIgnoreCase(scheme)) {
            configureUdpOptions(grabber, options, settings);
        }
        return grabber;
    }

    /**
     * Applies this class's RTSP-only grabber options — the pre-existing {@code rtsp_transport}/
     * {@code timeout}/{@code rw_timeout} plus docs/plans/done/MVP2-PLAN.md V-c's low-latency demuxer tuning
     * ({@code probesize}/{@code analyzeduration}/{@code reorder_queue_size}/{@code max_delay}, see
     * each constant's javadoc above for the verified FFmpeg/JavaCV facts behind its default).
     *
     * <p>Called only from the {@code uri.getScheme().equals("rtsp")} branch of {@link #newGrabber} —
     * a {@code file} source must never have any of these applied (paced local-file playback is not
     * the live-network case this tuning targets).
     */
    static void configureRtspOptions(FFmpegFrameGrabber grabber, Map<String, String> options, FfmpegSettings settings) {
        String transport = options.getOrDefault(OPTION_RTSP_TRANSPORT, settings.transport());
        grabber.setOption(OPTION_RTSP_TRANSPORT, transport);
        String timeoutMicros =
                options.getOrDefault(OPTION_TIMEOUT_MICROS, FfmpegSettings.microsOption(settings.openTimeout()));
        grabber.setOption(OPTION_TIMEOUT_MICROS, timeoutMicros);
        grabber.setOption("rw_timeout", timeoutMicros);

        grabber.setOption(OPTION_PROBESIZE_BYTES,
                options.getOrDefault(OPTION_PROBESIZE_BYTES, String.valueOf(settings.probesizeBytes())));
        grabber.setOption(OPTION_ANALYZE_DURATION_MICROS, options.getOrDefault(OPTION_ANALYZE_DURATION_MICROS,
                FfmpegSettings.microsOption(settings.analyzeDuration())));
        grabber.setOption(OPTION_REORDER_QUEUE_SIZE,
                options.getOrDefault(OPTION_REORDER_QUEUE_SIZE, String.valueOf(settings.reorderQueueSize())));
        // Not setOption: see OPTION_MAX_DELAY_MICROS's javadoc -- JavaCV overwrites an AVOption-dict
        // "max_delay" with its own maxDelay field right after open.
        int defaultMaxDelayMicros = (int) (settings.maxDelay().toNanos() / 1_000L);
        grabber.setMaxDelay(intOption(options, OPTION_MAX_DELAY_MICROS, defaultMaxDelayMicros));
    }

    /**
     * Lenient integer option parsing, matching this module's existing lenient-parsing idiom for
     * other options ({@link FfmpegGrabLoop}'s boolean loop-option parsing): missing/blank falls back
     * to {@code defaultValue}, and so does a malformed (non-integer) value — a bad device-option
     * override must never crash stream setup.
     */
    private static int intOption(Map<String, String> options, String key, int defaultValue) {
        String raw = options.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue; // malformed device override: keep the (well-formed) default
        }
    }

    /**
     * Applies this class's SRT-only grabber options — docs/plans/active/DRONE-INFRA-PLAN.md I-h.
     *
     * <p><b>Applied via {@code setOption}, not the {@code srt://} URL's query string</b> — both are
     * documented FFmpeg mechanisms for SRT protocol options, and both were actually tried (not
     * assumed) while writing this class: a standalone probe using {@code setOption("mode",
     * "listener")} measurably steered {@code FFmpegFrameGrabber#start()} into the SRT listener
     * accept-wait native code path rather than the caller-connect path, proving {@code setOption}
     * genuinely reaches the SRT protocol's own private AVOptions in this exact JavaCV/FFmpeg build —
     * the identical {@code srt://…?mode=listener} URL-query-string form was also tried and reached
     * the same code path, no better or worse. {@code setOption} was kept: one idiom across
     * rtsp/srt/udp, and no hand-building/escaping a query string on top of a caller-supplied URI that
     * may already carry its own. See this module's MODULE.md Gotchas ("verified finding: a
     * JVM-hosted SRT connection via JavaCV does not complete in this development environment") for
     * the full investigation.
     *
     * <p>Called only from the {@code uri.getScheme().equals("srt")} branch of {@link #newGrabber}.
     */
    static void configureSrtOptions(FFmpegFrameGrabber grabber, URI uri, Map<String, String> options,
            FfmpegSettings settings) {
        int defaultLatencyMillis = (int) settings.srtLatency().toMillis();
        int latencyMillis = intOption(options, OPTION_SRT_LATENCY_MILLIS, defaultLatencyMillis);
        // ms -> us: FFmpeg's own "latency" AVOption is in microseconds, see OPTION_SRT_LATENCY_MILLIS javadoc.
        grabber.setOption(OPTION_SRT_LATENCY_MILLIS, String.valueOf(latencyMillis * 1000L));
        grabber.setOption(OPTION_SRT_MODE, options.getOrDefault(OPTION_SRT_MODE, defaultSrtMode(uri)));
        String streamId = options.get(OPTION_SRT_STREAMID);
        if (streamId != null && !streamId.isBlank()) {
            grabber.setOption(OPTION_SRT_STREAMID, streamId);
        }
        String passphrase = options.get(OPTION_SRT_PASSPHRASE);
        if (passphrase != null && !passphrase.isBlank()) {
            grabber.setOption(OPTION_SRT_PASSPHRASE, passphrase);
            grabber.setOption(FFMPEG_OPTION_SRT_PBKEYLEN, DEFAULT_SRT_PBKEYLEN);
        }
    }

    /**
     * Infers the SRT connection mode when the {@link #OPTION_SRT_MODE} device option is absent — see
     * that constant's javadoc for the full rationale: an any-address host reads as "bind and wait"
     * ({@code listener}), any real host reads as "dial out" ({@code caller}).
     */
    private static String defaultSrtMode(URI uri) {
        String host = uri.getHost();
        boolean anyAddress = host == null || host.isBlank() || "0.0.0.0".equals(host) || "::".equals(host);
        return anyAddress ? SRT_MODE_LISTENER : SRT_MODE_CALLER;
    }

    /**
     * Applies this class's UDP/MPEG-TS-only grabber options — docs/plans/active/DRONE-INFRA-PLAN.md I-h.
     *
     * <p>Always forces the demuxer format to {@code mpegts} via {@link
     * FFmpegFrameGrabber#setFormat(String)} — see {@link #FORMAT_MPEGTS}'s javadoc for why a raw
     * {@code udp://} source needs the hint. Called only from the {@code uri.getScheme().equals("udp")}
     * branch of {@link #newGrabber}.
     */
    static void configureUdpOptions(FFmpegFrameGrabber grabber, Map<String, String> options, FfmpegSettings settings) {
        grabber.setFormat(FORMAT_MPEGTS);
        grabber.setOption(OPTION_UDP_FIFO_SIZE,
                options.getOrDefault(OPTION_UDP_FIFO_SIZE, String.valueOf(settings.udpFifoSizePackets())));
        grabber.setOption(OPTION_UDP_OVERRUN_NONFATAL,
                options.getOrDefault(OPTION_UDP_OVERRUN_NONFATAL, DEFAULT_UDP_OVERRUN_NONFATAL));
        String bufferSize = options.get(OPTION_UDP_BUFFER_SIZE);
        if (bufferSize != null && !bufferSize.isBlank()) {
            grabber.setOption(OPTION_UDP_BUFFER_SIZE, bufferSize);
        }
        // Not overridable, not part of the frozen options contract -- see OPTION_UDP_TIMEOUT_MICROS's
        // javadoc for why this internal safety bound exists (a stuck native open() call holds a
        // process-wide JavaCV lock forever otherwise, verified via a real thread dump).
        grabber.setOption(OPTION_UDP_TIMEOUT_MICROS, FfmpegSettings.microsOption(settings.udpTimeout()));
    }

    /**
     * {@code resolveFilename(URI)} special-cases the {@code file:} scheme (via {@code
     * Paths.get(uri)}) for both the {@code openAny} test seam and the real {@code "file"} protocol —
     * the same code path serves both.
     */
    private static String resolveFilename(URI uri) {
        if (FfmpegVideoSource.PROTOCOL_FILE.equalsIgnoreCase(uri.getScheme())) {
            return Paths.get(uri).toString();
        }
        return uri.toString();
    }
}
