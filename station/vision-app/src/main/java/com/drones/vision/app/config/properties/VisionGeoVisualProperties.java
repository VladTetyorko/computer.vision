package com.drones.vision.app.config.properties;

import com.drones.vision.adapter.cvgrpc.GeoUploadSettings;
import com.drones.vision.adapter.tiles.TileSourceSettings;
import com.drones.vision.flight.application.TrackCorrectionSettings;
import com.drones.vision.perception.application.geo.ReferenceRegionSettings;
import com.drones.vision.perception.domain.model.GeoSessionConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

/**
 * Configuration for visual geolocation ({@code vision.geo.visual.*}), docs/plans/active/VISUAL-GEO-V2-PLAN.md
 * §3.6 — every value there, frozen verbatim, none as a code constant (CLAUDE.md rule 1). Bound by
 * {@link com.drones.vision.app.config.wiring.VisualGeoWiringConfiguration}.
 *
 * @param enabled              the master gate (D9); default {@code false} — with it off, no geo bean or
 *                              schedule exists and every §3.3 endpoint answers {@code 409}
 * @param rtspBase              mediamtx base URL the worker pulls keyframes from (D2, pull-only
 *                              transport — no frame ever crosses the Java wire); default {@value #DEFAULT_RTSP_BASE}
 * @param rtspTransport         RTSP transport hint restated on every control message; default {@value #DEFAULT_RTSP_TRANSPORT}
 * @param keyframeFps           the worker's own geo sampling rate, frames/sec — becomes every opened
 *                              session's {@link GeoSessionConfig#targetFps()}; default {@value #DEFAULT_KEYFRAME_FPS}
 * @param runnerIntervalMillis  {@code VisualGeoRunner}'s own tick cadence — session reconcile +
 *                              telemetry pump + prune, all on one cadence; default {@value #DEFAULT_RUNNER_INTERVAL_MILLIS}
 * @param telemetryMaxAge       telemetry older than this is withheld rather than sent stale (PullControl
 *                              doctrine — a one-shot message can be lost with no error); default {@value #DEFAULT_TELEMETRY_MAX_AGE}
 * @param mountPitchDegrees     the fixed camera's pitch relative to the airframe, positive-up (a camera
 *                              bolted 36° nose-down reads {@code -36.0}) — used by {@code GeoFixCodec}
 *                              only when an aircraft reports no gimbal pitch at all, so a gimbal-less
 *                              platform still crosses the wire with a real {@code camera_pitch_deg}
 *                              instead of being silently modelled as nadir (§9.11 defect 2, H8);
 *                              default {@value #DEFAULT_MOUNT_PITCH_DEGREES}, boresight along the
 *                              airframe's forward axis
 * @param region                reference-region ingest limits — see {@link Region}
 * @param tiles                 tile source configuration — see {@link Tiles}
 * @param upload                reference-tile upload limits — see {@link Upload}
 * @param gate                  the Java-side confidence gate, D5/§4.3 — see {@link Gate}
 * @param divergence            the divergence-alarm knobs, §4.5 — see {@link Divergence}
 * @param retention             {@code track_corrections} prune horizon; default {@value #DEFAULT_RETENTION}
 * @param maxRowsPerUsage       per-usage row cap trimmed by the runner; default {@value #DEFAULT_MAX_ROWS_PER_USAGE}
 */
