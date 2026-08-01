package com.drones.vision.application;

import com.drones.vision.domain.model.Mark;
import com.drones.vision.domain.model.MarkId;
import com.drones.vision.domain.model.MarkStatus;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.UserId;

import java.util.List;

/**
 * The shared operational picture: geolocated tactical {@link Mark}s, created two ways (a map click,
 * or a cockpit "geolocate" projected from a drone's pose), annotated and cleared/deleted
 * (docs/TACTICAL-MARKS-PLAN.md §2, revised for the FPV-operator/PILOT persona — see Authorization
 * below).
 *
 * <p>One interface, one implementation ({@link DefaultMarkService}). Structurally this mirrors
 * {@link GeofenceService}'s CRUD/list shape — a {@link Mark} is a point version of a {@code
 * GeofenceZone} — and, like geofence zones, {@link #list()} is now unscoped/deployment-wide (see
 * below). Ownership/actor threading still mirrors {@link AssetService}: {@link Ownership} (who it
 * belongs to) and {@link UserId actor} are separate method parameters, never constructor state.
 *
 * <h2>Visibility: deployment-wide, not group-filtered</h2>
 * The "shared operational picture" means everyone at the command point sees the <b>same</b> marks —
 * including a PILOT, whose {@link VisibilityScope#kind()} is {@code ASSIGNED_ASSETS} and so carries
 * no group at all (an earlier group-filtered design left pilots unable to see even their own marks;
 * revised). {@link #list()} therefore returns every {@link
 * com.drones.vision.domain.model.MarkStatus#ACTIVE} mark to any authenticated caller, with no
 * scope parameter — the same shape {@link GeofenceService#zones()} already has, and consistent with
 * the {@code "marks"} SSE topic, which broadcasts deployment-wide exactly like the pre-existing
 * {@code fleet}/{@code event} topics. This is an accepted, documented multi-tenant limitation, not
 * this feature's to fix (mirrors the same caveat on the live channel).
 *
 * <h2>Authorization: creator manages their own, manager manages any</h2>
 * Creating (a map click) and geolocating (cockpit) are open to any authenticated actor, who becomes
 * the mark's owner. Editing a mark — annotation (label/note/kind/position, including drag-to-correct)
 * <em>and</em> a lifecycle transition ({@code status} → {@code CLEARED} or back to {@code ACTIVE})
 * alike — and deleting one are both gated the same way: {@code actor.equals(mark.createdBy())} (the
 * creator may always manage their own mark) <b>or</b> {@link VisibilityScope#canManageOrg()} (a
 * manager/admin may manage any mark), else {@link AccessDeniedException} (403). An unknown id is
 * {@link java.util.NoSuchElementException} (404) — there is no group-visibility gate in front of
 * this check any more, so a PILOT is never hidden from their own mark.
 *
 * <h2>Live broadcast</h2>
 * Every create/update/clear publishes through {@link
 * com.drones.vision.domain.port.out.LiveUpdatePublisherPort}'s {@code publishMark*} methods, so the
 * shared picture stays live for every viewer (docs/TACTICAL-MARKS-PLAN.md §1, "New piece #1").
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use; all shared state lives behind the injected
 * ports/collaborators.
 */
public interface MarkService {

    /**
     * Lists every {@link MarkStatus#ACTIVE} mark, deployment-wide, newest first.
     *
     * <p>Unscoped by design (see the class javadoc's Visibility section) — a {@code CLEARED} mark
     * drops off this list, matching "clients drop the pin" once cleared.
     *
     * @return an immutable snapshot of active marks, newest first
     */
    List<Mark> list();

    /**
     * Drops a {@code MANUAL} mark (a map click).
     *
     * @param spec      what to create
     * @param ownership who the mark belongs to
     * @param actor     the user performing the creation
     * @return the created mark, {@link com.drones.vision.domain.model.MarkStatus#ACTIVE}
     */
    Mark create(MarkSpec spec, Ownership ownership, UserId actor);

    /**
     * Drops a {@code DETECTION} mark, projected from an asset's freshest telemetry (the cockpit
     * "geolocate" action).
     *
     * @param spec      which asset to project from, and the mark's descriptive fields
     * @param ownership who the mark belongs to
     * @param actor     the user performing the geolocation
     * @return the created mark, {@link com.drones.vision.domain.model.MarkStatus#ACTIVE}
     * @throws IllegalArgumentException if the asset has never reported telemetry, or its freshest
     *                                   sample is missing latitude/longitude/heading, or its
     *                                   altitude is missing or not positive — an honest "cannot
     *                                   geolocate: telemetry incomplete" (→ 400)
     */
    Mark geolocate(GeolocateSpec spec, Ownership ownership, UserId actor);

    /**
     * Applies a partial edit — annotation and/or a lifecycle transition. Creator-or-manager only
     * (see the class javadoc's Authorization section) — this applies uniformly to every field,
     * including a plain annotation/drag-to-correct with no status change.
     *
     * @param id     the mark to edit
     * @param patch  the fields to change
     * @param actor  the user performing the edit
     * @param scope  used only to test {@link VisibilityScope#canManageOrg()}
     * @return the updated mark
     * @throws java.util.NoSuchElementException if no mark has that id
     * @throws AccessDeniedException            if {@code actor} is neither the mark's creator nor a
     *                                           manager
     */
    Mark update(MarkId id, MarkPatch patch, UserId actor, VisibilityScope scope);

    /**
     * Removes a mark. Creator-or-manager only (see the class javadoc's Authorization section).
     *
     * @param id    the mark to delete
     * @param actor the user performing the deletion
     * @param scope used only to test {@link VisibilityScope#canManageOrg()}
     * @throws java.util.NoSuchElementException if no mark has that id
     * @throws AccessDeniedException            if {@code actor} is neither the mark's creator nor a
     *                                           manager
     */
    void delete(MarkId id, UserId actor, VisibilityScope scope);
}
