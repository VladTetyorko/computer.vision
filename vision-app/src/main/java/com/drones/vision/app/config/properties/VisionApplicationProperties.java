package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import com.drones.vision.application.pipeline.AdaptiveRateSettings;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for {@code vision-application}'s own tunables ({@code vision.application.*}),
 * docs/plans/active/LAYERING-REFACTOR-PLAN.md &sect;1.3/&sect;2.2 (Wave A). Mirrors {@link
 * VisionPublishProperties}'s record-plus-nested-record-plus-{@code @DefaultValue} idiom, one nested
 * record per feature package's plain settings record ({@code
 * com.drones.vision.application.replay.ReplayServiceSettings}, {@code
 * com.drones.vision.application.pipeline.StreamPipelineSettings}, {@code
 * com.drones.vision.application.simulation.SimulationServiceSettings}) or per bare tunable
 * otherwise. {@code wiring.ApplicationServiceWiring}/{@code wiring.DiscoveryWiring}/{@code
 * wiring.CvWiring} map this record's fields onto the plain settings objects/constructor
 * parameters those services take — {@code vision-application} itself never imports this type
 * (framework-free, ArchUnit-enforced).
 *
 * <p>Every {@code @DefaultValue} below is byte-identical to the literal it replaces; see each
 * nested record's own javadoc for which class's magic value it extracted.
 *
 * @param discoveryGraceMs small fixed allowance (milliseconds) added to a discovery scan's
 *                         requested timeout when bounding the overall wait ({@code
 *                         DefaultDiscoveryService#GRACE_PERIOD}); must not be negative; default
 *                         {@value #DEFAULT_DISCOVERY_GRACE_MS}
 * @param replay           {@code DefaultReplayService}'s downsampling/fetch-bound tunables;
 *                         defaulted as a whole when absent
 * @param stats            {@code DefaultAssetStatsService}'s fetch bound; defaulted as a whole when absent
 * @param fleet            {@code DefaultFleetSummaryService}'s per-asset/open-events caps; defaulted
 *                         as a whole when absent
 * @param training         {@code DefaultTrainingJobService}'s retention cap and {@code
 *                         DefaultLabelingService}'s capture JPEG quality; defaulted as a whole when absent
 * @param pipeline         {@code StreamPipeline}'s frame-cadence/backoff tunables, plus the source
 *                         reopen backoff {@code DefaultStreamService} wraps a video source in;
 *                         defaulted as a whole when absent
 * @param extrapolation    {@code DetectionExtrapolator}'s smoothing tunables; defaulted as a whole when absent
 * @param simulation       {@code DefaultSimulationService}'s MAVLink-transport fallback defaults;
 *                         defaulted as a whole when absent
 */
@ConfigurationProperties(prefix = "vision.application")
public record VisionApplicationProperties(
        @DefaultValue(VisionApplicationProperties.DEFAULT_DISCOVERY_GRACE_MS) long discoveryGraceMs,
        Replay replay,
        Stats stats,
        Fleet fleet,
        Training training,
        Pipeline pipeline,
        Extrapolation extrapolation,
        Simulation simulation) {

    static final String DEFAULT_DISCOVERY_GRACE_MS = "200";

    public VisionApplicationProperties {
        if (discoveryGraceMs < 0) {
            throw new IllegalArgumentException(
                    "vision.application.discovery-grace-ms must not be negative, was " + discoveryGraceMs);
        }
        if (replay == null) {
            replay = new Replay(Replay.DEFAULT_MAX_POINTS_INT, Replay.DEFAULT_MAX_POINTS_CEILING_INT,
                    Replay.DEFAULT_FETCH_LIMIT_INT);
        }
        if (stats == null) {
            stats = new Stats(Stats.DEFAULT_FETCH_LIMIT_INT);
        }
        if (fleet == null) {
            fleet = new Fleet(Fleet.DEFAULT_MAX_ASSETS_INT, Fleet.DEFAULT_OPEN_EVENTS_SCAN_LIMIT_INT);
        }
        if (training == null) {
            training = new Training(Training.DEFAULT_MAX_FINISHED_JOBS_INT, Training.DEFAULT_JPEG_QUALITY_FLOAT);
        }
        if (pipeline == null) {
            pipeline = new Pipeline(Pipeline.DEFAULT_ASSUMED_SOURCE_FPS_INT,
                    Pipeline.DEFAULT_MEASURED_FPS_EWMA_ALPHA_DOUBLE, Pipeline.DEFAULT_WARMUP_FRAMES_INT,
                    Pipeline.DEFAULT_MIN_MEASURED_FPS_DOUBLE, Pipeline.DEFAULT_MAX_MEASURED_FPS_DOUBLE, null, null,
                    Pipeline.DEFAULT_CAMERA_HFOV_DEGREES_DOUBLE, null);
        }
        if (extrapolation == null) {
            extrapolation = new Extrapolation(Extrapolation.DEFAULT_MAX_MILLIS_LONG,
                    Extrapolation.DEFAULT_MATCH_GATE_DOUBLE);
        }
        if (simulation == null) {
            simulation = new Simulation(Simulation.DEFAULT_MAVLINK_LOOPBACK_HOST_STRING,
                    Simulation.DEFAULT_FALLBACK_LATITUDE_DOUBLE, Simulation.DEFAULT_FALLBACK_LONGITUDE_DOUBLE);
        }
    }

    /**
     * @param defaultMaxPoints default cap the API layer falls back to when the caller omits
     *                         {@code maxPoints}; default {@value #DEFAULT_MAX_POINTS}
     * @param maxPointsCeiling hard ceiling {@code maxPoints} clamps to; default {@value #DEFAULT_MAX_POINTS_CEILING}
     * @param fetchLimit       best-effort telemetry/detection fetch bound; default {@value #DEFAULT_FETCH_LIMIT}
     */
    public record Replay(@DefaultValue(Replay.DEFAULT_MAX_POINTS) int defaultMaxPoints,
                          @DefaultValue(Replay.DEFAULT_MAX_POINTS_CEILING) int maxPointsCeiling,
                          @DefaultValue(Replay.DEFAULT_FETCH_LIMIT) int fetchLimit) {
        static final String DEFAULT_MAX_POINTS = "500";
        static final String DEFAULT_MAX_POINTS_CEILING = "2000";
        static final String DEFAULT_FETCH_LIMIT = "20000";
        static final int DEFAULT_MAX_POINTS_INT = 500;
        static final int DEFAULT_MAX_POINTS_CEILING_INT = 2000;
        static final int DEFAULT_FETCH_LIMIT_INT = 20_000;
    }

    /** @param fetchLimit best-effort flight-history fetch bound; default {@value #DEFAULT_FETCH_LIMIT} */
    public record Stats(@DefaultValue(Stats.DEFAULT_FETCH_LIMIT) int fetchLimit) {
        static final String DEFAULT_FETCH_LIMIT = "10000";
        static final int DEFAULT_FETCH_LIMIT_INT = 10_000;
    }

    /**
     * @param maxAssets            hard cap on the per-asset list; default {@value #DEFAULT_MAX_ASSETS}
     * @param openEventsScanLimit  how many recent detection events are scanned to count each
     *                             asset's open ones; default {@value #DEFAULT_OPEN_EVENTS_SCAN_LIMIT}
     */
    public record Fleet(@DefaultValue(Fleet.DEFAULT_MAX_ASSETS) int maxAssets,
                         @DefaultValue(Fleet.DEFAULT_OPEN_EVENTS_SCAN_LIMIT) int openEventsScanLimit) {
        static final String DEFAULT_MAX_ASSETS = "500";
        static final String DEFAULT_OPEN_EVENTS_SCAN_LIMIT = "2000";
        static final int DEFAULT_MAX_ASSETS_INT = 500;
        static final int DEFAULT_OPEN_EVENTS_SCAN_LIMIT_INT = 2000;
    }

    /**
     * @param maxFinishedJobs finished training jobs kept before the oldest is evicted; default
     *                        {@value #DEFAULT_MAX_FINISHED_JOBS}
     * @param jpegQuality     JPEG encoder quality for a captured training frame; default
     *                        {@value #DEFAULT_JPEG_QUALITY}
     */
    public record Training(@DefaultValue(Training.DEFAULT_MAX_FINISHED_JOBS) int maxFinishedJobs,
                            @DefaultValue(Training.DEFAULT_JPEG_QUALITY) float jpegQuality) {
        static final String DEFAULT_MAX_FINISHED_JOBS = "50";
        static final String DEFAULT_JPEG_QUALITY = "0.9";
        static final int DEFAULT_MAX_FINISHED_JOBS_INT = 50;
        static final float DEFAULT_JPEG_QUALITY_FLOAT = 0.9f;
    }

    /**
     * @param assumedSourceFps      assumed source frame rate before the measured rate is trusted;
     *                              default {@value #DEFAULT_ASSUMED_SOURCE_FPS}
     * @param measuredFpsEwmaAlpha  smoothing factor for the source frame-rate EWMA; default
     *                              {@value #DEFAULT_MEASURED_FPS_EWMA_ALPHA}
     * @param warmupFrames          frame arrivals observed before the measured rate replaces the
     *                              assumed one; default {@value #DEFAULT_WARMUP_FRAMES}
     * @param minMeasuredFps        lower sanity-clamp bound; default {@value #DEFAULT_MIN_MEASURED_FPS}
     * @param maxMeasuredFps        upper sanity-clamp bound; default {@value #DEFAULT_MAX_MEASURED_FPS}
     * @param detectionBackoff      backoff bounds for {@code StreamPipeline}'s own detection-outage
     *                              retry probe (1s&ndash;10s default); defaulted as a whole when absent
     * @param sourceReopenBackoff   backoff bounds {@code DefaultStreamService} uses when wrapping a
     *                              video source in a {@code SupervisedPublisher} (1s&ndash;30s
     *                              default, deliberately different from {@code detectionBackoff} —
     *                              docs/plans/active/LAYERING-REFACTOR-PLAN.md &sect;2.3); defaulted as a whole when absent
     * @param cameraHfovDegrees     the camera's horizontal field of view, which is what lets a
     *                              telemetry attitude delta become a pixel shift for cv-service's
     *                              {@code pose} ego-motion compensator
     *                              (docs/conclusions/CV-RATE-BUDGET.md &sect;2). Default
     *                              {@value #DEFAULT_CAMERA_HFOV_DEGREES} = unknown, which leaves
     *                              pose compensation off rather than guessing a scale
     * @param adaptiveRate          whether and how far the sample rate may rise above the
     *                              operator's {@code inferenceFps} when a tracked target is about
     *                              to leave its association budget
     *                              (docs/plans/active/CV-RATE-CONTROL-PLAN.md wave R2); defaulted as a
     *                              whole when absent
     */
    public record Pipeline(@DefaultValue(Pipeline.DEFAULT_ASSUMED_SOURCE_FPS) int assumedSourceFps,
                            @DefaultValue(Pipeline.DEFAULT_MEASURED_FPS_EWMA_ALPHA) double measuredFpsEwmaAlpha,
                            @DefaultValue(Pipeline.DEFAULT_WARMUP_FRAMES) int warmupFrames,
                            @DefaultValue(Pipeline.DEFAULT_MIN_MEASURED_FPS) double minMeasuredFps,
                            @DefaultValue(Pipeline.DEFAULT_MAX_MEASURED_FPS) double maxMeasuredFps,
                            Backoff detectionBackoff,
                            Backoff sourceReopenBackoff,
                            @DefaultValue(Pipeline.DEFAULT_CAMERA_HFOV_DEGREES) double cameraHfovDegrees,
                            AdaptiveRate adaptiveRate) {
        static final String DEFAULT_ASSUMED_SOURCE_FPS = "30";
        static final String DEFAULT_CAMERA_HFOV_DEGREES = "0.0";
        static final double DEFAULT_CAMERA_HFOV_DEGREES_DOUBLE = 0.0;
        static final String DEFAULT_MEASURED_FPS_EWMA_ALPHA = "0.2";
        static final String DEFAULT_WARMUP_FRAMES = "5";
        static final String DEFAULT_MIN_MEASURED_FPS = "1.0";
        static final String DEFAULT_MAX_MEASURED_FPS = "240.0";
        static final int DEFAULT_ASSUMED_SOURCE_FPS_INT = 30;
        static final double DEFAULT_MEASURED_FPS_EWMA_ALPHA_DOUBLE = 0.2;
        static final int DEFAULT_WARMUP_FRAMES_INT = 5;
        static final double DEFAULT_MIN_MEASURED_FPS_DOUBLE = 1.0;
        static final double DEFAULT_MAX_MEASURED_FPS_DOUBLE = 240.0;

        public Pipeline {
            if (detectionBackoff == null) {
                detectionBackoff = new Backoff(Backoff.DEFAULT_DETECTION_INITIAL_MS_INT,
                        Backoff.DEFAULT_DETECTION_MAX_MS_INT);
            }
            if (sourceReopenBackoff == null) {
                sourceReopenBackoff = new Backoff(Backoff.DEFAULT_SOURCE_REOPEN_INITIAL_MS_INT,
                        Backoff.DEFAULT_SOURCE_REOPEN_MAX_MS_INT);
            }
            if (adaptiveRate == null) {
                adaptiveRate = new AdaptiveRate(AdaptiveRateSettings.DEFAULT_ENABLED,
                        AdaptiveRateSettings.DEFAULT_MAX_FPS, AdaptiveRateSettings.DEFAULT_EWMA_ALPHA);
            }
        }

        /**
         * The adaptive-rate loop's three knobs, mapped straight onto {@code AdaptiveRateSettings}.
         *
         * @param enabled  whether the demand may raise the rate at all; it can only ever raise it
         *                 above the configured {@code inferenceFps}, never lower it
         * @param maxFps   the deployment's ceiling — a bandwidth budget, not a promise: the rate is
         *                 also bounded at runtime by the source rate and by measured detector
         *                 capacity
         * @param ewmaAlpha smoothing on the computed demand, in {@code (0,1]}
         */
        public record AdaptiveRate(@DefaultValue("true") boolean enabled,
                                    @DefaultValue("30.0") double maxFps,
                                    @DefaultValue("0.2") double ewmaAlpha) {
        }

        /**
         * One backoff pair (milliseconds); {@code wiring.ApplicationServiceWiring} converts to
         * nanoseconds when building {@code StreamPipelineSettings}. The two nested-record instances
         * above use different {@code @DefaultValue}s ({@link #detectionBackoff()}'s 1s/10s vs {@link
         * #sourceReopenBackoff()}'s 1s/30s) despite sharing this one record shape.
         */
        public record Backoff(@DefaultValue("1000") int initialMs, @DefaultValue("10000") int maxMs) {
            static final int DEFAULT_DETECTION_INITIAL_MS_INT = 1_000;
            static final int DEFAULT_DETECTION_MAX_MS_INT = 10_000;
            static final int DEFAULT_SOURCE_REOPEN_INITIAL_MS_INT = 1_000;
            static final int DEFAULT_SOURCE_REOPEN_MAX_MS_INT = 30_000;
        }
    }

    /**
     * @param maxMillis how far past the latest completed result's capture time {@code
     *                  DetectionExtrapolator} extrapolates before the output freezes; default
     *                  {@value #DEFAULT_MAX_MILLIS}
     * @param matchGate max normalized box-center distance for a same-label match; default
     *                  {@value #DEFAULT_MATCH_GATE}
     */
    public record Extrapolation(@DefaultValue(Extrapolation.DEFAULT_MAX_MILLIS) long maxMillis,
                                 @DefaultValue(Extrapolation.DEFAULT_MATCH_GATE) double matchGate) {
        static final String DEFAULT_MAX_MILLIS = "800";
        static final String DEFAULT_MATCH_GATE = "0.15";
        static final long DEFAULT_MAX_MILLIS_LONG = 800L;
        static final double DEFAULT_MATCH_GATE_DOUBLE = 0.15;
    }

    /**
     * @param mavlinkLoopbackHost loopback host every MAVLink-transport simulation's telemetry feed
     *                            binds/pushes to; default {@value #DEFAULT_MAVLINK_LOOPBACK_HOST}
     * @param fallbackLatitude    default circular-track center latitude; default {@value #DEFAULT_FALLBACK_LATITUDE}
     * @param fallbackLongitude   default circular-track center longitude; default {@value #DEFAULT_FALLBACK_LONGITUDE}
     */
    public record Simulation(@DefaultValue(Simulation.DEFAULT_MAVLINK_LOOPBACK_HOST) String mavlinkLoopbackHost,
                              @DefaultValue(Simulation.DEFAULT_FALLBACK_LATITUDE) double fallbackLatitude,
                              @DefaultValue(Simulation.DEFAULT_FALLBACK_LONGITUDE) double fallbackLongitude) {
        static final String DEFAULT_MAVLINK_LOOPBACK_HOST = "127.0.0.1";
        static final String DEFAULT_FALLBACK_LATITUDE = "50.45";
        static final String DEFAULT_FALLBACK_LONGITUDE = "30.52";
        static final String DEFAULT_MAVLINK_LOOPBACK_HOST_STRING = "127.0.0.1";
        static final double DEFAULT_FALLBACK_LATITUDE_DOUBLE = 50.45;
        static final double DEFAULT_FALLBACK_LONGITUDE_DOUBLE = 30.52;
    }
}
