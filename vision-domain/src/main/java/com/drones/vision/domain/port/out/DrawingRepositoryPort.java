package com.drones.vision.domain.port.out;

import com.drones.vision.domain.model.Drawing;
import com.drones.vision.domain.model.DrawingId;

import java.util.List;
import java.util.Optional;

/**
 * Driven port: persist and retrieve {@link Drawing}s (docs/MAP-REWORK-PLAN.md §2.3) — the same
 * minimal upsert/find/delete shape as {@link MarkRepositoryPort}/{@link GeofenceRepositoryPort}.
 *
 * <p>This port has no layer- or group-aware query: {@link #findAll()} always returns every
 * drawing, and access filtering over that snapshot is the application layer's job ({@code
 * MapAccessPolicy}), the same "no scope filtering baked into the repository" convention every
 * other port here follows.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #save(Drawing)} upserts by {@link Drawing#id()}: an id seen before is replaced in
 *       place, a new id is added.</li>
 *   <li>{@link #findById(DrawingId)} returns {@link Optional#empty()}, never {@code null}, when no
 *       drawing with that id exists.</li>
 *   <li>{@link #findAll()} returns a snapshot; the returned list is not a live view of the
 *       store.</li>
 *   <li>{@link #deleteById(DrawingId)} is idempotent: deleting a non-existent id is a no-op, not
 *       an error.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use — drawing CRUD and drawing reads (a driving
 * adapter refreshing the shared operational picture) may happen concurrently.
 */
public interface DrawingRepositoryPort {

    /**
     * Inserts or updates a drawing.
     *
     * @param drawing the drawing to persist
     * @return the persisted drawing
     */
    Drawing save(Drawing drawing);

    /**
     * Finds a drawing by id.
     *
     * @param id the drawing id
     * @return the drawing, or {@link Optional#empty()} if none exists
     */
    Optional<Drawing> findById(DrawingId id);

    /**
     * Lists all drawings.
     *
     * @return an immutable snapshot of all drawings
     */
    List<Drawing> findAll();

    /**
     * Deletes a drawing by id. Idempotent.
     *
     * @param id the drawing id to delete
     */
    void deleteById(DrawingId id);
}
