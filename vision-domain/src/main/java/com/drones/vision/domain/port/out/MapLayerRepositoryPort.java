package com.drones.vision.domain.port.out;

import com.drones.vision.domain.model.LayerId;
import com.drones.vision.domain.model.MapLayer;

import java.util.List;
import java.util.Optional;

/**
 * Driven port: persist and retrieve {@link MapLayer}s (docs/plans/done/MAP-REWORK-PLAN.md §2.3) — the same
 * minimal upsert/find/delete shape as {@link MarkRepositoryPort}/{@link GeofenceRepositoryPort}.
 *
 * <p>This port has no ownership- or group-aware query: {@link #findAll()} always returns every
 * layer, and access filtering over that snapshot is the application layer's job ({@code
 * MapAccessPolicy}), the same "no scope filtering baked into the repository" convention every
 * other port here follows.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #save(MapLayer)} upserts by {@link MapLayer#id()}: an id seen before is replaced in
 *       place, a new id is added.</li>
 *   <li>{@link #findById(LayerId)} returns {@link Optional#empty()}, never {@code null}, when no
 *       layer with that id exists.</li>
 *   <li>{@link #findAll()} returns a snapshot; the returned list is not a live view of the
 *       store.</li>
 *   <li>{@link #deleteById(LayerId)} is idempotent: deleting a non-existent id is a no-op, not an
 *       error. It does not cascade to marks/drawings on the deleted layer — cascading (and
 *       emitting a {@code MapEvent} per cascaded object) is the application layer's job.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use — layer CRUD and layer reads (a driving adapter
 * refreshing the shared operational picture) may happen concurrently.
 */
public interface MapLayerRepositoryPort {

    /**
     * Inserts or updates a layer.
     *
     * @param layer the layer to persist
     * @return the persisted layer
     */
    MapLayer save(MapLayer layer);

    /**
     * Finds a layer by id.
     *
     * @param id the layer id
     * @return the layer, or {@link Optional#empty()} if none exists
     */
    Optional<MapLayer> findById(LayerId id);

    /**
     * Lists all layers.
     *
     * @return an immutable snapshot of all layers
     */
    List<MapLayer> findAll();

    /**
     * Deletes a layer by id. Idempotent. Does not cascade to marks/drawings on the layer.
     *
     * @param id the layer id to delete
     */
    void deleteById(LayerId id);
}
