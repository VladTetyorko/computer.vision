package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Deployment defaults for the tracking engine ({@code vision.tracking.*}), docs/plans/done/TRACKING-PLAN.md
 * &sect;4 / docs/extracts/TRACKING-ORCHESTRATION.md &sect;4.1 and &sect;4.3's knob inventory. Mirrors {@link
 * VisionCvProperties}'s record-plus-{@code @DefaultValue} idiom.
 *
 * <h2>What "deployment default" means here, precisely</h2>
 * Configuration is a layer, and precedence runs strictly <b>per-stream request &gt; deployment env
 * &gt; code default</b> (docs/extracts/TRACKING-ORCHESTRATION.md &sect;4.1). This record is the middle layer,
 * and it has two disjoint jobs:
 *
 * <ul>
 *   <li>{@link #defaultMode()}/{@link #followFps()}/{@link #verifyEveryMillis()}/{@link
 *       #capabilityLevel()}/{@link #reupdateMaxGapMillis()} <b>seed new streams only</b>. {@code
 *       TrackingWiring#streamStartTrackingSeed} turns them into the {@code TrackingConfigPatch} that
 *       rides {@code StreamPipelineSettings} into {@code DefaultStreamService}, which folds it at
 *       every stream start — device, asset, simulation and demo fleet alike, so the default cannot
 *       depend on which button was pressed. <b>They never reach into a running stream</b> — a
 *       running stream's tracking configuration is its own state, changed only by {@code PATCH
 *       /api/streams/{id}/config}; restarting the stream is how a changed deployment default is
 *       picked up, and that is the honest behavior rather than a config edit silently re-steering a
 *       flight in progress.</li>
 *   <li>{@link #statsWindowSeconds()}/{@link #trackRetentionSeconds()} configure the per-stream
 *       <b>read models</b> ({@code TrackingStatsWindow}/{@code TrackBook}, vision-application) via
 *       {@code StreamPipelineSettings}, so they apply to every stream this instance starts from then
 *       on.</li>
 * </ul>
 *
 * <p>{@code vision-domain} keeps pure literals and knows nothing about any of this — {@code
 * TrackingConfig.off()}/{@code .defaults()} stay constants in a framework-free module, which is
 * exactly why the deployment layer lives here (docs/extracts/TRACKING-ORCHESTRATION.md &sect;4.1).
 *
 * <p><b>{@link #defaultMode()} is {@code ASSOCIATE}</b> — the same value {@code
 * PipelineConfig.defaults()} carries as of docs/plans/done/TRACKING-PLAN.md wave T8, so a deployment that sets
 * nothing starts every stream tracking, and every detection it produces carries a stable {@code
 * trackId}. <b>This default has to move with the domain's, not lag it.</b> The seed is
 * unconditional — {@link
 * com.drones.vision.app.config.wiring.TrackingWiring#streamStartTrackingSeed} always states a mode
 * — so it is folded <i>over</i> {@code PipelineConfig.defaults()} at every start, and leaving it
 * {@code OFF} here would have silently made T8's domain flip inert for every real stream while the
 * domain test that proves the flip stayed green. A deployment that wants the old behavior pins
 * {@code vision.tracking.default-mode=OFF}, which is the honest place for that decision to live.
 *
 * <p>The remaining wire knobs of &sect;4.A — {@code redetectIouPercent}, {@code maxAgeFrames},
 * {@code minHits} — deliberately have <b>no Java property</b>: they are cv-service-side resolution
 * knobs ({@code CV_TRACK_IOU}/{@code CV_TRACK_MAX_AGE}/{@code CV_TRACK_MIN_HITS}, &sect;4.3), and
 * duplicating them here would give one number two owners.
 *
 * @param defaultMode           tracking mode new streams start in — {@code OFF}/{@code ASSOCIATE}/{@code
 *                              FOLLOW}, matched case-insensitively; default {@value #DEFAULT_MODE}
 * @param followFps             the Java-side sampler's target rate while {@code FOLLOW} is active
 *                              (docs/plans/done/TRACKING-PLAN.md &sect;5.D — cv-service has no such knob, it
 *                              must never care how often it is fed); must be positive; default
 *                              {@value #DEFAULT_FOLLOW_FPS}
 * @param verifyEveryMillis     {@code FOLLOW} detector re-verify cadence new streams start with;
 *                              must be positive; default {@value #DEFAULT_VERIFY_EVERY_MILLIS}
 * @param statsWindowSeconds    how far back {@code TrackingStatsWindow}'s duty-cycle counters reach
 *                              — what {@code GET /api/streams/{id}/tracks} reports as {@code
 *                              stats.windowSeconds}; must be positive; default {@value
 *                              #DEFAULT_STATS_WINDOW_SECONDS}
 * @param trackRetentionSeconds how long a track that stops arriving stays in {@code TrackBook}
 *                              before expiring; must be positive; default {@value
 *                              #DEFAULT_TRACK_RETENTION_SECONDS}. Not in &sect;4.3's inventory —
 *                              exposed because {@code StreamPipelineSettings}' canonical constructor
 *                              needs both durations stated, and a knob with a documented default
 *                              beats a magic literal duplicated in wiring
 * @param capabilityLevel      the capability-ladder ceiling (docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md
 *                              &sect;2) requested for every newly started stream; within [0,5];
 *                              default {@value #DEFAULT_CAPABILITY_LEVEL} = auto-probe. <b>A
 *                              ceiling, not a demand</b> (invariant B5) — cv-service serves {@code
 *                              min(requested, affordable)} and reports what it actually served on
 *                              {@code TrackingTelemetry#capability()}; this property can never make a
 *                              weak host run a level it cannot afford, only cap a strong one down
 * @param reupdateMaxGapMillis the longest gap ORU (Observation-Centric Re-Update) may reconstruct
 *                              for every newly started stream, in milliseconds; must not be
 *                              negative; default {@value #DEFAULT_REUPDATE_MAX_GAP_MILLIS} =
 *                              cv-service's own server-side default
 */
@ConfigurationProperties(prefix = "vision.tracking")
public record VisionTrackingProperties(@DefaultValue(VisionTrackingProperties.DEFAULT_MODE) String defaultMode,
                                        @DefaultValue(VisionTrackingProperties.DEFAULT_FOLLOW_FPS) int followFps,
                                        @DefaultValue(VisionTrackingProperties.DEFAULT_VERIFY_EVERY_MILLIS)
                                        int verifyEveryMillis,
                                        @DefaultValue(VisionTrackingProperties.DEFAULT_STATS_WINDOW_SECONDS)
                                        int statsWindowSeconds,
                                        @DefaultValue(VisionTrackingProperties.DEFAULT_TRACK_RETENTION_SECONDS)
                                        int trackRetentionSeconds,
                                        @DefaultValue(VisionTrackingProperties.DEFAULT_CAPABILITY_LEVEL)
                                        int capabilityLevel,
                                        @DefaultValue(VisionTrackingProperties.DEFAULT_REUPDATE_MAX_GAP_MILLIS)
                                        int reupdateMaxGapMillis) {

    static final String DEFAULT_MODE = "ASSOCIATE";
    static final String DEFAULT_FOLLOW_FPS = "15";
    static final String DEFAULT_VERIFY_EVERY_MILLIS = "2000";
    static final String DEFAULT_STATS_WINDOW_SECONDS = "30";
    static final String DEFAULT_TRACK_RETENTION_SECONDS = "5";
    static final String DEFAULT_CAPABILITY_LEVEL = "0";
    static final String DEFAULT_REUPDATE_MAX_GAP_MILLIS = "0";

    @ConstructorBinding
    public VisionTrackingProperties {
        if (defaultMode == null || defaultMode.isBlank()) {
            throw new IllegalArgumentException("vision.tracking.default-mode must not be blank");
        }
        if (followFps <= 0) {
            throw new IllegalArgumentException("vision.tracking.follow-fps must be positive, was " + followFps);
        }
        if (verifyEveryMillis <= 0) {
            throw new IllegalArgumentException(
                    "vision.tracking.verify-every-millis must be positive, was " + verifyEveryMillis);
        }
        if (statsWindowSeconds <= 0) {
            throw new IllegalArgumentException(
                    "vision.tracking.stats-window-seconds must be positive, was " + statsWindowSeconds);
        }
        if (trackRetentionSeconds <= 0) {
            throw new IllegalArgumentException(
                    "vision.tracking.track-retention-seconds must be positive, was " + trackRetentionSeconds);
        }
        if (capabilityLevel < 0 || capabilityLevel > 5) {
            throw new IllegalArgumentException(
                    "vision.tracking.capability-level must be within [0,5], was " + capabilityLevel);
        }
        if (reupdateMaxGapMillis < 0) {
            throw new IllegalArgumentException(
                    "vision.tracking.reupdate-max-gap-millis must not be negative, was " + reupdateMaxGapMillis);
        }
    }
}
