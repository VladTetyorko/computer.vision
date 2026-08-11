package com.drones.vision.api.dto;

import com.drones.vision.flight.domain.model.GeofenceZone;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Response body for {@code GET/POST /api/geofences}, {@code PUT /api/geofences/{id}}
 * (docs/plans/done/OPS-CORE-PLAN.md §G's frozen wire contract).
 *
 * <p>{@code maxAltitudeMeters} is omitted entirely (rather than serialized {@code null}) when the
 * zone has no altitude ceiling — same convention as every other optional numeric field in this
 * package. {@code polygon} reuses {@link GeoPositionResponse} as-is (each vertex's own
 * {@code altitudeMeters} is always absent for a geofence zone, since {@link GeofenceZoneRequest}
 * builds vertices with no altitude — the wire contract's polygon shape is {@code {latitude,
 * longitude}} only, the zone-level {@code maxAltitudeMeters} being the one altitude concept a
 * zone carries).
 *
 * @param id                the zone id, as a canonical UUID string
 * @param name              human-readable name
 * @param kind              {@code "KEEP_IN"} or {@code "KEEP_OUT"} (the enum name)
 * @param polygon           boundary vertices, at least 3
 * @param maxAltitudeMeters altitude ceiling in meters, or absent for no ceiling
 * @param enabled           whether the zone currently participates in breach evaluation
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeofenceZoneResponse(String id, String name, String kind, List<GeoPositionResponse> polygon,
                                    Double maxAltitudeMeters, boolean enabled) {

    /**
     * Maps a domain {@link GeofenceZone} to its wire representation.
     *
     * @param zone the zone to map
     * @return the response body for {@code zone}
     */
    public static GeofenceZoneResponse from(GeofenceZone zone) {
        return new GeofenceZoneResponse(
                zone.id().value().toString(),
                zone.name(),
                zone.kind().name(),
                zone.polygon().stream().map(GeoPositionResponse::from).toList(),
                zone.maxAltitudeMeters(),
                zone.enabled());
    }
}
