package com.drones.vision.application.mark;

import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.map.domain.model.Mark;
import com.drones.vision.map.domain.model.MarkId;
import com.drones.vision.map.domain.model.MarkStatus;
import com.drones.vision.map.domain.model.Verification.VerificationState;

import java.util.List;
import com.drones.vision.application.map.MapAccessPolicy;
import com.drones.vision.application.map.MapAccessPolicy.Viewer;
import com.drones.vision.application.map.MapLayerService;

/**
 * The shared operational picture: geolocated tactical {@link Mark}s, created two ways (a map click,
 * or a cockpit "geolocate" projected from a drone's pose), annotated, verified, promoted and
 * cleared/deleted (docs/plans/done/MAP-REWORK-PLAN.md §3, reworked in place — superseding
 * docs/plans/done/TACTICAL-MARKS-PLAN.md §2's shape and its own "list() takes no scope" divergence, see below).
 *
 * <p>One interface, one implementation ({@link DefaultMarkService}). Visibility and every gate here
 * now resolve from a {@link Viewer} — identity plus group memberships — through {@link
 * MapAccessPolicy}, exactly like {@link MapLayerService}; {@code Ownership}/{@code UserId actor} are
 * no longer separate method parameters (superseded, see below).
 *
 * <h2>Visibility: layer-scoped, not deployment-wide any more</h2>
 * docs/plans/done/TACTICAL-MARKS-PLAN.md's shipped {@code list()} took <b>no scope at all</b> — a deliberate
 * workaround for a trap in {@code VisibilityScope}: a PILOT's {@code ASSIGNED_ASSETS} scope carries
 * no group information, so group-filtering hid every mark from the primary FPV-operator persona,
 * including their own. This rework fixes that properly instead of routing around it: every mark now
 * lives on a {@link com.drones.vision.map.domain.model.MapLayer}, and {@link #list} filters to layers
 * {@link MapAccessPolicy#canView} for the given {@link Viewer} — built from identity and group
 * membership directly, never from {@code VisibilityScope} (see {@link MapAccessPolicy}'s own javadoc
 * for exactly why). A PILOT reaches the COP layer (everyone can view it) and their own team's layer
 * (group membership grants {@code CONTRIBUTE}) without the old trap resurfacing. This supersedes
 * docs/plans/done/TACTICAL-MARKS-PLAN.md §2's "deployment-wide, no scope parameter at all" design note in full.
 *
 * <h2>Authorization</h2>
 * Creating/geolocating a mark requires {@link MapAccessPolicy#canContribute} on the resolved layer
 * (explicit, or the creator's default layer if none is given — see {@code LayerResolver
 * #defaultLayerFor}). Editing/clearing/deleting a mark is gated on: the mark's own creator, while its
 * {@link com.drones.vision.map.domain.model.Verification} is still {@code UNVERIFIED}; or {@link
 * MapAccessPolicy#canManage} on its layer, unconditionally — once a mark is {@code CONFIRMED}, its
 * creator loses the standing edit right and only a manager may touch it. {@link #verify} requires
 * {@link MapAccessPolicy#canManage} on the mark's current layer. {@link #promote} requires {@link
 * MapAccessPolicy#canManage} on the source layer <em>and</em> {@link MapAccessPolicy#canContribute}
 * on the target (default: the COP layer).
 *
 * <p>An unknown mark id is {@link java.util.NoSuchElementException} (404). Every authorization
 * failure above — including on a mark whose layer the actor cannot even view — is {@link
 * com.drones.vision.application.scope.AccessDeniedException} (403), matching how {@code DefaultFlightCommandService}'s own command gate
 * already treats a command (as opposed to a read) on an out-of-scope resource: it is more honest to
 * say "you may not do this" than to hide the mark.
 *
 * <h2>Live broadcast</h2>
 * Every create/patch/verify/promote/delete publishes a {@link
 * com.drones.vision.map.domain.model.MapEvent} through {@link
 * com.drones.vision.events.domain.port.LiveUpdatePublisherPort#publishMapEvent}, so the shared picture
 * stays live for every viewer whose {@link Viewer} may see the event's layer (scoped SSE delivery is
 * a Wave C concern). {@link #patch} publishes {@code CLEARED} when the patch flips {@link
 * #patch}'s status to {@link MarkStatus#CLEARED}, {@code UPDATED} otherwise; {@link #delete} now
 * publishes {@code DELETED} (the old two-method {@code publishMarkCleared}/{@code
 * publishMarkUpdated} split used {@code CLEARED} for both a status flip and an actual delete, since
 * no {@code DELETED} action existed yet — it does now).
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use; all shared state lives behind the injected
 * ports/collaborators.
 */
