package com.drones.vision.app.config.properties;

import com.drones.vision.kernel.FixedCameraGeoSettings;
import com.drones.vision.map.application.track.TrackProjectionSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuration for the fixed-camera geolocation feature ({@code vision.geo.fixed-camera.*}),
 * docs/plans/active/FIXED-CAMERA-GEO-PLAN.md §6 — every value there, none as a code constant
 * (CLAUDE.md rule 1). Bound by {@link com.drones.vision.app.config.wiring.FixedCameraGeoWiringConfiguration}.
 *
 * @param enabled                  the master gate (D8); default {@code false} — with it off, {@code
 *                                 TrackProjectionRunner} is never scheduled and every §5 endpoint
 *                                 answers {@code 409}
 * @param publishIntervalMillis    the runner's own tick cadence, milliseconds; the map needs ~1 Hz,
 *                                 not the tracker's 10 (D7); default {@value #DEFAULT_PUBLISH_INTERVAL_MILLIS}
 * @param minRayDepressionDegrees  D6's horizon guard, degrees; default {@value #DEFAULT_MIN_RAY_DEPRESSION_DEGREES}
 * @param maxRangeMeters           D6's range ceiling, meters; default {@value #DEFAULT_MAX_RANGE_METERS}
 * @param angularErrorDegrees      D6's assumed one-sigma angular error, degrees; default {@value #DEFAULT_ANGULAR_ERROR_DEGREES}
 * @param maxErrorRadiusMeters     D6's uncertainty ceiling, meters; default {@value #DEFAULT_MAX_ERROR_RADIUS_METERS}
 * @param trail                    trail decimation/retention — see {@link Trail}
 * @param calibration              the calibration solver's tolerance — see {@link Calibration}
 */
@ConfigurationProperties(prefix = "vision.geo.fixed-camera")
public record VisionGeoProperties(@DefaultValue("false") boolean enabled,
                                   @DefaultValue(DEFAULT_PUBLISH_INTERVAL_MILLIS) long publishIntervalMillis,
                                   @DefaultValue(DEFAULT_MIN_RAY_DEPRESSION_DEGREES) double minRayDepressionDegrees,
                                   @DefaultValue(DEFAULT_MAX_RANGE_METERS) double maxRangeMeters,
                                   @DefaultValue(DEFAULT_ANGULAR_ERROR_DEGREES) double angularErrorDegrees,
                                   @DefaultValue(DEFAULT_MAX_ERROR_RADIUS_METERS) double maxErrorRadiusMeters,
                                   @DefaultValue Trail trail, @DefaultValue Calibration calibration) {

    static final String DEFAULT_PUBLISH_INTERVAL_MILLIS = "1000";
    static final String DEFAULT_MIN_RAY_DEPRESSION_DEGREES = "1.0";
    static final String DEFAULT_MAX_RANGE_METERS = "500.0";
    static final String DEFAULT_ANGULAR_ERROR_DEGREES = "0.5";
    static final String DEFAULT_MAX_ERROR_RADIUS_METERS = "100.0";

    public VisionGeoProperties {
        if (publishIntervalMillis <= 0) {
            throw new IllegalArgumentException(
                    "vision.geo.fixed-camera.publish-interval-millis must be positive: " + publishIntervalMillis);
        }
    }

    /**
     * D7's decimation/cap/retention.
     *
     * @param minDistanceMeters  a trail point is appended only after this much movement from the
     *                           last stored point; default {@value #DEFAULT_MIN_DISTANCE_METERS}
     * @param maxPointsPerTrack  the per-track stored-point cap; default {@value #DEFAULT_MAX_POINTS_PER_TRACK}
     * @param retention          rows older than this are pruned by the runner on its own cadence;
     *                           default {@value #DEFAULT_RETENTION}
     */
    public record Trail(@DefaultValue(DEFAULT_MIN_DISTANCE_METERS) double minDistanceMeters,
                         @DefaultValue(DEFAULT_MAX_POINTS_PER_TRACK) int maxPointsPerTrack,
                         @DefaultValue(DEFAULT_RETENTION) Duration retention) {

        static final String DEFAULT_MIN_DISTANCE_METERS = "2.0";
        static final String DEFAULT_MAX_POINTS_PER_TRACK = "600";
        static final String DEFAULT_RETENTION = "PT30M";

        public Trail {
            if (retention == null || retention.isNegative() || retention.isZero()) {
                throw new IllegalArgumentException(
                        "vision.geo.fixed-camera.trail.retention must be positive: " + retention);
            }
        }
    }

    /**
     * @param maxRmsErrorPixels above this residual the solve refuses (D5); default {@value #DEFAULT_MAX_RMS_ERROR_PIXELS}
     */
    public record Calibration(@DefaultValue(DEFAULT_MAX_RMS_ERROR_PIXELS) double maxRmsErrorPixels) {

        static final String DEFAULT_MAX_RMS_ERROR_PIXELS = "25.0";
    }

    /**
     * The D6 refusal thresholds {@code FixedCameraGeo.project} takes directly.
     *
     * @return the equivalent {@link FixedCameraGeoSettings}
     */
    public FixedCameraGeoSettings toGeoSettings() {
        return new FixedCameraGeoSettings(minRayDepressionDegrees, maxRangeMeters, angularErrorDegrees,
                maxErrorRadiusMeters);
    }

    /**
     * The bundle {@code DefaultTrackProjectionService}'s constructor takes.
     *
     * @return the equivalent {@link TrackProjectionSettings}
     */
    public TrackProjectionSettings toTrackProjectionSettings() {
        return new TrackProjectionSettings(toGeoSettings(), trail.minDistanceMeters(), trail.maxPointsPerTrack());
    }
}
