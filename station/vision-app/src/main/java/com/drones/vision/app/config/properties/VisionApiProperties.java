package com.drones.vision.app.config.properties;

import com.drones.vision.api.ratelimit.RateLimitFilter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuration for {@code vision-api}'s edge-local infrastructure ({@code vision.api.*}),
 * docs/plans/active/LAYERING-REFACTOR-PLAN.md &sect;2.2, wave D.
 *
 * <p><b>Naming note</b>: this Spring {@code @ConfigurationProperties} record shares its simple name
 * with {@code com.drones.vision.api.support.VisionApiProperties} — {@code vision-api}'s own plain,
 * framework-free settings record (which that module self-constructs via {@code
 * VisionApiProperties.defaults()} as a stopgap wherever this bean isn't yet threaded through). They
 * are deliberately two distinct types in two distinct packages, never the same class: {@code
 * vision-api} may not depend on Spring's {@code @ConfigurationProperties} machinery (the dependency
 * rule runs the other way), so it keeps its own plain mirror; this record's only job is to bind
 * {@code application.yaml} and map onto an instance of that one. Always reference either type
 * by its fully-qualified name in a file that needs both (see {@code wiring.ApplicationServiceWiring}).
 *
 * @param snapshot {@code SnapshotJpegEncoder}'s downscale/encode tunables
 * @param hlsProxy {@code HlsProxyController}'s upstream HTTP client timeouts
 * @param live     the SSE data plane's coalescing/heartbeat cadence and per-topic ring-buffer capacities
 * @param paging   the shared default/max page size for list endpoints that accept a {@code limit}
 * @param upload   the asset-image upload size cap
 * @param rateLimit {@code RateLimitFilter}'s per-principal request budget (off by default)
 */
