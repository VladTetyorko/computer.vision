package com.drones.vision.api.support;

import java.time.Duration;
import java.util.Objects;

/**
 * Framework-free tunables for this module's edge-local infrastructure — docs/plans/active/LAYERING-REFACTOR-PLAN.md
 * §1.3/§2.2's frozen {@code vision.api} property-key contract, extracted structurally out of what
 * were previously scattered {@code private static final} constants on {@code SnapshotJpegEncoder},
 * {@code HlsProxyController}, {@code LiveUpdateRegistry}, {@code AssetImageController}, and a few
 * per-controller paging defaults. {@link #defaults()} reproduces every one of those old literals
 * exactly — this is a structural move, not a retune.
 *
 * <p><b>Why this record lives in {@code vision-api}, not {@code vision-app}</b>: {@code
 * vision-api} is a driving adapter and may not gain a dependency on {@code vision-app} (the
 * hexagon runs the other way — see docs/plans/active/LAYERING-REFACTOR-PLAN.md §1.3 rule 1, which reserves
 * {@code @ConfigurationProperties} records for {@code vision-app} alone). This is the same "plain
 * settings record, framework-free, with a {@code static defaults()} factory" shape {@code
 * FfmpegSettings} (adapter-rtsp) already establishes; a later wave adds a Spring {@code
 * VisionApiProperties}-shaped {@code @ConfigurationProperties} record in {@code vision-app} (under
 * {@code ...app.config.properties}) and maps it onto an instance of this one, exactly like every
 * adapter's own settings record.
 *
 * <p><b>Wiring status</b>: {@link SnapshotJpegEncoder}, {@code HlsProxyController} and {@code
 * LiveUpdateRegistry} each take an instance of the relevant nested record today — the last two as
 * of docs/plans/done/SCALE-100-PLAN.md §5 S7, which finished the extraction
 * docs/plans/active/LAYERING-REFACTOR-PLAN.md §7 row B deferred. Both take the nested record alone
 * (not the whole top-level {@code VisionApiProperties}), matching {@code
 * PublishWiring#snapshotJpegEncoder}'s established bridge shape: {@code vision-app}'s
 * {@code @ConfigurationProperties}-bound mirror is mapped field-by-field onto an instance of this
 * plain record in {@code vision-app}'s wiring, never referenced directly by either controller.
 * {@code AssetImageController}'s upload cap and the per-controller paging defaults still read
 * their own local constants — rewiring those needs a wiring change outside this wave's scope. This
 * record is nonetheless the single documented source of truth for every one of those values'
 * current defaults, ready for that wave to wire through.
 *
 * @param snapshot {@code GET /api/streams/{streamId}/snapshot}'s downscale/encode tunables
 * @param hlsProxy {@code HlsProxyController}'s upstream HTTP client timeouts
 * @param live     the SSE data plane's coalescing/heartbeat cadence and per-topic ring-buffer capacities
 * @param paging   the shared default/max page size for list endpoints that accept a {@code limit}
 * @param upload   the asset-image upload size cap
 */
public record VisionApiProperties(Snapshot snapshot, HlsProxy hlsProxy, Live live, Paging paging, Upload upload) {

    public VisionApiProperties {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(hlsProxy, "hlsProxy must not be null");
        Objects.requireNonNull(live, "live must not be null");
        Objects.requireNonNull(paging, "paging must not be null");
        Objects.requireNonNull(upload, "upload must not be null");
    }

    /**
     * Reproduces every literal this record replaces, exactly as it stood before this extraction —
     * see each nested record's own javadoc for provenance.
     */
    public static VisionApiProperties defaults() {
        return new VisionApiProperties(Snapshot.defaults(), HlsProxy.defaults(), Live.defaults(),
                Paging.defaults(), Upload.defaults());
    }

    /**
     * {@code SnapshotJpegEncoder}'s downscale/encode tunables (docs/plans/done/MVP3-PLAN.md C-a).
     *
     * @param maxWidth    max width a snapshot is downscaled to before JPEG encoding; height scales
     *                    to preserve aspect ratio
     * @param jpegQuality JPEG encoder quality, {@code 0.0}-{@code 1.0}
     */
    public record Snapshot(int maxWidth, float jpegQuality) {

        public Snapshot {
            if (maxWidth <= 0) {
                throw new IllegalArgumentException("maxWidth must be positive, was " + maxWidth);
            }
            if (jpegQuality <= 0f || jpegQuality > 1f) {
                throw new IllegalArgumentException("jpegQuality must be in (0,1], was " + jpegQuality);
            }
        }

        public static Snapshot defaults() {
            return new Snapshot(480, 0.8f);
        }
    }

