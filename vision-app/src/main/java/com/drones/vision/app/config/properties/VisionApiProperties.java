package com.drones.vision.app.config.properties;

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
 * {@code application.properties} and map onto an instance of that one. Always reference either type
 * by its fully-qualified name in a file that needs both (see {@code wiring.ApplicationServiceWiring}).
 *
 * @param snapshot {@code SnapshotJpegEncoder}'s downscale/encode tunables
 * @param hlsProxy {@code HlsProxyController}'s upstream HTTP client timeouts
 * @param live     the SSE data plane's coalescing/heartbeat cadence and per-topic ring-buffer capacities
 * @param paging   the shared default/max page size for list endpoints that accept a {@code limit}
 * @param upload   the asset-image upload size cap
 */
@ConfigurationProperties(prefix = "vision.api")
public record VisionApiProperties(Snapshot snapshot, HlsProxy hlsProxy, Live live, Paging paging, Upload upload) {

    public VisionApiProperties {
        if (snapshot == null) {
            snapshot = new Snapshot(Snapshot.DEFAULT_MAX_WIDTH_INT, Snapshot.DEFAULT_JPEG_QUALITY_FLOAT);
        }
        if (hlsProxy == null) {
            hlsProxy = new HlsProxy(HlsProxy.DEFAULT_CONNECT_TIMEOUT_DURATION, HlsProxy.DEFAULT_REQUEST_TIMEOUT_DURATION);
        }
        if (live == null) {
            live = new Live(Live.DEFAULT_COALESCE_DURATION, Live.DEFAULT_HEARTBEAT_DURATION,
                    Live.DEFAULT_TELEMETRY_BUFFER_INT, Live.DEFAULT_EVENT_BUFFER_INT,
                    Live.DEFAULT_DETECTION_BUFFER_INT, Live.DEFAULT_MARKS_BUFFER_INT);
        }
        if (paging == null) {
            paging = new Paging(Paging.DEFAULT_DEFAULT_LIMIT_INT, Paging.DEFAULT_MAX_LIMIT_INT);
        }
        if (upload == null) {
            upload = new Upload(Upload.DEFAULT_MAX_IMAGE_BYTES_INT);
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
     * @param connectTimeout bound on establishing the upstream TCP connection; default 5s
     * @param requestTimeout bound on the whole upstream request/response round trip; default 15s
     */
    public record HlsProxy(@DefaultValue("5s") Duration connectTimeout, @DefaultValue("15s") Duration requestTimeout) {
        static final Duration DEFAULT_CONNECT_TIMEOUT_DURATION = Duration.ofSeconds(5);
        static final Duration DEFAULT_REQUEST_TIMEOUT_DURATION = Duration.ofSeconds(15);
    }

    /**
     * @param coalesce        how often pending telemetry/detections are flushed per topic; default 150ms
     * @param heartbeat       how often an idle SSE connection gets a heartbeat; default 15s
     * @param telemetryBuffer per-asset {@code telemetry} topic ring-buffer capacity; default {@value #DEFAULT_TELEMETRY_BUFFER}
     * @param eventBuffer     the {@code event} topic's ring-buffer capacity; default {@value #DEFAULT_EVENT_BUFFER}
     * @param detectionBuffer the {@code detection-events} topic's ring-buffer capacity; default {@value #DEFAULT_DETECTION_BUFFER}
     * @param marksBuffer     the {@code marks} topic's ring-buffer capacity; default {@value #DEFAULT_MARKS_BUFFER}
     */
    public record Live(@DefaultValue("150ms") Duration coalesce, @DefaultValue("15s") Duration heartbeat,
                        @DefaultValue(Live.DEFAULT_TELEMETRY_BUFFER) int telemetryBuffer,
                        @DefaultValue(Live.DEFAULT_EVENT_BUFFER) int eventBuffer,
                        @DefaultValue(Live.DEFAULT_DETECTION_BUFFER) int detectionBuffer,
                        @DefaultValue(Live.DEFAULT_MARKS_BUFFER) int marksBuffer) {
        static final String DEFAULT_TELEMETRY_BUFFER = "50";
        static final String DEFAULT_EVENT_BUFFER = "300";
        static final String DEFAULT_DETECTION_BUFFER = "300";
        static final String DEFAULT_MARKS_BUFFER = "300";
        static final Duration DEFAULT_COALESCE_DURATION = Duration.ofMillis(150);
        static final Duration DEFAULT_HEARTBEAT_DURATION = Duration.ofSeconds(15);
        static final int DEFAULT_TELEMETRY_BUFFER_INT = 50;
        static final int DEFAULT_EVENT_BUFFER_INT = 300;
        static final int DEFAULT_DETECTION_BUFFER_INT = 300;
        static final int DEFAULT_MARKS_BUFFER_INT = 300;
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

    /** @param maxImageBytes maximum accepted asset-image body size, bytes; default {@value #DEFAULT_MAX_IMAGE_BYTES} */
    public record Upload(@DefaultValue(Upload.DEFAULT_MAX_IMAGE_BYTES) int maxImageBytes) {
        static final String DEFAULT_MAX_IMAGE_BYTES = "2097152";
        static final int DEFAULT_MAX_IMAGE_BYTES_INT = 2 * 1024 * 1024;
    }
}
