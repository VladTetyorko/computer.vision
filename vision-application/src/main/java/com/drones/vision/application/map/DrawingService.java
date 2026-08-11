package com.drones.vision.application.map;

import com.drones.vision.domain.model.Drawing;
import com.drones.vision.domain.model.DrawingId;

import java.util.List;
import com.drones.vision.application.map.MapAccessPolicy.Viewer;
import com.drones.vision.application.scope.AccessDeniedException;

/**
 * Lines, polygons, arrows and text annotations on a {@link com.drones.vision.domain.model.MapLayer}
 * (docs/plans/done/MAP-REWORK-PLAN.md §3/§5.1) — the substrate for a later "plans" slice. One interface, one
 * implementation ({@link DefaultDrawingService}).
 *
 * <h2>Authorization</h2>
 * {@link #list} silently filters to layers {@link MapAccessPolicy#canView}. {@link #create} requires
 * {@link MapAccessPolicy#canContribute} on the resolved layer (explicit, or the creator's default —
 * see {@link LayerResolver#defaultLayerFor}). {@link #patch}/{@link #delete} are gated the same way
 * as a mark's annotation edits minus the verification carve-out (a {@link Drawing} has no review
 * state): the creator may always edit/delete their own drawing, or a viewer with {@link
 * MapAccessPolicy#canManage} on its layer may edit/delete any drawing there. An unknown id is {@link
 * java.util.NoSuchElementException} (404); an insufficient access level is {@link
 * AccessDeniedException} (403).
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use.
 */
public interface DrawingService {

    /**
     * Lists every drawing on a layer {@code v} may see.
     *
     * @param v who is asking
     * @return an immutable snapshot, newest first
     */
    List<Drawing> list(Viewer v);

    /**
     * Creates a drawing.
     *
     * @param v    who is creating it
     * @param spec what to create
     * @return the created drawing
     * @throws java.util.NoSuchElementException if {@code spec.layerId()} is given and unknown
     * @throws AccessDeniedException              if {@code v} does not {@link
     *                                             MapAccessPolicy#canContribute} to the resolved layer
     */
    Drawing create(Viewer v, DrawingSpec spec);

    /**
     * Applies a partial edit (geometry and/or descriptive fields).
     *
     * @param v     who is editing it
     * @param id    the drawing to edit
     * @param patch the fields to change
     * @return the updated drawing
     * @throws java.util.NoSuchElementException if no drawing has that id
     * @throws AccessDeniedException              if {@code v} is neither the drawing's creator nor a
     *                                             manager of its layer
     */
    Drawing patch(Viewer v, DrawingId id, DrawingPatch patch);

    /**
     * Removes a drawing.
     *
     * @param v  who is deleting it
     * @param id the drawing to delete
     * @throws java.util.NoSuchElementException if no drawing has that id
     * @throws AccessDeniedException              if {@code v} is neither the drawing's creator nor a
     *                                             manager of its layer
     */
    void delete(Viewer v, DrawingId id);
}
