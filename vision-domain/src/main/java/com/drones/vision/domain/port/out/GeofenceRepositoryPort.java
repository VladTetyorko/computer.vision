package com.drones.vision.domain.port.out;

import com.drones.vision.domain.model.GeofenceZone;
import com.drones.vision.domain.model.ZoneId;

import java.util.List;
import java.util.Optional;

/**
 * Driven port: persist and retrieve {@link GeofenceZone}s (docs/plans/done/OPS-CORE-PLAN.md §G).
 *
 * <p>Zones are global reference data (no per-asset/per-group scoping yet), so this port has no
 * concept of ownership or a "belongs to" filter — every implementation just stores/returns the
 * full zone set, exactly like {@code CategoryRepositoryPort} does for categories.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #save(GeofenceZone)} upserts by {@link GeofenceZone#id()}: an id seen before is
 *       replaced in place, a new id is added.</li>
 *   <li>{@link #findById(ZoneId)} returns {@link Optional#empty()}, never {@code null}, when no
 *       zone with that id exists.</li>
 *   <li>{@link #findAll()} returns a snapshot; the returned list is not a live view of the
 *       store.</li>
 *   <li>{@link #deleteById(ZoneId)} is idempotent: deleting a non-existent id is a no-op, not an
 *       error.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use — zone CRUD (control-plane requests) and zone
 * reads (a {@code GeofenceMonitor} refreshing its cache) may happen concurrently.
 */
public interface GeofenceRepositoryPort {

    /**
     * Inserts or updates a zone.
     *
     * @param zone the zone to persist
     * @return the persisted zone
     */
    GeofenceZone save(GeofenceZone zone);

    /**
     * Finds a zone by id.
     *
     * @param id the zone id
     * @return the zone, or {@link Optional#empty()} if none exists
     */
    Optional<GeofenceZone> findById(ZoneId id);

    /**
     * Lists all zones.
     *
     * @return an immutable snapshot of all zones
     */
    List<GeofenceZone> findAll();

    /**
     * Deletes a zone by id. Idempotent.
     *
     * @param id the zone id to delete
     */
    void deleteById(ZoneId id);
}
