package com.drones.vision.application.map;

import com.drones.vision.domain.model.LayerGrant;
import com.drones.vision.domain.model.LayerId;
import com.drones.vision.domain.model.LayerKind;
import com.drones.vision.domain.model.MapLayer;

import java.util.List;
import com.drones.vision.application.map.MapAccessPolicy.Viewer;

/**
 * Layer CRUD and grant management (docs/plans/done/MAP-REWORK-PLAN.md §3) — the shared, access-controlled
 * surfaces marks and drawings live on. One interface, one implementation ({@link
 * DefaultMapLayerService}).
 *
 * <h2>Authorization</h2>
 * Every method except {@link #copLayerId()} takes a {@link Viewer} and is gated by {@link
 * MapAccessPolicy}: {@link #layers} silently filters to visible layers; {@link #create} gates a
 * {@link LayerKind#TEAM} layer on "MANAGER of that group, or ADMIN" and leaves {@link
 * LayerKind#PERSONAL} open to anyone; {@link #rename}/{@link #delete}/{@link #setGrants} require
 * {@link MapAccessPolicy#canManage}. An unknown layer id is {@link java.util.NoSuchElementException}
 * (404); an in-scope-but-insufficient access level is {@link com.drones.vision.application.scope.AccessDeniedException} (403).
 *
 * <h2>The COP layer</h2>
 * Exactly one {@link LayerKind#COP} layer exists per deployment — the default mark-promotion target
 * everyone can see. It is never created via {@link #create}, only lazily via {@link #copLayerId()};
 * it can never be renamed or deleted through this service, regardless of who is asking.
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use.
 */
public interface MapLayerService {

    /**
     * Lists every layer {@code v} may see, each paired with their own highest access level.
     *
     * @param v who is asking
     * @return an immutable list, the COP layer first, then every other visible layer sorted by name
     *         (case-insensitive)
     */
    List<LayerView> layers(Viewer v);

    /**
     * Creates a {@link LayerKind#TEAM} or {@link LayerKind#PERSONAL} layer.
     *
     * @param v    who is creating it
     * @param spec what to create
     * @return the created layer
     * @throws com.drones.vision.application.scope.AccessDeniedException if {@code spec.kind()} is {@link LayerKind#TEAM} and {@code v} is
     *                                neither an ADMIN nor a MANAGER of {@code spec.groupId()}
     */
    MapLayer create(Viewer v, LayerSpec spec);

    /**
     * Renames a non-COP layer.
     *
     * @param v    who is renaming it
     * @param id   the layer to rename
     * @param name the replacement name
     * @return the renamed layer
     * @throws java.util.NoSuchElementException if no layer has that id
     * @throws IllegalStateException             if the layer is the COP layer
     * @throws com.drones.vision.application.scope.AccessDeniedException              if {@code v} does not {@link
     *                                             MapAccessPolicy#canManage} it
     */
    MapLayer rename(Viewer v, LayerId id, String name);

    /**
     * Deletes a non-COP layer, cascading to every mark and drawing on it (each cascade deletion
     * publishes its own {@code MapEvent}, followed by the layer's own DELETED event).
     *
     * @param v  who is deleting it
     * @param id the layer to delete
     * @throws java.util.NoSuchElementException if no layer has that id
     * @throws IllegalStateException             if the layer is the COP layer
     * @throws com.drones.vision.application.scope.AccessDeniedException              if {@code v} does not {@link
     *                                             MapAccessPolicy#canManage} it
     */
    void delete(Viewer v, LayerId id);

    /**
     * Wholesale-replaces a layer's grant list.
     *
     * @param v      who is granting
     * @param id     the layer to update
     * @param grants the complete replacement grant list
     * @return the updated layer
     * @throws java.util.NoSuchElementException if no layer has that id
     * @throws com.drones.vision.application.scope.AccessDeniedException              if {@code v} does not {@link
     *                                             MapAccessPolicy#canManage} it
     */
    MapLayer setGrants(Viewer v, LayerId id, List<LayerGrant> grants);

    /**
     * The single, deployment-wide Common Operational Picture layer's id — found, or lazily created
     * on first call. Idempotent, so {@code vision-app} needs no separate bootstrap step. Takes no
     * {@link Viewer}: this is infrastructure, not a user-initiated action.
     *
     * @return the COP layer's id
     */
    LayerId copLayerId();
}