    /**
     * {@code HlsProxyController}'s upstream {@code HttpClient} timeouts and its two bounded-buffer
     * sizing knobs (docs/plans/done/SCALE-100-PLAN.md §5 S7).
     *
     * @param connectTimeout           bound on establishing the upstream TCP connection
     * @param requestTimeout           bound on the whole upstream request/response round trip
     * @param errorBodyPreviewMaxChars how much of a non-2xx upstream error body to buffer for the
     *                                 diagnostic WARN log line — the only body bytes this controller
     *                                 ever holds in a Java array
     * @param maxRedirectHops          bound on the hand-followed redirect chain (mediamtx's own
     *                                 node-pinning flow is exactly one hop); higher only so a
     *                                 misbehaving or looping upstream fails fast instead of hanging
     * @param authUsername             mediamtx {@code read} account username (docs/plans/active/
     *                                 ASSET-FLOWS-PLAN.md &sect;2 S6) sent as an outbound {@code
     *                                 Authorization: Basic} header on every upstream fetch, now that
     *                                 mediamtx gates {@code read} on every path; {@code null}/blank
     *                                 sends no {@code Authorization} header (mediamtx auth off/
     *                                 unconfigured — the correct behaviour for most tests, which
     *                                 build this record via {@link #defaults()})
     * @param authPassword             password paired with {@code authUsername}; ignored when {@code
     *                                 authUsername} is {@code null}/blank
     */
    public record HlsProxy(Duration connectTimeout, Duration requestTimeout, int errorBodyPreviewMaxChars,
                            int maxRedirectHops, String authUsername, String authPassword) {

        public HlsProxy {
            requirePositive(connectTimeout, "connectTimeout");
            requirePositive(requestTimeout, "requestTimeout");
            requirePositive(errorBodyPreviewMaxChars, "errorBodyPreviewMaxChars");
            requirePositive(maxRedirectHops, "maxRedirectHops");
        }

        /** No mediamtx read credential — matches {@code vision.publish.enabled=false}/no-auth setups. */
        public static HlsProxy defaults() {
            return new HlsProxy(Duration.ofSeconds(5), Duration.ofSeconds(15), 200, 5, null, null);
        }
    }

    /**
     * The SSE data plane's coalescing/heartbeat cadence, per-topic ring-buffer capacities, and
     * {@code LiveUpdateRegistry}'s two per-connection dispatch bounds
     * (docs/plans/done/REALTIME-PLAN.md §4; {@code sendTimeout}/{@code bufferEviction} added
     * docs/plans/done/SCALE-100-PLAN.md §5 S2/S7).
     *
     * @param coalesce        how often pending telemetry/detections/fleet-recompute are flushed
     *                        into one envelope per topic
     * @param heartbeat       how often an idle SSE connection gets a comment-line heartbeat
     * @param telemetryBuffer per-asset {@code telemetry} topic ring-buffer capacity (FIFO)
     * @param eventBuffer     the {@code event} topic's ring-buffer capacity (FIFO)
     * @param detectionBuffer the {@code detection-events} topic's ring-buffer capacity (FIFO)
     * @param mapBuffer       the {@code map} topic's ring-buffer capacity (FIFO)
     * @param sendTimeout     how long a queued per-connection write may take before that
     *                        connection is unregistered as stalled/dead
     * @param bufferEviction  how often per-asset {@code telemetry}/{@code detections} buffers with
     *                        no subscriber left are swept away
     * @param systemSample    how often {@code com.drones.vision.api.live.SystemStatusSampler} samples
     *                        every subsystem for the {@code system} live topic (docs/plans/active/
     *                        LIVE-POLL-RETIREMENT-PLAN.md &sect;3 D3/&sect;4.2, wave L4) — a broadcast
     *                        only actually happens on a change, so this is an upper bound on
     *                        detection latency, not a broadcast cadence
     */
    public record Live(Duration coalesce, Duration heartbeat, int telemetryBuffer, int eventBuffer,
                        int detectionBuffer, int mapBuffer, Duration sendTimeout, Duration bufferEviction,
                        Duration systemSample) {

        public Live {
            requirePositive(coalesce, "coalesce");
            requirePositive(heartbeat, "heartbeat");
            requirePositive(telemetryBuffer, "telemetryBuffer");
            requirePositive(eventBuffer, "eventBuffer");
            requirePositive(detectionBuffer, "detectionBuffer");
            requirePositive(mapBuffer, "mapBuffer");
            requirePositive(sendTimeout, "sendTimeout");
            requirePositive(bufferEviction, "bufferEviction");
            requirePositive(systemSample, "systemSample");
        }

        public static Live defaults() {
            return new Live(Duration.ofMillis(150), Duration.ofSeconds(15), 50, 300, 300, 300,
                    Duration.ofSeconds(3), Duration.ofSeconds(60), Duration.ofSeconds(5));
        }
    }

    /**
     * Shared default/max page size for list endpoints that accept a {@code limit} query parameter
     * (e.g. {@code ActivityController}'s {@code GET /api/me/activity}).
     *
     * @param defaultLimit page size used when the caller omits {@code limit}
     * @param maxLimit     hard ceiling a larger requested {@code limit} is clamped to
     */
    public record Paging(int defaultLimit, int maxLimit) {

        public Paging {
            if (defaultLimit <= 0) {
                throw new IllegalArgumentException("defaultLimit must be positive, was " + defaultLimit);
            }
            if (maxLimit < defaultLimit) {
                throw new IllegalArgumentException("maxLimit must be >= defaultLimit");
            }
        }

        public static Paging defaults() {
            return new Paging(50, 500);
        }
    }

    /**
     * {@code AssetImageController}'s upload size cap (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3).
     *
     * @param maxImageBytes maximum accepted asset-image body size, bytes
     */
    public record Upload(int maxImageBytes) {

        public Upload {
            if (maxImageBytes <= 0) {
                throw new IllegalArgumentException("maxImageBytes must be positive, was " + maxImageBytes);
            }
        }

        public static Upload defaults() {
            return new Upload(2 * 1024 * 1024);
        }
    }

    private static void requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name + " must not be null");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
