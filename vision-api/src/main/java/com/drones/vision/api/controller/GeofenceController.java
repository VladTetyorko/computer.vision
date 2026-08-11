package com.drones.vision.api.controller;

import com.drones.vision.api.dto.GeofenceZoneRequest;
import com.drones.vision.api.dto.GeofenceZoneResponse;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.application.geofence.GeofenceService;
import com.drones.vision.flight.domain.model.ZoneId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Driving REST adapter for geofence zone CRUD (docs/plans/done/OPS-CORE-PLAN.md §G's frozen wire contract).
 *
 * <p>Constructor-injected with {@link GeofenceService} only — zones are global reference data
 * with no ownership/audit concerns (see that service's own javadoc), so this controller needs no
 * second collaborator the way {@link AssetController}/{@link DeviceController} do for auditing.
 *
 * <p>Error mapping is entirely {@link GeofenceService}'s/{@link
 * com.drones.vision.application.geofence.GeofenceZoneSpec}'s own exceptions surfacing through {@link
 * ApiExceptionHandler}, no controller-side translation needed: {@link
 * java.util.NoSuchElementException} (unknown zone id on update/delete) → 404; {@link
 * IllegalArgumentException} (a malformed zone-id UUID, an unrecognized {@code kind}, or a
 * polygon with fewer than 3 vertices) → 400 — the same idiom every other controller in this
 * module already follows.
 *
 * <p>Per the hexagonal dependency rule (ARCHITECTURE.md §2, enforced by ArchUnit), this module
 * depends only on {@code vision-domain} and {@code vision-application} — never on an adapter.
 */
@RestController
public class GeofenceController {

    private final GeofenceService geofenceService;

    public GeofenceController(GeofenceService geofenceService) {
        this.geofenceService = Objects.requireNonNull(geofenceService, "geofenceService must not be null");
    }

    /**
     * Lists every known zone.
     *
     * @return every zone, sorted by name (see {@link GeofenceService#zones()})
     */
    @GetMapping("/api/geofences")
    public List<GeofenceZoneResponse> list() {
        return geofenceService.zones().stream().map(GeofenceZoneResponse::from).toList();
    }

    /**
     * Creates a new zone.
     *
     * @param request the zone to create
     * @return the created zone
     */
    @PostMapping("/api/geofences")
    @ResponseStatus(HttpStatus.CREATED)
    public GeofenceZoneResponse create(@RequestBody GeofenceZoneRequest request) {
        return GeofenceZoneResponse.from(geofenceService.create(request.toSpec()));
    }

    /**
     * Replaces an existing zone's fields wholesale (the wire contract's update request carries the
     * same shape as create).
     *
     * @param id      the zone id, as a canonical UUID string
     * @param request the replacement fields
     * @return the updated zone
     */
    @PutMapping("/api/geofences/{id}")
    public GeofenceZoneResponse update(@PathVariable String id, @RequestBody GeofenceZoneRequest request) {
        return GeofenceZoneResponse.from(geofenceService.update(ZoneId.of(id), request.toSpec()));
    }

    /**
     * Deletes a zone.
     *
     * @param id the zone id, as a canonical UUID string
     */
    @DeleteMapping("/api/geofences/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        geofenceService.delete(ZoneId.of(id));
    }
}
