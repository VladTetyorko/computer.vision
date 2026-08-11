package com.drones.vision.application.geofence;

import com.drones.vision.flight.domain.model.GeofenceZone;
import com.drones.vision.flight.domain.model.ZoneId;

import java.util.List;
import java.util.Optional;

/**
 * CRUD/list over {@link GeofenceZone}s (docs/plans/done/OPS-CORE-PLAN.md §G).
 *
 * <p>One interface, one implementation ({@link DefaultGeofenceService}). Zones are global
 * reference data — no ownership, no per-user scoping, no audit trail (unlike {@code
 * AssetService}/{@code DeviceService}, nothing in the plan calls for one here) — so this
 * interface stays a thin, direct pass-through over {@link
 * com.drones.vision.flight.domain.port.GeofenceRepositoryPort} plus keeping {@link GeofenceMonitor}'s
 * cache current.
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use; all shared state lives behind the injected
 * port/monitor.
 */
public interface GeofenceService {

    /**
     * Lists every known zone, sorted by name (case-insensitive) for a stable management-panel
     * order — same reasoning as {@code CategoryService#categories()}.
     *
     * @return an immutable snapshot
     */
    List<GeofenceZone> zones();

    /**
     * Finds a zone by id.
     *
     * @param id the zone id
     * @return the zone, or {@link Optional#empty()} if none exists
     */
    Optional<GeofenceZone> find(ZoneId id);

    /**
     * Creates a new zone.
     *
     * @param spec what to create
     * @return the created zone
     */
    GeofenceZone create(GeofenceZoneSpec spec);

    /**
     * Replaces an existing zone's fields wholesale (the wire contract's update request carries the
     * same shape as create).
     *
     * @param id   the zone to update
     * @param spec the replacement fields
     * @return the updated zone
     * @throws java.util.NoSuchElementException if no zone has that id
     */
    GeofenceZone update(ZoneId id, GeofenceZoneSpec spec);

    /**
     * Deletes a zone.
     *
     * @param id the zone to delete
     * @throws java.util.NoSuchElementException if no zone has that id
     */
    void delete(ZoneId id);
}
