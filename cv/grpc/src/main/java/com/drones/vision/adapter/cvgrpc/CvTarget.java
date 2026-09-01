package com.drones.vision.adapter.cvgrpc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One {@code host:port} cv-service endpoint (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R6) —
 * the unit {@link CvChannels} builds a {@link io.grpc.ManagedChannel} from, either singly (the
 * training/geolocation channel) or as an ordered list (the inference channel's failover targets).
 * Deliberately not part of {@link GrpcCvSettings}: that record is pure protocol/transport tunables,
 * unchanged whichever endpoint(s) they apply to, exactly as host/port were already a separate
 * constructor argument from {@code GrpcCvSettings} before this type existed (see {@code
 * GrpcDetectionPort(String, int, GrpcCvSettings)}).
 *
 * @param host DNS name or IP literal; must not be blank
 * @param port TCP port; must be in {@code (0, 65535]}
 */
public record CvTarget(String host, int port) {

    public CvTarget {
        Objects.requireNonNull(host, "host must not be null");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port must be in (0,65535], was " + port);
        }
    }

    /**
     * Parses {@code host:port}, tolerating an optional {@code scheme://} prefix (stripped) — the same
     * lenient shape {@code vision-app}'s {@code VisionCvProperties#endpoint()} already accepts for the
     * single-endpoint case, so a caller can reuse one parsing convention for both.
     *
     * @throws IllegalArgumentException if {@code value} is not {@code [scheme://]host:port}
     */
    public static CvTarget parse(String value) {
        Objects.requireNonNull(value, "value must not be null");
        String stripped = value.strip();
        int schemeIdx = stripped.indexOf("://");
        if (schemeIdx >= 0) {
            stripped = stripped.substring(schemeIdx + 3);
        }
        int colonIdx = stripped.lastIndexOf(':');
        if (colonIdx <= 0 || colonIdx == stripped.length() - 1) {
            throw new IllegalArgumentException("target must be host:port, was: " + value);
        }
        String host = stripped.substring(0, colonIdx);
        String portText = stripped.substring(colonIdx + 1);
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("target port must be numeric, was: " + value, e);
        }
        return new CvTarget(host, port);
    }

    /** {@link #parse(String)} over a list, preserving order — the shape {@code vision.cv.inference.targets} arrives in. */
    public static List<CvTarget> parseAll(List<String> values) {
        Objects.requireNonNull(values, "values must not be null");
        List<CvTarget> targets = new ArrayList<>(values.size());
        for (String value : values) {
            targets.add(parse(value));
        }
        return targets;
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}