@ConfigurationProperties(prefix = "vision.geo.visual")
public record VisionGeoVisualProperties(@DefaultValue("false") boolean enabled,
                                         @DefaultValue(DEFAULT_RTSP_BASE) URI rtspBase,
                                         @DefaultValue(DEFAULT_RTSP_TRANSPORT) String rtspTransport,
                                         @DefaultValue(DEFAULT_KEYFRAME_FPS) float keyframeFps,
                                         @DefaultValue(DEFAULT_RUNNER_INTERVAL_MILLIS) long runnerIntervalMillis,
                                         @DefaultValue(DEFAULT_TELEMETRY_MAX_AGE) Duration telemetryMaxAge,
                                         @DefaultValue(DEFAULT_MOUNT_PITCH_DEGREES) double mountPitchDegrees,
                                         @DefaultValue Region region, @DefaultValue Tiles tiles,
                                         @DefaultValue Upload upload, @DefaultValue Gate gate,
                                         @DefaultValue Divergence divergence,
                                         @DefaultValue(DEFAULT_RETENTION) Duration retention,
                                         @DefaultValue(DEFAULT_MAX_ROWS_PER_USAGE) int maxRowsPerUsage) {

    static final String DEFAULT_RTSP_BASE = "rtsp://localhost:8554";
    static final String DEFAULT_RTSP_TRANSPORT = "tcp";
    static final String DEFAULT_KEYFRAME_FPS = "1.0";
    static final String DEFAULT_RUNNER_INTERVAL_MILLIS = "2000";
    static final String DEFAULT_TELEMETRY_MAX_AGE = "PT2S";
    static final String DEFAULT_MOUNT_PITCH_DEGREES = "0.0";
    static final String DEFAULT_RETENTION = "PT12H";
    static final String DEFAULT_MAX_ROWS_PER_USAGE = "20000";

    public VisionGeoVisualProperties {
        if (rtspBase == null) {
            throw new IllegalArgumentException("vision.geo.visual.rtsp-base must not be null");
        }
        if (rtspTransport == null || rtspTransport.isBlank()) {
            throw new IllegalArgumentException("vision.geo.visual.rtsp-transport must not be blank");
        }
        if (runnerIntervalMillis <= 0) {
            throw new IllegalArgumentException(
                    "vision.geo.visual.runner-interval-millis must be positive: " + runnerIntervalMillis);
        }
        if (telemetryMaxAge == null || telemetryMaxAge.isNegative() || telemetryMaxAge.isZero()) {
            throw new IllegalArgumentException(
                    "vision.geo.visual.telemetry-max-age must be positive: " + telemetryMaxAge);
        }
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("vision.geo.visual.retention must be positive: " + retention);
        }
        if (maxRowsPerUsage <= 0) {
            throw new IllegalArgumentException(
                    "vision.geo.visual.max-rows-per-usage must be positive: " + maxRowsPerUsage);
        }
        if (!Double.isFinite(mountPitchDegrees)) {
            throw new IllegalArgumentException(
                    "vision.geo.visual.mount-pitch-degrees must be finite: " + mountPitchDegrees);
        }
    }

    /**
     * @param zoom     tile zoom level — z16 measured a 0.75-0.96 false-fix rate, do not lower;
     *                 default {@value #DEFAULT_ZOOM}
     * @param maxTiles per-region tile ceiling before an ingest is refused; default {@value #DEFAULT_MAX_TILES}
     */
    public record Region(@DefaultValue(DEFAULT_ZOOM) int zoom, @DefaultValue(DEFAULT_MAX_TILES) int maxTiles) {

        static final String DEFAULT_ZOOM = "17";
        static final String DEFAULT_MAX_TILES = "4000";
    }

    /**
     * @param urlTemplate      the {@code {z}/{x}/{y}} tile URL template; default the frozen Esri World
     *                         Imagery endpoint
     * @param attribution      display attribution string, not itself part of {@link TileSourceSettings}
     *                         (a UI concern); default {@value #DEFAULT_ATTRIBUTION}
     * @param concurrency      max concurrent tile fetches; default {@value #DEFAULT_CONCURRENCY}
     * @param requestsPerSecond fetch rate ceiling; default {@value #DEFAULT_REQUESTS_PER_SECOND}
     * @param timeout          per-tile fetch timeout; default {@value #DEFAULT_TIMEOUT}
     * @param maxRetries       per-tile retry ceiling; default {@value #DEFAULT_MAX_RETRIES}
     * @param userAgent        HTTP {@code User-Agent} sent with each tile fetch; default {@value #DEFAULT_USER_AGENT}
     * @param waybackMultiDate selects {@code WaybackTileSource} over {@code HttpTileSource} when {@code
     *                         true} — off until occlusion is the bottleneck; default {@value #DEFAULT_WAYBACK_MULTI_DATE}
     */
    public record Tiles(@DefaultValue(DEFAULT_URL_TEMPLATE) String urlTemplate,
                         @DefaultValue(DEFAULT_ATTRIBUTION) String attribution,
                         @DefaultValue(DEFAULT_CONCURRENCY) int concurrency,
                         @DefaultValue(DEFAULT_REQUESTS_PER_SECOND) double requestsPerSecond,
                         @DefaultValue(DEFAULT_TIMEOUT) Duration timeout,
                         @DefaultValue(DEFAULT_MAX_RETRIES) int maxRetries,
                         @DefaultValue(DEFAULT_USER_AGENT) String userAgent,
                         @DefaultValue(DEFAULT_WAYBACK_MULTI_DATE) boolean waybackMultiDate) {

        static final String DEFAULT_URL_TEMPLATE =
                "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}";
        static final String DEFAULT_ATTRIBUTION = "Esri World Imagery";
        static final String DEFAULT_CONCURRENCY = "4";
        static final String DEFAULT_REQUESTS_PER_SECOND = "20";
        static final String DEFAULT_TIMEOUT = "PT10S";
        static final String DEFAULT_MAX_RETRIES = "5";
        static final String DEFAULT_USER_AGENT = "vision-geo/0.0.2";
        static final String DEFAULT_WAYBACK_MULTI_DATE = "false";
    }

    /**
     * @param timeout    the whole-region upload timeout, covering the entire {@code
     *                   BuildReferenceIndex} bidi call; default {@value #DEFAULT_TIMEOUT}
     * @param chunkBytes target size of each streamed chunk; default {@value #DEFAULT_CHUNK_BYTES}
     */
    public record Upload(@DefaultValue(DEFAULT_TIMEOUT) Duration timeout,
                          @DefaultValue(DEFAULT_CHUNK_BYTES) int chunkBytes) {

        static final String DEFAULT_TIMEOUT = "PT30M";
        static final String DEFAULT_CHUNK_BYTES = "262144";
    }

    /**
     * D5's Java-side confidence gate (§4.3).
     *
     * @param minRadiusMeters        a fix claiming better than this is not believed; default {@value #DEFAULT_MIN_RADIUS_METERS}
     * @param maxRadiusMeters        above this the fix is downgraded to PROBABLE, never CONFIRMED;
     *                               default {@value #DEFAULT_MAX_RADIUS_METERS}
     * @param confirmConsecutive     consecutive agreeing fixes required before CONFIRMED; default {@value #DEFAULT_CONFIRM_CONSECUTIVE}
     * @param confirmAgreementMeters the agreement radius across that consecutive run; default {@value #DEFAULT_CONFIRM_AGREEMENT_METERS}
     * @param confirmWindow          the consecutive run must fit inside this; default {@value #DEFAULT_CONFIRM_WINDOW}
     */
    public record Gate(@DefaultValue(DEFAULT_MIN_RADIUS_METERS) double minRadiusMeters,
                        @DefaultValue(DEFAULT_MAX_RADIUS_METERS) double maxRadiusMeters,
                        @DefaultValue(DEFAULT_CONFIRM_CONSECUTIVE) int confirmConsecutive,
                        @DefaultValue(DEFAULT_CONFIRM_AGREEMENT_METERS) double confirmAgreementMeters,
                        @DefaultValue(DEFAULT_CONFIRM_WINDOW) Duration confirmWindow) {

        static final String DEFAULT_MIN_RADIUS_METERS = "5.0";
        static final String DEFAULT_MAX_RADIUS_METERS = "120.0";
        static final String DEFAULT_CONFIRM_CONSECUTIVE = "3";
        static final String DEFAULT_CONFIRM_AGREEMENT_METERS = "60.0";
        static final String DEFAULT_CONFIRM_WINDOW = "PT10S";
    }

    /**
     * The divergence-alarm knobs (§4.5).
     *
     * @param sigma                  separation greater than {@code sigma} times the combined
     *                               one-sigma radius qualifies; default {@value #DEFAULT_SIGMA}
     * @param consecutiveFixes       qualifying CONFIRMED fixes before the alarm latches; default {@value #DEFAULT_CONSECUTIVE_FIXES}
     * @param clearAfter             no qualifying fix for this long clears the alarm; default {@value #DEFAULT_CLEAR_AFTER}
     * @param defaultRawRadiusMeters used when the raw fix reports no HDOP-derived radius; default {@value #DEFAULT_DEFAULT_RAW_RADIUS_METERS}
     */
    public record Divergence(@DefaultValue(DEFAULT_SIGMA) double sigma,
                              @DefaultValue(DEFAULT_CONSECUTIVE_FIXES) int consecutiveFixes,
                              @DefaultValue(DEFAULT_CLEAR_AFTER) Duration clearAfter,
                              @DefaultValue(DEFAULT_DEFAULT_RAW_RADIUS_METERS) double defaultRawRadiusMeters) {

        static final String DEFAULT_SIGMA = "3.0";
        static final String DEFAULT_CONSECUTIVE_FIXES = "4";
        static final String DEFAULT_CLEAR_AFTER = "PT30S";
        static final String DEFAULT_DEFAULT_RAW_RADIUS_METERS = "10.0";
    }

    /**
     * The bundle {@code DefaultTrackCorrectionService}'s constructor takes.
     *
     * @return the equivalent {@link TrackCorrectionSettings}
     */
    public TrackCorrectionSettings toTrackCorrectionSettings() {
        TrackCorrectionSettings.GateSettings gateSettings =
                new TrackCorrectionSettings.GateSettings(gate.minRadiusMeters(), gate.maxRadiusMeters(),
                        gate.confirmConsecutive(), gate.confirmAgreementMeters(), gate.confirmWindow());
        TrackCorrectionSettings.DivergenceSettings divergenceSettings =
                new TrackCorrectionSettings.DivergenceSettings(divergence.sigma(), divergence.consecutiveFixes(),
                        divergence.clearAfter(), divergence.defaultRawRadiusMeters());
        return new TrackCorrectionSettings(gateSettings, divergenceSettings);
    }

    /**
     * @return the equivalent {@link ReferenceRegionSettings}
     */
    public ReferenceRegionSettings toReferenceRegionSettings() {
        return new ReferenceRegionSettings(region.maxTiles());
    }

    /**
     * Combines {@link Region#zoom()}/{@link Region#maxTiles()} with {@link Tiles}'s own fields — the
     * tile source cares about the same zoom/cap the region-ingest gate does, so those two live under
     * {@code region.*} rather than being duplicated under {@code tiles.*}.
     *
     * @return the equivalent {@link TileSourceSettings}
     */
    public TileSourceSettings toTileSourceSettings() {
        return new TileSourceSettings(tiles.urlTemplate(), region.zoom(), region.maxTiles(), tiles.concurrency(),
                tiles.requestsPerSecond(), tiles.timeout(), tiles.userAgent(), tiles.maxRetries());
    }

    /**
     * @return the equivalent {@link GeoUploadSettings}
     */
    public GeoUploadSettings toGeoUploadSettings() {
        return new GeoUploadSettings(upload.timeout(), upload.chunkBytes());
    }

    /**
     * The session config {@code GeolocationSessionService.start} takes when {@code VisualGeoRunner}
     * opens a new session — search every {@code READY} region ({@code regionId=""}) with no prior,
     * at this deployment's configured sampling rate.
     *
     * @return the default per-stream {@link GeoSessionConfig}
     */
    public GeoSessionConfig defaultSessionConfig() {
        return new GeoSessionConfig("", keyframeFps, null);
    }
}