public interface MarkService {

    /**
     * Lists every {@link MarkStatus#ACTIVE} mark on a layer {@code v} may view, newest first.
     *
     * <p>A {@code CLEARED} mark drops off this list, matching "clients drop the pin" once cleared.
     *
     * @param v who is asking
     * @return an immutable snapshot of visible active marks, newest first
     */
    List<Mark> list(Viewer v);

    /**
     * Drops a {@code MANUAL} mark (a map click), {@code UNVERIFIED} by default.
     *
     * @param v    who is creating it
     * @param spec what to create
     * @return the created mark, {@link MarkStatus#ACTIVE}
     * @throws java.util.NoSuchElementException if {@code spec.layerId()} is given and unknown
     * @throws com.drones.vision.application.scope.AccessDeniedException              if {@code v} does not {@link
     *                                             MapAccessPolicy#canContribute} to the resolved layer
     */
    Mark create(Viewer v, MarkSpec spec);

    /**
     * Drops a {@code DETECTION} mark, projected from an asset's freshest telemetry (the cockpit
     * "geolocate" action), {@code UNVERIFIED} by default.
     *
     * @param v    who is geolocating it
     * @param spec which asset to project from, and the mark's descriptive fields
     * @return the created mark, {@link MarkStatus#ACTIVE}
     * @throws java.util.NoSuchElementException if {@code spec.layerId()} is given and unknown
     * @throws com.drones.vision.application.scope.AccessDeniedException              if {@code v} does not {@link
     *                                             MapAccessPolicy#canContribute} to the resolved layer
     * @throws IllegalArgumentException           if the asset has never reported telemetry, or its
     *                                             freshest sample is missing latitude/longitude/
     *                                             heading, or its altitude is missing or not
     *                                             positive — an honest "cannot geolocate: telemetry
     *                                             incomplete" (→ 400)
     */
    Mark geolocate(Viewer v, GeolocateSpec spec);

    /**
     * Applies a partial edit — annotation and/or a lifecycle transition. See the class javadoc's
     * Authorization section for the creator-while-unverified-or-manager gate.
     *
     * @param v     who is editing it
     * @param id    the mark to edit
     * @param patch the fields to change
     * @return the updated mark
     * @throws java.util.NoSuchElementException if no mark has that id
     * @throws com.drones.vision.application.scope.AccessDeniedException              if {@code v} may not edit this mark
     */
    Mark patch(Viewer v, MarkId id, MarkPatch patch);

    /**
     * Reviews a mark: {@link VerificationState#CONFIRMED} or {@link VerificationState#REJECTED}.
     *
     * @param v        who is reviewing it
     * @param id       the mark to review
     * @param decision {@link VerificationState#CONFIRMED} or {@link VerificationState#REJECTED}
     * @return the updated mark
     * @throws java.util.NoSuchElementException if no mark has that id
     * @throws com.drones.vision.application.scope.AccessDeniedException              if {@code v} does not {@link
     *                                             MapAccessPolicy#canManage} its layer
     * @throws IllegalArgumentException           if {@code decision} is {@link
     *                                             VerificationState#UNVERIFIED}
     */
    Mark verify(Viewer v, MarkId id, VerificationState decision);

    /**
     * Moves a mark to a wider-shared layer — DELTA's "verify then share wider" flow — stamping
     * {@link VerificationState#CONFIRMED} if it is not already.
     *
     * @param v          who is promoting it
     * @param id         the mark to promote
     * @param targetOrNull the destination layer, or {@code null} to promote to the COP layer
     * @return the updated mark, now on the target layer
     * @throws java.util.NoSuchElementException if no mark, or no target layer, has that id
     * @throws com.drones.vision.application.scope.AccessDeniedException              if {@code v} does not {@link
     *                                             MapAccessPolicy#canManage} the source layer, or
     *                                             does not {@link MapAccessPolicy#canContribute} to
     *                                             the target
     */
    Mark promote(Viewer v, MarkId id, LayerId targetOrNull);

    /**
     * Removes a mark. See the class javadoc's Authorization section for the gate.
     *
     * @param v  who is deleting it
     * @param id the mark to delete
     * @throws java.util.NoSuchElementException if no mark has that id
     * @throws com.drones.vision.application.scope.AccessDeniedException              if {@code v} may not delete this mark
     */
    void delete(Viewer v, MarkId id);
}
