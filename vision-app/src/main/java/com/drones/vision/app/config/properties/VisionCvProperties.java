package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuration for the gRPC connection to the Python CV service ({@code vision.cv.*}),
 * per docs/MVP1-PLAN.md §C7 bullet 4 and docs/REMOTE-CV-PLAN.md P1 item 5, extended by
 * docs/LAYERING-REFACTOR-PLAN.md §2.2 (wave F4) with every remaining {@code
 * com.drones.vision.adapter.cvgrpc.GrpcCvSettings}/{@code GrpcModelRegistryPort} tunable.
 *
 * <p>Selected by {@code wiring.CvWiring#detectionPort}: {@link #enabled()} {@code false}
 * (the default, today's behavior) keeps {@code DetectionPort} wired to the devsupport
 * {@code NoopDetectionPort}; {@code true} wires {@code
 * com.drones.vision.adapter.cvgrpc.GrpcDetectionPort} against {@link #endpoint()} and a {@code
 * GrpcCvSettings} built from {@link #responseTimeout()}/{@link #keepAliveTime()}/{@link
 * #keepAliveTimeout()}/{@link #keepAliveWithoutCalls()}/{@link #channelShutdownTimeout()}/{@link
 * #plaintext()}/{@link #detectWidth()}/{@link #jpegQuality()}/{@link Upload#timeout()}/{@link
 * Upload#chunkBytes()}. {@link Registry#callTimeout()} is deliberately <b>not</b> part of the
 * mapped {@code GrpcCvSettings} — it maps straight onto {@code GrpcModelRegistryPort}'s own
 * {@code (ManagedChannel, Duration)} constructor instead (that class takes no settings object).
 *
 * @param enabled                whether to wire {@code GrpcDetectionPort} instead of the no-op fallback;
 *                               default {@code false}
 * @param endpoint               {@code host:port} of the cv-service's {@code DetectStream} gRPC endpoint
 *                               (an optional {@code scheme://} prefix, e.g. {@code dns://}, is tolerated
 *                               and stripped); only read when {@link #enabled()} is {@code true}; default
 *                               {@value #DEFAULT_ENDPOINT}
 * @param detectWidth            widest a {@code BGR24} frame may be before {@code GrpcDetectionPort}
 *                               downscales+JPEG-encodes it before sending; must be {@code >= 64}; default
 *                               {@value #DEFAULT_DETECT_WIDTH}
 * @param jpegQuality            JPEG encoder quality {@code GrpcDetectionPort} uses for that same
 *                               downscale path; must be in {@code (0, 1]}; default
 *                               {@value #DEFAULT_JPEG_QUALITY}
 * @param responseTimeout        per-pending-future response timeout on the detection bidi stream; default 2s
 * @param keepAliveTime          HTTP/2 keepalive PING interval; default 20s
 * @param keepAliveTimeout       keepalive PING ack deadline; default 5s
 * @param keepAliveWithoutCalls  whether to send keepalive PINGs on an otherwise idle channel; default {@code true}
 * @param channelShutdownTimeout how long {@code GrpcDetectionPort#close()} awaits graceful channel
 *                               termination; default 5s
 * @param plaintext              whether the host/port channel skips TLS; default {@code true}
 * @param upload                 {@code GrpcDatasetUploadPort}'s per-call deadline/chunk-framing size;
 *                               defaulted as a whole when absent
 * @param registry               {@code GrpcModelRegistryPort}'s per-call deadline; defaulted as a whole
 *                               when absent
 */
@ConfigurationProperties(prefix = "vision.cv")
public record VisionCvProperties(@DefaultValue("false") boolean enabled,
                                  @DefaultValue(VisionCvProperties.DEFAULT_ENDPOINT) String endpoint,
                                  @DefaultValue(VisionCvProperties.DEFAULT_DETECT_WIDTH) int detectWidth,
                                  @DefaultValue(VisionCvProperties.DEFAULT_JPEG_QUALITY) float jpegQuality,
                                  @DefaultValue("2s") Duration responseTimeout,
                                  @DefaultValue("20s") Duration keepAliveTime,
                                  @DefaultValue("5s") Duration keepAliveTimeout,
                                  @DefaultValue("true") boolean keepAliveWithoutCalls,
                                  @DefaultValue("5s") Duration channelShutdownTimeout,
                                  @DefaultValue("true") boolean plaintext,
                                  Upload upload,
                                  Registry registry) {

    static final String DEFAULT_ENDPOINT = "localhost:50051";
    static final String DEFAULT_DETECT_WIDTH = "640";
    static final String DEFAULT_JPEG_QUALITY = "0.8";
    private static final int MIN_DETECT_WIDTH = 64;

    @ConstructorBinding
    public VisionCvProperties {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("vision.cv.endpoint must not be blank");
        }
        if (detectWidth < MIN_DETECT_WIDTH) {
            throw new IllegalArgumentException(
                    "vision.cv.detect-width must be >= " + MIN_DETECT_WIDTH + ", was " + detectWidth);
        }
        if (jpegQuality <= 0f || jpegQuality > 1f) {
            throw new IllegalArgumentException("vision.cv.jpeg-quality must be in (0,1], was " + jpegQuality);
        }
        if (upload == null) {
            upload = new Upload(Upload.DEFAULT_TIMEOUT_DURATION, Upload.DEFAULT_CHUNK_BYTES_INT);
        }
        if (registry == null) {
            registry = new Registry(Registry.DEFAULT_CALL_TIMEOUT_DURATION);
        }
    }

    /**
     * Convenience constructor covering just the four original {@code vision.cv.*} fields (docs/MVP1-PLAN.md
     * §C7/docs/REMOTE-CV-PLAN.md P1 item 5, predating wave F4's extension) — every field wave F4 added
     * defaults to {@code GrpcCvSettings}'s own literal, so behavior constructing an instance this way
     * is unchanged.
     */
    public VisionCvProperties(boolean enabled, String endpoint, int detectWidth, float jpegQuality) {
        this(enabled, endpoint, detectWidth, jpegQuality, Duration.ofSeconds(2), Duration.ofSeconds(20),
                Duration.ofSeconds(5), true, Duration.ofSeconds(5), true, null, null);
    }

    /**
     * @return the host component of {@link #endpoint()}, e.g. {@code localhost}
     */
    public String host() {
        return hostAndPort()[0];
    }

    /**
     * @return the port component of {@link #endpoint()}, e.g. {@code 50051}
     */
    public int port() {
        return Integer.parseInt(hostAndPort()[1]);
    }

    private String[] hostAndPort() {
        String value = endpoint;
        int schemeIdx = value.indexOf("://");
        if (schemeIdx >= 0) {
            value = value.substring(schemeIdx + 3);
        }
        int colonIdx = value.lastIndexOf(':');
        if (colonIdx <= 0 || colonIdx == value.length() - 1) {
            throw new IllegalArgumentException("vision.cv.endpoint must be host:port, got: " + endpoint);
        }
        return new String[] {value.substring(0, colonIdx), value.substring(colonIdx + 1)};
    }

    /**
     * @param timeout    per-call deadline covering dataset archive framing plus the whole upload
     *                   round trip; default 300s
     * @param chunkBytes target size of each streamed dataset zip chunk; default {@value #DEFAULT_CHUNK_BYTES}
     */
    public record Upload(@DefaultValue("300s") Duration timeout,
                          @DefaultValue(Upload.DEFAULT_CHUNK_BYTES) int chunkBytes) {
        static final String DEFAULT_CHUNK_BYTES = "262144";
        static final Duration DEFAULT_TIMEOUT_DURATION = Duration.ofSeconds(300);
        static final int DEFAULT_CHUNK_BYTES_INT = 262_144;
    }

    /**
     * @param callTimeout per-call deadline for {@code GrpcModelRegistryPort}'s {@code
     *                    ListModels}/{@code PromoteModel} RPCs; default 10s
     */
    public record Registry(@DefaultValue("10s") Duration callTimeout) {
        static final Duration DEFAULT_CALL_TIMEOUT_DURATION = Duration.ofSeconds(10);
    }
}
