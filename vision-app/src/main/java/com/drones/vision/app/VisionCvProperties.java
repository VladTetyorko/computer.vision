package com.drones.vision.app;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the gRPC connection to the Python CV service ({@code vision.cv.*}),
 * per docs/MVP1-PLAN.md §C7 bullet 4 and docs/REMOTE-CV-PLAN.md P1 item 5.
 *
 * <p>Selected by {@code WiringConfiguration#detectionPort}: {@link #enabled()} {@code false}
 * (the default, today's behavior) keeps {@code DetectionPort} wired to the devsupport
 * {@code NoopDetectionPort}; {@code true} wires {@code
 * com.drones.vision.adapter.cvgrpc.GrpcDetectionPort} against {@link #endpoint()}, {@link
 * #detectWidth()} and {@link #jpegQuality()} instead.
 *
 * @param enabled      whether to wire {@code GrpcDetectionPort} instead of the no-op fallback;
 *                     default {@code false}
 * @param endpoint     {@code host:port} of the cv-service's {@code DetectStream} gRPC endpoint
 *                     (an optional {@code scheme://} prefix, e.g. {@code dns://}, is tolerated
 *                     and stripped); only read when {@link #enabled()} is {@code true}; default
 *                     {@value #DEFAULT_ENDPOINT}
 * @param detectWidth  widest a {@code BGR24} frame may be before {@code GrpcDetectionPort}
 *                     downscales+JPEG-encodes it before sending (see adapter-cv-grpc/MODULE.md's
 *                     "Payload shrinking" section); lower this over a slow/metered link (e.g.
 *                     480 over VPN); must be {@code >= 64}; default {@value #DEFAULT_DETECT_WIDTH}
 * @param jpegQuality  JPEG encoder quality {@code GrpcDetectionPort} uses for that same
 *                     downscale path; must be in {@code (0, 1]}; default
 *                     {@value #DEFAULT_JPEG_QUALITY}
 */
@ConfigurationProperties(prefix = "vision.cv")
public record VisionCvProperties(@DefaultValue("false") boolean enabled,
                                  @DefaultValue(VisionCvProperties.DEFAULT_ENDPOINT) String endpoint,
                                  @DefaultValue(VisionCvProperties.DEFAULT_DETECT_WIDTH) int detectWidth,
                                  @DefaultValue(VisionCvProperties.DEFAULT_JPEG_QUALITY) float jpegQuality) {

    static final String DEFAULT_ENDPOINT = "localhost:50051";
    static final String DEFAULT_DETECT_WIDTH = "640";
    static final String DEFAULT_JPEG_QUALITY = "0.8";
    private static final int MIN_DETECT_WIDTH = 64;

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
}