@ConfigurationProperties(prefix = "vision.api")
public record VisionApiProperties(Snapshot snapshot, HlsProxy hlsProxy, Live live, Paging paging, Upload upload,
                                   RateLimit rateLimit) {

    public VisionApiProperties {
        if (snapshot == null) {
            snapshot = new Snapshot(Snapshot.DEFAULT_MAX_WIDTH_INT, Snapshot.DEFAULT_JPEG_QUALITY_FLOAT);
        }
        if (hlsProxy == null) {
            hlsProxy = new HlsProxy(HlsProxy.DEFAULT_CONNECT_TIMEOUT_DURATION, HlsProxy.DEFAULT_REQUEST_TIMEOUT_DURATION,
                    HlsProxy.DEFAULT_ERROR_BODY_PREVIEW_MAX_CHARS_INT, HlsProxy.DEFAULT_MAX_REDIRECT_HOPS_INT);
        }
        if (live == null) {
            live = new Live(Live.DEFAULT_COALESCE_DURATION, Live.DEFAULT_HEARTBEAT_DURATION,
                    Live.DEFAULT_TELEMETRY_BUFFER_INT, Live.DEFAULT_EVENT_BUFFER_INT,
                    Live.DEFAULT_DETECTION_BUFFER_INT, Live.DEFAULT_MAP_BUFFER_INT,
                    Live.DEFAULT_SEND_TIMEOUT_DURATION, Live.DEFAULT_BUFFER_EVICTION_DURATION);
        }
        if (paging == null) {
            paging = new Paging(Paging.DEFAULT_DEFAULT_LIMIT_INT, Paging.DEFAULT_MAX_LIMIT_INT);
        }
        if (upload == null) {
            upload = new Upload(Upload.DEFAULT_MAX_IMAGE_BYTES_INT);
        }
        if (rateLimit == null) {
            rateLimit = new RateLimit(false, RateLimitFilter.DEFAULT_PERMITS_PER_MINUTE);
        }
    }

    /**
     * @param maxWidth    max width a snapshot is downscaled to; default {@value #DEFAULT_MAX_WIDTH}
     * @param jpegQuality JPEG encoder quality; default {@value #DEFAULT_JPEG_QUALITY}
     */
    public record Snapshot(@DefaultValue(Snapshot.DEFAULT_MAX_WIDTH) int maxWidth,
                            @DefaultValue(Snapshot.DEFAULT_JPEG_QUALITY) float jpegQuality) {
        static final String DEFAULT_MAX_WIDTH = "480";
        static final String DEFAULT_JPEG_QUALITY = "0.8";
        static final int DEFAULT_MAX_WIDTH_INT = 480;
        static final float DEFAULT_JPEG_QUALITY_FLOAT = 0.8f;
    }

    /**
     * @param connectTimeout           bound on establishing the upstream TCP connection; default 5s
     * @param requestTimeout           bound on the whole upstream request/response round trip; default 15s
     * @param errorBodyPreviewMaxChars how much of a non-2xx upstream error body to buffer for the
     *                                 diagnostic log line; default {@value #DEFAULT_ERROR_BODY_PREVIEW_MAX_CHARS}
     * @param maxRedirectHops          bound on the hand-followed redirect chain; default {@value #DEFAULT_MAX_REDIRECT_HOPS}
     */
    public record HlsProxy(@DefaultValue("5s") Duration connectTimeout, @DefaultValue("15s") Duration requestTimeout,
                            @DefaultValue(HlsProxy.DEFAULT_ERROR_BODY_PREVIEW_MAX_CHARS) int errorBodyPreviewMaxChars,
                            @DefaultValue(HlsProxy.DEFAULT_MAX_REDIRECT_HOPS) int maxRedirectHops) {
        static final Duration DEFAULT_CONNECT_TIMEOUT_DURATION = Duration.ofSeconds(5);
        static final Duration DEFAULT_REQUEST_TIMEOUT_DURATION = Duration.ofSeconds(15);
        static final String DEFAULT_ERROR_BODY_PREVIEW_MAX_CHARS = "200";
        static final String DEFAULT_MAX_REDIRECT_HOPS = "5";
        static final int DEFAULT_ERROR_BODY_PREVIEW_MAX_CHARS_INT = 200;
        static final int DEFAULT_MAX_REDIRECT_HOPS_INT = 5;
    }

    /**
     * @param coalesce        how often pending telemetry/detections/fleet-recompute are flushed per
     *                        topic; default 150ms
     * @param heartbeat       how often an idle SSE connection gets a heartbeat; default 15s
     * @param telemetryBuffer per-asset {@code telemetry} topic ring-buffer capacity; default {@value #DEFAULT_TELEMETRY_BUFFER}
     * @param eventBuffer     the {@code event} topic's ring-buffer capacity; default {@value #DEFAULT_EVENT_BUFFER}
     * @param detectionBuffer the {@code detection-events} topic's ring-buffer capacity; default {@value #DEFAULT_DETECTION_BUFFER}
     * @param mapBuffer       the {@code map} topic's ring-buffer capacity; default {@value #DEFAULT_MAP_BUFFER}
     * @param sendTimeout     how long a queued per-connection write may take before that connection
     *                        is unregistered as stalled/dead; default 3s
     * @param bufferEviction  how often per-asset {@code telemetry}/{@code detections} buffers with
     *                        no subscriber left are swept away; default 60s
     */
    public record Live(@DefaultValue("150ms") Duration coalesce, @DefaultValue("15s") Duration heartbeat,
                        @DefaultValue(Live.DEFAULT_TELEMETRY_BUFFER) int telemetryBuffer,
                        @DefaultValue(Live.DEFAULT_EVENT_BUFFER) int eventBuffer,
                        @DefaultValue(Live.DEFAULT_DETECTION_BUFFER) int detectionBuffer,
                        @DefaultValue(Live.DEFAULT_MAP_BUFFER) int mapBuffer,
                        @DefaultValue("3s") Duration sendTimeout,
                        @DefaultValue("60s") Duration bufferEviction) {
        static final String DEFAULT_TELEMETRY_BUFFER = "50";
        static final String DEFAULT_EVENT_BUFFER = "300";
        static final String DEFAULT_DETECTION_BUFFER = "300";
        static final String DEFAULT_MAP_BUFFER = "300";
        static final Duration DEFAULT_COALESCE_DURATION = Duration.ofMillis(150);
        static final Duration DEFAULT_HEARTBEAT_DURATION = Duration.ofSeconds(15);
        static final Duration DEFAULT_SEND_TIMEOUT_DURATION = Duration.ofSeconds(3);
        static final Duration DEFAULT_BUFFER_EVICTION_DURATION = Duration.ofSeconds(60);
        static final int DEFAULT_TELEMETRY_BUFFER_INT = 50;
        static final int DEFAULT_EVENT_BUFFER_INT = 300;
        static final int DEFAULT_DETECTION_BUFFER_INT = 300;
        static final int DEFAULT_MAP_BUFFER_INT = 300;
    }

    /**
     * @param defaultLimit page size used when the caller omits {@code limit}; default {@value #DEFAULT_DEFAULT_LIMIT}
     * @param maxLimit     hard ceiling a larger requested {@code limit} is clamped to; default {@value #DEFAULT_MAX_LIMIT}
     */
    public record Paging(@DefaultValue(Paging.DEFAULT_DEFAULT_LIMIT) int defaultLimit,
                          @DefaultValue(Paging.DEFAULT_MAX_LIMIT) int maxLimit) {
        static final String DEFAULT_DEFAULT_LIMIT = "50";
        static final String DEFAULT_MAX_LIMIT = "500";
        static final int DEFAULT_DEFAULT_LIMIT_INT = 50;
        static final int DEFAULT_MAX_LIMIT_INT = 500;
    }

    /**
     * {@code RateLimitFilter}'s budget (docs/plans/active/SCALE-100-PLAN.md S6) — a blast-radius
     * bound, not security: it stops one runaway client from degrading the JVM for everyone else.
     *
     * <p><strong>Off by default, and that is a real decision, not caution.</strong> The filter keys
     * buckets on {@code CurrentUser#userId()}, which with {@code vision.auth.enabled=false} is one
     * fixed dev principal for the entire deployment — so enabling it there would give <em>all</em>
     * callers combined a single {@code permits-per-minute} budget, and at this plan's own target of
     * 100 concurrent users (~1 req/s each) the limit would trip immediately on legitimate traffic.
     * Turn it on together with auth, where each real user gets their own bucket, and the limit
     * measures what it is meant to measure.
     *
     * @param enabled          whether the filter is registered at all
     * @param permitsPerMinute per-principal capacity and refill rate; default {@code 600} — roughly
     *                         ten ungated cockpit tabs' worth of polling for one principal
     */
    public record RateLimit(@DefaultValue("false") boolean enabled,
                             @DefaultValue("" + RateLimitFilter.DEFAULT_PERMITS_PER_MINUTE) int permitsPerMinute) {
    }

    /** @param maxImageBytes maximum accepted asset-image body size, bytes; default {@value #DEFAULT_MAX_IMAGE_BYTES} */
    public record Upload(@DefaultValue(Upload.DEFAULT_MAX_IMAGE_BYTES) int maxImageBytes) {
        static final String DEFAULT_MAX_IMAGE_BYTES = "2097152";
        static final int DEFAULT_MAX_IMAGE_BYTES_INT = 2 * 1024 * 1024;
    }
}
