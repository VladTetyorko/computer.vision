package com.drones.vision.adapter.rtsp;

import com.drones.vision.domain.model.PixelFormat;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.domain.port.out.VideoSourcePort;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link VideoSourcePort} implementation backed by JavaCV/FFmpeg — the
 * platform's FFmpeg ingest adapter. Covers four protocols:
 * <ul>
 *   <li>{@code rtsp} — real RTSP/RTP camera streams (IP cameras, drone
 *       companions); dials out (the app connects to the camera).</li>
 *   <li>{@code file} — a local video file played back as a simulated live
 *       source (drone simulation with zero hardware): looped on request and
 *       paced to its own native frame rate rather than decoded flat out.</li>
 *   <li>{@code srt} — SRT (Secure Reliable Transport), docs/DRONE-INFRA-PLAN.md
 *       I-h: the de-facto low-latency transport for drone video over lossy
 *       cellular/long-range links (DJI transmission, Herelink, cheap SRT
 *       encoders, OBS). Either dials out ({@code mode=caller}, the app
 *       connects to the encoder) or binds/listens ({@code mode=listener},
 *       the natural choice for {@code srt://0.0.0.0:port} — the encoder
 *       dials in).</li>
 *   <li>{@code udp} — raw UDP/MPEG-TS, docs/DRONE-INFRA-PLAN.md I-h: the
 *       classic ground-station/encoder output ({@code ffmpeg … -f mpegts
 *       udp://…}, analog-to-digital boxes). Always binds/listens — UDP is
 *       connectionless, so opening a {@code udp://host:port} URL for
 *       reading means "receive datagrams sent to this host:port", not
 *       "connect to a remote peer".</li>
 * </ul>
 *
 * <p>Supports {@link StreamDescriptor#protocol()} {@code "rtsp"} (any URI)
 * and {@code "file"}/{@code "srt"}/{@code "udp"} (the {@link
 * StreamDescriptor#uri()} scheme must itself match the protocol string).
 * Recognized {@link StreamDescriptor#options()} keys (all optional):
 * <ul>
 *   <li>{@code rtsp_transport} — FFmpeg's {@code rtsp_transport} AVOption
 *       (e.g. {@code tcp}, {@code udp}); default {@value #DEFAULT_RTSP_TRANSPORT}.
 *       Only applied when the URI scheme is {@code rtsp}.</li>
 *   <li>{@code timeout} — socket/read timeout in <b>microseconds</b>, applied
 *       to both the RTSP demuxer's {@code timeout} option and the generic
 *       I/O {@code rw_timeout} option; default {@value #DEFAULT_TIMEOUT_MICROS}
 *       (10 seconds). Only applied when the URI scheme is {@code rtsp}.</li>
 *   <li>{@code loop} — {@code "true"}/{@code "false"}, default {@code false}
 *       (malformed values fall back to the default). When {@code true}, a
 *       graceful end-of-stream ({@link FFmpegFrameGrabber#grab()} returning
 *       {@code null}) restarts the grabber instead of completing the
 *       publisher, so a finite file loops indefinitely until {@link
 *       #close(StreamId)} is called; the frame {@link VideoFrame#sequence()}
 *       keeps increasing monotonically across loop restarts, it never
 *       resets. <b>Only meaningful for {@code file}</b> — a live {@code
 *       rtsp}/{@code srt}/{@code udp} source has no bounded content to
 *       restart from, so setting {@code loop=true} for one of those has no
 *       useful effect (a live source's {@code grabImage()} returning {@code
 *       null} means the connection/stream genuinely ended, not "reached the
 *       end of a file to replay").</li>
 *   <li>{@code probesize}, {@code analyzeduration}, {@code
 *       reorder_queue_size}, {@code max_delay} — docs/MVP2-PLAN.md V-c
 *       low-latency RTSP demuxer tuning; see {@link #OPTION_PROBESIZE_BYTES}/
 *       {@link #OPTION_ANALYZE_DURATION_MICROS}/{@link
 *       #OPTION_REORDER_QUEUE_SIZE}/{@link #OPTION_MAX_DELAY_MICROS}'s own
 *       javadoc for each option's verified FFmpeg default, this class's
 *       tightened default, and the rationale. Only applied when the URI
 *       scheme is {@code rtsp} — never {@code file}/{@code srt}/{@code udp}
 *       (paced local-file playback and the SRT/UDP protocols below have
 *       their own, separately-tuned option sets).</li>
 *   <li>{@code latency}, {@code mode}, {@code streamid}, {@code passphrase}
 *       — SRT connection tuning, docs/DRONE-INFRA-PLAN.md I-h; see {@link
 *       #OPTION_SRT_LATENCY_MILLIS}/{@link #OPTION_SRT_MODE}/{@link
 *       #OPTION_SRT_STREAMID}/{@link #OPTION_SRT_PASSPHRASE}'s own javadoc.
 *       Only applied when the URI scheme is {@code srt}.</li>
 *   <li>{@code fifo_size}, {@code overrun_nonfatal}, {@code buffer_size} —
 *       UDP/MPEG-TS receive tuning, docs/DRONE-INFRA-PLAN.md I-h; see {@link
 *       #OPTION_UDP_FIFO_SIZE}/{@link #OPTION_UDP_OVERRUN_NONFATAL}/{@link
 *       #OPTION_UDP_BUFFER_SIZE}'s own javadoc. Only applied when the URI
 *       scheme is {@code udp}, which also always forces the demuxer format
 *       to {@code mpegts} (see {@link #configureUdpOptions} for why) and
 *       always applies an internal, non-overridable read timeout (see
 *       {@link #DEFAULT_UDP_TIMEOUT_MICROS}'s javadoc for why this exists
 *       — a real production robustness finding, not a test-only detail:
 *       without it, a udp source nobody ever sends a packet to can hold a
 *       process-wide native lock forever).</li>
 * </ul>
 *
 * <p><b>Real-time pacing:</b> decoding a local file is disk-bound, not
 * time-bound, so left unthrottled it would blast through an entire clip far
 * faster than a live camera ever could. Whenever the URI scheme is {@code
 * file}, the grab loop paces itself against the media timeline: it tracks
 * the delta between successive {@link FFmpegFrameGrabber#getTimestamp()}
 * values (microseconds) and sleeps the difference between that delta and
 * the wall-clock time actually spent since the previous frame, clamped to
 * zero (never a negative sleep, and no drift compensation beyond this one
 * monotonic baseline). The baseline resets on every loop restart, so the
 * first frame of each pass through the file is never delayed. {@code rtsp}/
 * {@code srt}/{@code udp} URIs are never paced — the network already paces
 * a live source.
 *
 * <p>Each {@link #open(StreamId, StreamDescriptor)} call starts one
 * dedicated platform thread (decoding is CPU-bound; virtual threads buy
 * nothing here) running a blocking {@link FFmpegFrameGrabber} loop. Every
 * grabbed video frame is decoded to {@link PixelFormat#BGR24}, copied into a
 * heap {@link ByteBuffer} (the grabber reuses its native buffers across
 * calls — see {@link FrameConverter}), wrapped in a {@link VideoFrame}, and
 * offered to a per-stream {@link SubmissionPublisher} with a small buffer
 * and a drop-on-backpressure policy so a slow subscriber never blocks
 * capture (frames the grabber produces while demand is exhausted are
 * dropped rather than queued without bound, per {@link VideoSourcePort}'s
 * latest-wins contract). An unrecoverable grab failure closes the publisher
 * exceptionally and releases the grabber; {@link #close(StreamId)} is the
 * cooperative counterpart (stop flag, thread join, grabber release) and is
 * idempotent.
 *
 * <p>Plain class with no framework dependency — instantiated directly by
 * {@code vision-app}'s wiring configuration.
 */
public final class FfmpegVideoSource implements VideoSourcePort {

    private static final String PROTOCOL_RTSP = "rtsp";
    private static final String PROTOCOL_FILE = "file";
    private static final String PROTOCOL_SRT = "srt";
    private static final String PROTOCOL_UDP = "udp";

    static final String OPTION_RTSP_TRANSPORT = "rtsp_transport";
    static final String DEFAULT_RTSP_TRANSPORT = "tcp";
    static final String OPTION_TIMEOUT_MICROS = "timeout";
    static final String DEFAULT_TIMEOUT_MICROS = "10000000"; // 10s, in microseconds
    static final String OPTION_LOOP = "loop";
    static final boolean DEFAULT_LOOP = false;

    // -- docs/MVP2-PLAN.md V-c: low-latency RTSP demuxer tuning (rtsp scheme only, never file) --
    // Every default below was verified against FFmpeg 6.1.1's own source
    // (libavformat/options_table.h, demux.c, rtsp.c) rather than assumed from
    // common blog-post folklore -- see each constant's javadoc and this
    // module's MODULE.md "V-c: RTSP demuxer latency tuning" section for the
    // full writeup, including one genuine correction to a commonly-repeated
    // claim (max_delay's "7 second default").

    /**
     * FFmpeg's own {@code probesize} AVOption ({@code
     * libavformat/options_table.h}) defaults to 5,000,000 bytes (5MB) --
     * verified against FFmpeg 6.1.1's source, not assumed. This bounds how
     * many raw bytes {@code avformat_find_stream_info} (called by {@link
     * FFmpegFrameGrabber#start()}) is willing to buffer while probing for
     * stream parameters (confirmed a real, not cosmetic, byte cap: {@code
     * libavformat/demux.c}'s {@code read_frame_internal} logs "Probe buffer
     * size limit ... reached" and stops once {@code read_size >= probesize}).
     * RTSP already learns codec identity from its SDP {@code DESCRIBE}
     * response before any RTP packets are read, so a single-video-stream
     * camera needs nowhere near 5MB of probing; bounding it down shortens how
     * long a slow/high-bitrate source can stall connect-time probing. Device
     * option key matches FFmpeg's own AVOption name, same idiom as {@link
     * #OPTION_RTSP_TRANSPORT}/{@link #OPTION_TIMEOUT_MICROS} above.
     */
    static final String OPTION_PROBESIZE_BYTES = "probesize";
    static final String DEFAULT_PROBESIZE_BYTES = "32768"; // 32 KiB, vs. FFmpeg's 5,000,000-byte (5MB) default

    /**
     * FFmpeg's {@code analyzeduration} AVOption defaults to {@code 0}
     * ("unset"), which {@code libavformat/demux.c}'s {@code
     * avformat_find_stream_info} then resolves to {@code 5*AV_TIME_BASE} =
     * 5,000,000us (5s) for most formats -- <b>and, verified against the same
     * source, a full {@code 7*AV_TIME_BASE} = 7,000,000us (7s) specifically
     * when the payload is detected as {@code mpeg}/{@code mpegts}</b>, a
     * common RTSP payload for many IP cameras and drone companions. This is
     * the real source of the "several-second RTSP connect stall" this task
     * set out to fix -- not, as it turns out, {@code max_delay} (see {@link
     * #DEFAULT_MAX_DELAY_MICROS} below for why). Bounding this down is the
     * single biggest connect-time lever of the four options in this block.
     */
    static final String OPTION_ANALYZE_DURATION_MICROS = "analyzeduration";
    static final String DEFAULT_ANALYZE_DURATION_MICROS = "1000000"; // 1s, vs. FFmpeg's 5s (up to 7s for mpegts) default

    /**
     * FFmpeg's RTSP-demuxer-private {@code reorder_queue_size} AVOption
     * caps how many RTP packets the demuxer buffers to reorder out-of-order
     * arrivals. Verified against FFmpeg 6.1.1's {@code libavformat/rtsp.c}
     * ({@code ff_rtsp_open_transport_ctx}): left unset (its own default,
     * {@code -1}), this resolves <i>adaptively</i> -- {@code 0} packets when
     * {@code rtsp_transport=tcp} (this module's own default, see {@link
     * #DEFAULT_RTSP_TRANSPORT} -- TCP already guarantees in-order delivery,
     * so a reorder queue buys nothing) or FFmpeg's own {@code
     * RTP_REORDER_QUEUE_DEFAULT_SIZE} = 500 packets for {@code udp}.
     * <p>Pinned at {@code 0} here: for this module's actual TCP-default path
     * that is a no-op -- documents, doesn't change, today's behavior (same
     * spirit as {@code MediamtxStreamPublisher}'s {@code setMaxBFrames(0)},
     * docs/MVP2-PLAN.md V-a) -- but it is a genuine guard against a silent
     * multi-hundred-packet buffer if a device option ever overrides {@link
     * #OPTION_RTSP_TRANSPORT} to {@code udp}: without this pin, that source
     * would inherit the 500-packet default, which at typical RTP packet
     * rates can mean seconds of demuxer-side buffering. <b>Trade-off, stated
     * honestly</b>: a {@code udp} source on a genuinely lossy/jittery link
     * also loses that 500-packet reorder tolerance if this pin is left in
     * place -- override {@code reorder_queue_size} back up via the same
     * device-option map for that case; this module does not attempt to
     * auto-detect it.
     */
    static final String OPTION_REORDER_QUEUE_SIZE = "reorder_queue_size";
    static final String DEFAULT_REORDER_QUEUE_SIZE = "0"; // packets; pins this module's already-TCP-implied default

    /**
     * Bounds (microseconds) how long the RTSP demuxer waits for a
     * straggling/out-of-order RTP packet before force-delivering what it
     * already has ({@code libavformat/rtsp.c}'s {@code ff_rtsp_fetch_packet}:
     * {@code wait_end = first_queue_time + max_delay}).
     * <p><b>Must be applied via {@link FFmpegFrameGrabber#setMaxDelay(int)},
     * not {@link FFmpegFrameGrabber#setOption}</b> -- verified against
     * JavaCV 1.5.10's own source ({@code FFmpegFrameGrabber#startUnsafe}):
     * although {@code max_delay} is a valid generic AVOption that {@code
     * setOption} would apply during {@code avformat_open_input}, JavaCV
     * unconditionally overwrites it immediately afterwards with {@code
     * oc.max_delay(this.maxDelay)} -- the grabber's own {@code maxDelay}
     * Java field, default {@code -1} -- silently discarding anything set the
     * {@code setOption} way. This is exactly the "check how options are set
     * -- setOption vs specific setters" pitfall docs/MVP2-PLAN.md V-c's brief
     * warns about, caught by reading JavaCV's source rather than assuming.
     * <p><b>Deliberately not the commonly-cited "500ms, vs. a 7s default".</b>
     * Verified against {@code rtsp.c}: the RTSP demuxer's own built-in
     * default (unset {@code max_delay < 0}) already resolves to {@code
     * DEFAULT_REORDERING_DELAY} = 100,000us (100ms) -- not several seconds.
     * The real "7 second" figure is genuine (see {@link
     * #DEFAULT_ANALYZE_DURATION_MICROS} above) but belongs to {@code
     * analyzeduration}'s mpegts-payload branch, not this option. Explicitly
     * setting 500ms here would have <i>loosened</i>, not tightened, the
     * demuxer's own already-tight default. This constant instead pins the
     * value at that already-good 100ms -- living documentation against a
     * future FFmpeg version silently changing {@code DEFAULT_REORDERING_DELAY}
     * (same intent as {@link #DEFAULT_REORDER_QUEUE_SIZE} above), not a
     * behavior change. Going tighter than 100ms was considered and rejected:
     * {@code wait_end} is what gives a jittery/far source's late RTP packets
     * a chance to arrive before the demuxer gives up and force-delivers
     * (logging FFmpeg's own "max delay reached" warning) -- shrinking it
     * further trades a little latency for materially worse robustness on
     * exactly the non-ideal links this task asked to reason about.
     */
    static final String OPTION_MAX_DELAY_MICROS = "max_delay";
    static final String DEFAULT_MAX_DELAY_MICROS = "100000"; // 100ms; pins rtsp.c's own DEFAULT_REORDERING_DELAY

    // -- docs/DRONE-INFRA-PLAN.md I-h: SRT (srt scheme only) --
    // Every FFmpeg AVOption name/default below was verified against this module's
    // actual pinned ffmpeg-platform-gpl 6.1.1-1.5.10 native binary (libavformat.so.60
    // built with --enable-libsrt, confirmed present by extracting the jar and grepping
    // its strings for SRTO_LATENCY/SRTO_PASSPHRASE/SRTO_MODE-family symbols and the
    // libsrt.c help text), not assumed from FFmpeg's own docs/wiki -- same discipline
    // as the RTSP V-c block above.

    /**
     * {@link StreamDescriptor#options()} key for SRT receive latency, in
     * <b>milliseconds</b> -- this is the platform's own contract unit
     * (docs/DRONE-INFRA-PLAN.md I-h: "{@code latency} (ms, SRT's core
     * knob)"). <b>FFmpeg's own {@code latency} AVOption (libavformat's
     * {@code libsrt.c}, confirmed present in this build's binary via the
     * {@code "receive latency (in microseconds)"}/{@code "peer latency (in
     * microseconds)"} help strings on its sibling {@code rcvlatency}/{@code
     * peerlatency} options) is in <b>microseconds</b>, not milliseconds</b>
     * -- {@link #configureSrtOptions} converts (×1000) before calling {@code
     * setOption}. Getting this conversion wrong silently changes the
     * requested latency budget by a factor of 1000, so it is called out
     * here and at the call site, not just implied by the constant name.
     */
    static final String OPTION_SRT_LATENCY_MILLIS = "latency";
    static final String DEFAULT_SRT_LATENCY_MILLIS = "120"; // 120ms -- matches libsrt's own built-in default exactly

    /**
     * {@link StreamDescriptor#options()} key mapping directly to FFmpeg's
     * {@code mode} AVOption (verified present in this build's binary: the
     * {@code caller}/{@code listener}/{@code rendezvous} AVOption constant
     * names appear verbatim in {@code libavformat.so.60}'s strings). Only
     * {@code caller} (the app dials the encoder) and {@code listener} (the
     * app binds; encoder dials in) are exposed per docs/DRONE-INFRA-PLAN.md
     * I-h's frozen contract -- {@code rendezvous} exists in FFmpeg but is
     * out of scope here. When absent, {@link #defaultSrtMode(URI)} infers
     * {@code listener} for an any-address host ({@code 0.0.0.0}/{@code ::}/
     * unset -- the natural reading of {@code srt://0.0.0.0:port}: the app
     * binds and waits) and {@code caller} otherwise (a real host means the
     * app dials out to it).
     */
    static final String OPTION_SRT_MODE = "mode";
    static final String SRT_MODE_CALLER = "caller";
    static final String SRT_MODE_LISTENER = "listener";

    /**
     * {@link StreamDescriptor#options()} key mapping directly to FFmpeg's
     * {@code streamid} AVOption (verified present: {@code SRTO_STREAMID}/
     * {@code srt_streamid} appear in this build's binary strings) -- an
     * arbitrary string an SRT caller passes to a listener to identify
     * itself/select a resource, used by e.g. SRT relay/gateway services.
     * Pure passthrough, no default: absent means "don't set one".
     */
    static final String OPTION_SRT_STREAMID = "streamid";

    /**
     * {@link StreamDescriptor#options()} key mapping directly to FFmpeg's
     * {@code passphrase} AVOption (verified present in this build's binary
     * strings) -- enables SRT's built-in AES encryption. Pure passthrough,
     * no default: absent means "no encryption". When present, {@link
     * #configureSrtOptions} also sets FFmpeg's {@code pbkeylen} AVOption
     * (verified present: {@code SRTO_PBKEYLEN}/{@code "Crypto key len in
     * bytes {16,24,32} Default: 16 (128-bit)"} appear in this build's
     * binary strings) to {@value #DEFAULT_SRT_PBKEYLEN} -- FFmpeg/libsrt
     * already default an unset {@code pbkeylen} to 16 bytes (128-bit)
     * internally when a passphrase is set, so this is not strictly required
     * for encryption to work, but pinning it explicitly documents the
     * chosen key length rather than relying on an unstated library default
     * (same "living documentation" idiom as the RTSP V-c block's {@code
     * reorder_queue_size}/{@code max_delay} pins above).
     */
    static final String OPTION_SRT_PASSPHRASE = "passphrase";
    private static final String FFMPEG_OPTION_SRT_PBKEYLEN = "pbkeylen";
    private static final String DEFAULT_SRT_PBKEYLEN = "16";

    // -- docs/DRONE-INFRA-PLAN.md I-h: UDP/MPEG-TS (udp scheme only) --

    /**
     * {@link StreamDescriptor#options()} key mapping directly to FFmpeg's
     * {@code fifo_size} AVOption on the {@code udp} protocol (verified
     * present in this build's binary strings: {@code "set the UDP receiving
     * circular buffer size, expressed as a number of packets with size of
     * 188 bytes"}) -- the demuxer-side receive ring buffer, in 188-byte
     * MPEG-TS packets. FFmpeg's own unset default is {@code 7*4096} = 28,672
     * packets (~5.4MB, confirmed via the same option table) -- generous
     * loss tolerance at the cost of several seconds of possible buffering
     * latency on a live drone feed. {@value #DEFAULT_UDP_FIFO_SIZE_PACKETS}
     * packets (~96KB) trades most of that tolerance away for materially
     * lower latency, matching this module's low-latency-by-default posture
     * for live sources (same intent as the RTSP V-c block's {@code
     * probesize}/{@code analyzeduration} tightening above).
     */
    static final String OPTION_UDP_FIFO_SIZE = "fifo_size";
    static final String DEFAULT_UDP_FIFO_SIZE_PACKETS = "512"; // ~96KB, vs. FFmpeg's own ~5.4MB (28,672 packet) default

    /**
     * {@link StreamDescriptor#options()} key mapping directly to FFmpeg's
     * {@code overrun_nonfatal} AVOption on the {@code udp} protocol
     * (verified present: {@code "survive in case of UDP receiving circular
     * buffer overrun"} / {@code "Circular buffer overrun. Surviving due to
     * overrun_nonfatal option"} appear in this build's binary strings).
     * FFmpeg's own default is {@code false} -- a full receive ring buffer
     * (more likely here given the tightened {@link #OPTION_UDP_FIFO_SIZE}
     * default above) aborts the stream entirely. Defaulted to {@code true}
     * here: a live drone feed dropping a burst of packets under transient
     * jitter/loss should keep decoding what it can, the same "stay up
     * through jitter" posture as the RTSP {@code reorder_queue_size}/{@code
     * max_delay} tuning above, not hard-fail the whole stream.
     */
    static final String OPTION_UDP_OVERRUN_NONFATAL = "overrun_nonfatal";
    static final String DEFAULT_UDP_OVERRUN_NONFATAL = "1"; // true; survive fifo overruns rather than aborting

    /**
     * {@link StreamDescriptor#options()} key mapping directly to FFmpeg's
     * {@code buffer_size} AVOption on the {@code udp} protocol -- the
     * underlying OS socket receive buffer size in bytes ({@code SO_RCVBUF}).
     * Pure passthrough, no forced default: FFmpeg's own unset value ({@code
     * -1}) means "leave the OS default alone", which this module does not
     * override on principle -- unlike {@link #OPTION_UDP_FIFO_SIZE} (an
     * application-level ring buffer this module actively wants small for
     * latency), the OS socket buffer is a system-level knob a specific
     * deployment may need to size up for a lossy/bursty link; this module
     * has no basis to pick a better default than "unset" for that case.
     */
    static final String OPTION_UDP_BUFFER_SIZE = "buffer_size";

    /**
     * FFmpeg's {@code udp} protocol {@code timeout} AVOption (verified
     * present in this build's binary strings: {@code "set raise error
     * timeout, in microseconds (only in read mode)"}) -- bounds how long a
     * udp read blocks with zero incoming packets before failing with an
     * error, instead of FFmpeg's own unset default of {@code 0} (no
     * timeout: block forever).
     * <p><b>Not exposed as a {@link StreamDescriptor#options()} key</b> --
     * unlike the three options above, this is an internal safety default
     * only, always applied and not currently overridable. It was added
     * after a serious finding while writing this class's own tests:
     * JavaCV's {@code FFmpegFrameGrabber}/{@code
     * FFmpegFrameRecorder#start()} both synchronize on a shared, static,
     * process-wide lock (verified via a real thread dump: both block on
     * the same {@code org.bytedeco.ffmpeg.global.avcodec} class monitor)
     * for the *entire duration* of their native {@code
     * avformat_open_input} call -- so a {@code udp} source that nobody is
     * ever sending a packet to would, without this bound, hold that lock
     * <i>forever</i>, silently blocking every <i>other</i> video source's
     * own open/reconnect attempts in the same JVM (the application's
     * stream supervisor retries a failed {@link #open} indefinitely with
     * its own 1s-30s backoff -- each retry would re-enter the same stuck
     * lock). This is not hypothetical: it was reproduced directly while
     * developing this module's own udp loopback test (a receiver whose
     * native probe never resolved wedged an unrelated, later test in the
     * same JVM for 10+ minutes) -- see this module's MODULE.md Gotchas for
     * the full investigation. {@value #DEFAULT_UDP_TIMEOUT_MICROS}
     * microseconds (5s) is a deliberately generous "is this source dead"
     * bound, not a latency knob -- it exists purely so one open attempt
     * fails cleanly (letting the caller/supervisor retry) instead of
     * hanging the whole process.
     */
    private static final String OPTION_UDP_TIMEOUT_MICROS = "timeout";
    private static final String DEFAULT_UDP_TIMEOUT_MICROS = "5000000"; // 5s; dead-source bound, not exposed as an option

    /**
     * FFmpeg format name forced on every {@code udp} scheme open via {@link
     * FFmpegFrameGrabber#setFormat(String)} -- a raw {@code udp://} URL
     * carries no container-level self-description the way {@code rtsp://}'s
     * SDP {@code DESCRIBE} response does, so without an explicit format
     * hint {@code avformat_open_input}'s format probe must guess from
     * whatever datagrams it happens to receive during probing.
     * docs/DRONE-INFRA-PLAN.md I-h's frozen contract already assumes
     * MPEG-TS for {@code udp} ("MPEG-TS assumed"), so forcing it here is
     * not a guess -- it names the one container this protocol is
     * contracted to carry, and removes format auto-detection as a failure
     * mode entirely rather than trusting it to keep guessing right.
     * <b>Honesty note</b>: an ad hoc local A/B check while writing this
     * class found format auto-probing succeeded just as reliably as an
     * explicit {@code setFormat} on this quiet loopback (5/5 either way) --
     * this module does not have measured evidence that a real, lossier/
     * jitterier drone link would probe as reliably, so {@code setFormat} is
     * kept on contract-correctness grounds (it names the guaranteed
     * container instead of relying on inference) rather than a proven
     * reliability delta.
     */
    private static final String FORMAT_MPEGTS = "mpegts";

    private static final int PUBLISHER_BUFFER_CAPACITY = 4;
    private static final long CLOSE_JOIN_TIMEOUT_MILLIS = 20_000L;

    private static final System.Logger LOG = System.getLogger(FfmpegVideoSource.class.getName());

    private final Map<StreamId, StreamRuntime> runtimes = new ConcurrentHashMap<>();
    /**
     * Consecutive open-failure count per stream — purely to give the WARN log below "attempt N"
     * context (e.g. a dead RTSP path retried endlessly by the {@code vision-application} stream
     * supervisor, {@code SupervisedPublisher}, on a 1s→30s backoff). This class has no knowledge of
     * the supervisor's own backoff timer — each retry is just another {@link #open}/{@link #openAny}
     * call — so this is a local, best-effort counter, not the supervisor's authoritative one. Reset
     * to zero the moment a grabber actually starts successfully (see {@link StreamRuntime#runGrabLoop}),
     * and removed entirely on an explicit {@link #close(StreamId)}.
     */
    private final Map<StreamId, AtomicInteger> consecutiveOpenFailures = new ConcurrentHashMap<>();

    public FfmpegVideoSource() {
        ensureQuietLogging();
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
    //
    // org.bytedeco.javacv.FFmpegLogCallback.set() (routing native logs
    // through javacpp's Logger, java.util.logging-flavored) was evaluated
    // and rejected: an empirical check (a standalone FFmpegFrameRecorder
    // harness against this module's pinned FFmpeg 6.1.1/JavaCV 1.5.10)
    // showed the callback does no level filtering of its own -- it still
    // requires this exact av_log_set_level(WARNING) call to suppress
    // anything -- and once active, it fragments FFmpeg's own multi-part log
    // lines (e.g. the muxer's "Output #0 ..." block) into a stream of
    // separately-prefixed partial lines, which is objectively worse than
    // the default callback's coherent raw output, for no offsetting
    // benefit (this codebase has no JUL-to-Spring bridge configured, and
    // adapter-publish-hls logs via System.Logger, not java.util.logging
    // anyway). Plain av_log_set_level keeps FFmpeg's well-formed default
    // formatting and only changes the threshold.
    private static boolean quietLoggingConfigured = false;

    /**
     * Idempotent; lowers FFmpeg's native log threshold to {@code
     * AV_LOG_ERROR} so grabber start/stop no longer dumps INFO-level
     * banners to stdout/stderr -- only warnings and errors from the native
     * layer still print. Safe to call repeatedly (e.g. once per constructed
     * instance, including across many instances in a single test run): the
     * guard makes every call after the first a no-op.
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
     * Test seam: runs the exact production grab loop against any URI (an
     * {@code rtsp://} camera, a local {@code file:} video, ...) without the
     * {@link #supports(StreamDescriptor)} protocol check. This lets
     * integration tests exercise the real FFmpeg decode path against a
     * small local file without a live camera.
     *
     * @param id      identity to associate with the opened stream
     * @param uri     resource to open; RTSP-specific grabber options and
     *                real-time pacing (see class javadoc) are only applied
     *                based on {@code uri.getScheme()}
     * @param options adapter options, see class javadoc; may be empty
     * @return a per-open publisher of frames; see {@link VideoSourcePort} for
     *         delivery/backpressure semantics
     */
    Flow.Publisher<VideoFrame> openAny(StreamId id, URI uri, Map<String, String> options) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (uri == null) {
            throw new IllegalArgumentException("uri must not be null");
        }
        Map<String, String> effectiveOptions = options == null ? Map.of() : options;
        StreamRuntime runtime = new StreamRuntime(id, uri, effectiveOptions, consecutiveOpenFailures);
        StreamRuntime previous = runtimes.put(id, runtime);
        if (previous != null) {
            previous.close(); // defensive: an id must not have two live runtimes
        }
        runtime.start();
        return runtime.publisher;
    }

    @Override
    public void close(StreamId id) {
        StreamRuntime runtime = runtimes.remove(id);
        if (runtime != null) {
            runtime.close();
        }
        consecutiveOpenFailures.remove(id);
    }

    /**
     * Applies this class's RTSP-only grabber options — the pre-existing
     * {@code rtsp_transport}/{@code timeout}/{@code rw_timeout} plus
     * docs/MVP2-PLAN.md V-c's low-latency demuxer tuning ({@code
     * probesize}/{@code analyzeduration}/{@code reorder_queue_size}/{@code
     * max_delay}, see each constant's javadoc above for the verified
     * FFmpeg/JavaCV facts behind its value). Package-private test seam,
     * mirroring {@code MediamtxStreamPublisher.configureRecorder}
     * (adapter-publish-hls): constructing an {@link FFmpegFrameGrabber} and
     * calling {@code setOption}/{@code setMaxDelay} only assigns fields — no
     * native/network I/O happens until {@link FFmpegFrameGrabber#start()},
     * which this method deliberately never calls — so a test can assert the
     * exact options a real grabber would be started with, without a live
     * camera or RTSP server.
     *
     * <p>Called only from the {@code uri.getScheme().equals("rtsp")} branch
     * of {@link StreamRuntime#newGrabber()} — a {@code file} source must
     * never have any of these applied (paced local-file playback is not the
     * live-network case this tuning targets).
     */
    static void configureRtspOptions(FFmpegFrameGrabber grabber, Map<String, String> options) {
        String transport = options.getOrDefault(OPTION_RTSP_TRANSPORT, DEFAULT_RTSP_TRANSPORT);
        grabber.setOption(OPTION_RTSP_TRANSPORT, transport);
        String timeoutMicros = options.getOrDefault(OPTION_TIMEOUT_MICROS, DEFAULT_TIMEOUT_MICROS);
        grabber.setOption(OPTION_TIMEOUT_MICROS, timeoutMicros);
        grabber.setOption("rw_timeout", timeoutMicros);

        grabber.setOption(OPTION_PROBESIZE_BYTES, options.getOrDefault(OPTION_PROBESIZE_BYTES, DEFAULT_PROBESIZE_BYTES));
        grabber.setOption(OPTION_ANALYZE_DURATION_MICROS,
                options.getOrDefault(OPTION_ANALYZE_DURATION_MICROS, DEFAULT_ANALYZE_DURATION_MICROS));
        grabber.setOption(OPTION_REORDER_QUEUE_SIZE,
                options.getOrDefault(OPTION_REORDER_QUEUE_SIZE, DEFAULT_REORDER_QUEUE_SIZE));
        // Not setOption: see DEFAULT_MAX_DELAY_MICROS's javadoc -- JavaCV overwrites
        // an AVOption-dict "max_delay" with its own maxDelay field right after open.
        grabber.setMaxDelay(intOption(options, OPTION_MAX_DELAY_MICROS, DEFAULT_MAX_DELAY_MICROS));
    }

    /**
     * Lenient integer option parsing, matching this module's existing
     * lenient-parsing idiom for other options ({@code StreamRuntime}'s
     * {@code booleanOption}): missing/blank falls back to {@code
     * defaultValue}, and so does a malformed (non-integer) value — a bad
     * device-option override must never crash stream setup.
     */
    private static int intOption(Map<String, String> options, String key, String defaultValue) {
        String raw = options.getOrDefault(key, defaultValue);
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return Integer.parseInt(defaultValue); // malformed device override: keep the (well-formed) default
        }
    }

    /**
     * Applies this class's SRT-only grabber options — docs/DRONE-INFRA-PLAN.md
     * I-h. Package-private test seam, same idiom as {@link
     * #configureRtspOptions}: constructing an {@link FFmpegFrameGrabber} and
     * calling {@code setOption} only assigns fields, no network I/O, so a
     * test can assert exactly what a real grabber would be started with
     * against any {@code srt://} URI, live SRT peer or not.
     *
     * <p><b>Applied via {@code setOption}, not the {@code srt://} URL's
     * query string</b> — both are documented FFmpeg mechanisms for SRT
     * protocol options, and both were actually tried (not assumed) while
     * writing this class: a standalone probe using {@code setOption("mode",
     * "listener")} measurably steered {@code FFmpegFrameGrabber#start()}
     * into the SRT listener accept-wait native code path rather than the
     * caller-connect path, proving {@code setOption} genuinely reaches the
     * SRT protocol's own private AVOptions in this exact JavaCV/FFmpeg
     * build — the identical {@code srt://…?mode=listener} URL-query-string
     * form was also tried and reached the same code path, no better or
     * worse. {@code setOption} was kept: one idiom across rtsp/srt/udp,
     * and no hand-building/escaping a query string on top of a
     * caller-supplied URI that may already carry its own. See this
     * module's MODULE.md Gotchas ("verified finding: a JVM-hosted SRT
     * connection via JavaCV does not complete in this development
     * environment") for the full investigation, including why {@code
     * FfmpegVideoSourceTest}'s own SRT loopback test degrades to a skip
     * rather than asserting success here — that is a separate, downstream
     * native-connectivity finding, not evidence against this method's own
     * option-application correctness (which the unit tests below do cover).
     *
     * <p>Called only from the {@code uri.getScheme().equals("srt")} branch
     * of {@link StreamRuntime#newGrabber()}.
     */
    static void configureSrtOptions(FFmpegFrameGrabber grabber, URI uri, Map<String, String> options) {
        int latencyMillis = intOption(options, OPTION_SRT_LATENCY_MILLIS, DEFAULT_SRT_LATENCY_MILLIS);
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
     * Infers the SRT connection mode when the {@link #OPTION_SRT_MODE}
     * device option is absent — see {@link #OPTION_SRT_MODE}'s javadoc for
     * the full rationale: an any-address host reads as "bind and wait"
     * ({@code listener}), any real host reads as "dial out" ({@code
     * caller}).
     */
    private static String defaultSrtMode(URI uri) {
        String host = uri.getHost();
        boolean anyAddress = host == null || host.isBlank() || "0.0.0.0".equals(host) || "::".equals(host);
        return anyAddress ? SRT_MODE_LISTENER : SRT_MODE_CALLER;
    }

    /**
     * Applies this class's UDP/MPEG-TS-only grabber options —
     * docs/DRONE-INFRA-PLAN.md I-h. Package-private test seam, same idiom as
     * {@link #configureRtspOptions}/{@link #configureSrtOptions}.
     *
     * <p>Always forces the demuxer format to {@code mpegts} via {@link
     * FFmpegFrameGrabber#setFormat(String)} — see {@link #FORMAT_MPEGTS}'s
     * javadoc for why a raw {@code udp://} source needs the hint. Called
     * only from the {@code uri.getScheme().equals("udp")} branch of {@link
     * StreamRuntime#newGrabber()}.
     */
    static void configureUdpOptions(FFmpegFrameGrabber grabber, Map<String, String> options) {
        grabber.setFormat(FORMAT_MPEGTS);
        grabber.setOption(OPTION_UDP_FIFO_SIZE,
                options.getOrDefault(OPTION_UDP_FIFO_SIZE, DEFAULT_UDP_FIFO_SIZE_PACKETS));
        grabber.setOption(OPTION_UDP_OVERRUN_NONFATAL,
                options.getOrDefault(OPTION_UDP_OVERRUN_NONFATAL, DEFAULT_UDP_OVERRUN_NONFATAL));
        String bufferSize = options.get(OPTION_UDP_BUFFER_SIZE);
        if (bufferSize != null && !bufferSize.isBlank()) {
            grabber.setOption(OPTION_UDP_BUFFER_SIZE, bufferSize);
        }
        // Not overridable, not part of the frozen options contract -- see DEFAULT_UDP_TIMEOUT_MICROS's
        // javadoc for why this internal safety bound exists (a stuck native open() call holds a
        // process-wide JavaCV lock forever otherwise, verified via a real thread dump).
        grabber.setOption(OPTION_UDP_TIMEOUT_MICROS, DEFAULT_UDP_TIMEOUT_MICROS);
    }

    /** Per-open runtime: a dedicated grab thread feeding a {@link SubmissionPublisher}. */
    private static final class StreamRuntime {
        private final StreamId streamId;
        private final URI uri;
        private final Map<String, String> options;
        private final boolean loop;
        private final boolean paced;
        private final Map<StreamId, AtomicInteger> consecutiveOpenFailures;
        private final SubmissionPublisher<VideoFrame> publisher =
                new SubmissionPublisher<>(ForkJoinPool.commonPool(), PUBLISHER_BUFFER_CAPACITY);
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicBoolean stopRequested = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile Thread grabThread;

        StreamRuntime(StreamId streamId, URI uri, Map<String, String> options,
                      Map<StreamId, AtomicInteger> consecutiveOpenFailures) {
            this.streamId = streamId;
            this.uri = uri;
            this.options = options;
            this.loop = booleanOption(options, OPTION_LOOP, DEFAULT_LOOP);
            this.paced = PROTOCOL_FILE.equalsIgnoreCase(uri.getScheme());
            this.consecutiveOpenFailures = consecutiveOpenFailures;
        }

        void start() {
            grabThread = new Thread(this::runGrabLoop, "rtsp-video-" + streamId.value());
            grabThread.setDaemon(true);
            grabThread.start();
        }

        private void runGrabLoop() {
            FFmpegFrameGrabber grabber = null;
            boolean errored = false;
            try {
                grabber = newGrabber();
                grabber.start();
                // A successful connect/DESCRIBE is proof this stream is no longer stuck failing to
                // open at all (e.g. a dead RTSP path returning 404) -- reset the WARN-log attempt
                // counter below, mirroring MediamtxStreamPublisher.StreamState's own
                // reset-on-recovery convention (adapter-publish-hls).
                consecutiveOpenFailures.remove(streamId);
                // Real-time pacing baseline (file sources only, see class javadoc):
                // -1 means "no previous frame yet" -- the next grabbed frame sets the
                // baseline without sleeping, whether that is the very first frame or
                // the first frame after a loop restart.
                long pacingBaselineTimestampMicros = -1;
                long pacingBaselineWallNanos = 0;
                while (!stopRequested.get()) {
                    // grabImage(), not grab(): grab() also returns audio/data frames, and mp4
                    // muxers interleave audio up to ~0.5s AHEAD of video -- pacing on those
                    // look-ahead timestamps stalled the loop until wall-clock caught up with
                    // the audio track, starving video down to ~2fps (observed with a real
                    // 30fps clip carrying AAC). grabImage() skips non-video packets, so the
                    // pacing timeline is the video track's own monotonic timestamps.
                    Frame frame = grabber.grabImage();
                    if (frame == null) {
                        if (loop && !stopRequested.get()) {
                            grabber.restart(); // stop() + start(): reopens the file from the beginning
                            pacingBaselineTimestampMicros = -1; // reset pacing baseline across the loop restart
                            continue;
                        }
                        break; // end of stream (e.g. a file source ran out) -- graceful completion
                    }
                    if (paced) {
                        long timestampMicros = grabber.getTimestamp();
                        if (pacingBaselineTimestampMicros >= 0) {
                            long targetDeltaMicros = timestampMicros - pacingBaselineTimestampMicros;
                            long elapsedMicros = (System.nanoTime() - pacingBaselineWallNanos) / 1_000L;
                            sleepMicros(targetDeltaMicros - elapsedMicros);
                        }
                        pacingBaselineTimestampMicros = timestampMicros;
                        pacingBaselineWallNanos = System.nanoTime();
                    }
                    if (frame.image == null || frame.image.length == 0) {
                        continue; // audio/data-only frame: no pixel payload to publish
                    }
                    ByteBuffer copy = FrameConverter.copyBgr24(frame);
                    VideoFrame videoFrame = new VideoFrame(streamId, sequence.getAndIncrement(), Instant.now(),
                            frame.imageWidth, frame.imageHeight, PixelFormat.BGR24, copy);
                    // Latest-wins backpressure: never block capture for a slow subscriber;
                    // when a subscriber's small buffer is full, the offered frame is dropped
                    // instead of queuing capture indefinitely.
                    publisher.offer(videoFrame, (subscriber, dropped) -> true);
                }
            } catch (Exception e) {
                errored = true;
                if (!stopRequested.get()) {
                    // Unrecoverable grab failure: signal onError and stop producing frames. This is
                    // the only place a dead/unreachable source (e.g. an RTSP DESCRIBE 404 against a
                    // path nothing is publishing to) becomes visible anywhere but FFmpeg's own
                    // stderr -- log it at WARN with the source and a local consecutive-failure count
                    // before handing off to the caller (vision-application's SupervisedPublisher,
                    // which retries this exact open() with a 1s->30s exponential backoff,
                    // indefinitely, until it succeeds).
                    int attempt = consecutiveOpenFailures
                            .computeIfAbsent(streamId, unused -> new AtomicInteger())
                            .incrementAndGet();
                    LOG.log(System.Logger.Level.WARNING, () -> "Video source error for stream "
                            + streamId.value() + " at " + uri + " (consecutive failure " + attempt
                            + "); will be retried with backoff by the stream supervisor, dropping "
                            + "frames until it recovers", e);
                    publisher.closeExceptionally(e);
                }
            } finally {
                releaseQuietly(grabber);
                if (!errored) {
                    publisher.close();
                }
            }
        }

        private FFmpegFrameGrabber newGrabber() {
            FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(resolveFilename(uri));
            grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);
            String scheme = uri.getScheme();
            if (PROTOCOL_RTSP.equalsIgnoreCase(scheme)) {
                configureRtspOptions(grabber, options);
            } else if (PROTOCOL_SRT.equalsIgnoreCase(scheme)) {
                configureSrtOptions(grabber, uri, options);
            } else if (PROTOCOL_UDP.equalsIgnoreCase(scheme)) {
                configureUdpOptions(grabber, options);
            }
            return grabber;
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                stopRequested.set(true);
                Thread thread = grabThread;
                if (thread != null && thread != Thread.currentThread()) {
                    thread.interrupt(); // best-effort; native grab() may not respond to this
                    try {
                        thread.join(CLOSE_JOIN_TIMEOUT_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                publisher.close();
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

        private static String resolveFilename(URI uri) {
            if (PROTOCOL_FILE.equalsIgnoreCase(uri.getScheme())) {
                return Paths.get(uri).toString();
            }
            return uri.toString();
        }

        /** Sleeps the given microsecond duration; clamps negative/zero to a no-op. */
        private static void sleepMicros(long micros) {
            if (micros <= 0) {
                return;
            }
            try {
                TimeUnit.MICROSECONDS.sleep(micros);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Lenient boolean option parsing, matching this module's existing
         * idiom for other options: missing/blank falls back to {@code
         * defaultValue}, and so does anything that isn't (case-insensitively)
         * {@code "true"} or {@code "false"} -- a malformed value is never
         * allowed to crash stream setup.
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
