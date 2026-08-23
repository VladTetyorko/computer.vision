package com.drones.vision.perception.domain.model;

/**
 * Declarative desired state for one localization session — the framework-free shape of proto's
 * {@code GeoControl} (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.1), minus the fields that are the
 * adapter's own transport concern ({@code stream_id}/{@code source_url}/{@code rtsp_transport}/
 * {@code stop}, already parameters or lifecycle methods on {@link
 * com.drones.vision.perception.domain.port.PulledGeolocationPort} itself) and minus {@code
 * telemetry} (its own port method, restated independently — see {@link
 * com.drones.vision.perception.domain.port.PulledGeolocationPort#telemetry}).
 *
 * @param regionId  the region to search; {@code ""} = search every {@code READY} region
 * @param targetFps keyframe rate; {@code <= 0} = server default
 * @param prior     restricts retrieval to a disc around a believed position; {@code null} = search
 *                  the whole region
 */
public record GeoSessionConfig(String regionId, float targetFps, GeoPrior prior) {

    public GeoSessionConfig {
        if (regionId == null) {
            throw new IllegalArgumentException("GeoSessionConfig regionId must not be null (use \"\")");
        }
        if (Float.isNaN(targetFps) || Float.isInfinite(targetFps)) {
            throw new IllegalArgumentException("GeoSessionConfig targetFps must be finite: " + targetFps);
        }
    }
}
