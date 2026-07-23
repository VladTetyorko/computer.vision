package com.drones.vision.application;

/**
 * Everything needed to turn a video file — or, since docs/CYCLES-PLAN.md §9 (CU-a), nothing at all
 * — into a watchable, telemetry-emitting simulated drone in one call (docs/CYCLES-PLAN.md §1b): the
 * zero-hardware entry point.
 *
 * @param displayName human-readable name; {@code null}/blank derives one from {@code videoPath}'s
 *                     file name (extension stripped) when a path is given, or a generic synthetic
 *                     name (docs/CYCLES-PLAN.md §9) when it is not
 * @param videoPath   absolute path to a local video file, or {@code null} for a fully synthetic
 *                     simulation (docs/CYCLES-PLAN.md §9, CU-a) — a {@code "sim"}-protocol video
 *                     device (the same synthetic renderer the telemetry device already uses)
 *                     instead of a {@code "file"}-protocol one; must not be blank (blank is not the
 *                     same as absent) and, when non-{@code null}, is only further validated
 *                     (existence, regular file, readable) in {@link SimulationService}, not here,
 *                     since that requires filesystem access, not just shape-checking. {@code null}
 *                     is only valid with {@link SimulationTransport#DIRECT} — a wired transport has
 *                     no in-process renderer output to push over the wire, so {@link
 *                     SimulationTransport#RTSP}/{@link SimulationTransport#MJPEG} require a
 *                     non-{@code null} path
 * @param latitude     home-point latitude for the synthetic telemetry track, or {@code null} for
 *                     {@code SimulatedTelemetrySource}'s own default
 * @param longitude    home-point longitude for the synthetic telemetry track, or {@code null} for
 *                     {@code SimulatedTelemetrySource}'s own default
 * @param autoStart    whether to start streaming immediately after creating the asset
 * @param transport    how the video reaches its device — {@link SimulationTransport#DIRECT}
 *                     (in-process playback, the only transport a {@code null} {@link #videoPath()}
 *                     may use) or {@link SimulationTransport#RTSP}/{@link SimulationTransport#MJPEG}
 *                     (pushed over the wire via {@code FeedTransmitterPort} and ingested back,
 *                     docs/CYCLES-PLAN.md §3, §5, both requiring a non-{@code null} {@link
 *                     #videoPath()}); must not be {@code null}
 * @param plan         an optional configurable flight plan (docs/CYCLES-PLAN.md §7, CT-a) for the
 *                     synthetic telemetry track — {@code null} keeps today's circular home-point
 *                     track ({@link #latitude()}/{@link #longitude()}); when given, it wins over
 *                     those bare fields
 */
public record SimulationSpec(String displayName, String videoPath, Double latitude, Double longitude,
                              boolean autoStart, SimulationTransport transport, TelemetryPlan plan) {

    public SimulationSpec {
        if (videoPath != null && videoPath.isBlank()) {
            throw new IllegalArgumentException("SimulationSpec videoPath must not be blank");
        }
        if (transport == null) {
            throw new IllegalArgumentException("SimulationSpec transport must not be null");
        }
        if (videoPath == null && transport != SimulationTransport.DIRECT) {
            throw new IllegalArgumentException("SimulationSpec transport " + transport + " requires a videoPath");
        }
    }

    /**
     * Convenience constructor defaulting {@link #plan()} to {@code null} (no flight plan, the
     * circular home-point track) — mirrors {@code Asset}/{@code Device}'s (vision-domain) N-1-arg
     * convenience constructors that default a newly added field, rather than {@code AssetEdit}/
     * {@code DeviceEdit}'s null-means-"leave unchanged" idiom, which only applies to
     * partial-<em>edit</em> records (this is a creation command, so {@code null} can't mean
     * "unchanged"). Keeps every pre-existing 6-arg call site source-compatible.
     *
     * @param displayName see the canonical constructor
     * @param videoPath   see the canonical constructor
     * @param latitude    see the canonical constructor
     * @param longitude   see the canonical constructor
     * @param autoStart   see the canonical constructor
     * @param transport   see the canonical constructor
     */
    public SimulationSpec(String displayName, String videoPath, Double latitude, Double longitude,
                           boolean autoStart, SimulationTransport transport) {
        this(displayName, videoPath, latitude, longitude, autoStart, transport, null);
    }

    /**
     * Convenience constructor defaulting {@link #transport()} to {@link SimulationTransport#DIRECT}
     * and {@link #plan()} to {@code null}. Keeps every pre-existing 5-arg call site
     * source-compatible.
     *
     * @param displayName see the canonical constructor
     * @param videoPath   see the canonical constructor
     * @param latitude    see the canonical constructor
     * @param longitude   see the canonical constructor
     * @param autoStart   see the canonical constructor
     */
    public SimulationSpec(String displayName, String videoPath, Double latitude, Double longitude,
                           boolean autoStart) {
        this(displayName, videoPath, latitude, longitude, autoStart, SimulationTransport.DIRECT, null);
    }
}
