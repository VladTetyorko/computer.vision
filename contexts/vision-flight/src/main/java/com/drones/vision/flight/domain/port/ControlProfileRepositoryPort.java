package com.drones.vision.flight.domain.port;

import com.drones.vision.flight.domain.model.ControlProfileId;
import com.drones.vision.flight.domain.model.OwnedControlProfile;
import com.drones.vision.flight.domain.model.VehicleKind;
import com.drones.vision.kernel.UserId;

import java.util.List;
import java.util.Optional;

/**
 * Driven port: storage for the controller layouts an operator has saved
 * (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md wave C4).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Profiles are <b>owned</b>: every read is scoped to one {@link UserId}, and no method here
 *       lets one operator reach another's layout. Two pilots flying the same vehicle have different
 *       controllers in their hands, so a shared profile would be wrong for at least one of them.</li>
 *   <li><b>At most one active profile per {@code (owner, kind)}</b>, and {@link #activate} is the
 *       only way to move it. Implementations must clear the previous active profile in the same
 *       transaction: two active profiles would leave {@link #findActive} deciding by read order,
 *       which is exactly the kind of quiet nondeterminism that surfaces as "my sticks were
 *       different today".</li>
 *   <li>{@link #save} is an upsert on {@link OwnedControlProfile#id()} and never changes
 *       activeness — activating is a separate, deliberate gesture.</li>
 *   <li>Nothing here ever returns a built-in profile; those are {@link
 *       com.drones.vision.flight.domain.model.ControlProfile#forKind}'s job and never touch storage.</li>
 * </ul>
 */
public interface ControlProfileRepositoryPort {

    /**
     * Every profile {@code owner} has saved, newest edit first.
     *
     * @param owner whose profiles to list
     * @return their profiles; empty if they have saved none
     */
    List<OwnedControlProfile> findAllByOwner(UserId owner);

    /**
     * One profile by id, regardless of owner — the caller checks ownership.
     *
     * @param id the profile to load
     * @return the profile, or empty if no such profile is stored
     */
    Optional<OwnedControlProfile> findById(ControlProfileId id);

    /**
     * The profile a new session for {@code owner} on a {@code kind} vehicle should engage with.
     *
     * @param owner the operator taking control
     * @param kind  the vehicle kind, as the link is currently reporting it
     * @return their active profile for that kind, or empty to fall back to the built-in
     */
    Optional<OwnedControlProfile> findActive(UserId owner, VehicleKind kind);

    /**
     * Inserts or replaces one profile. Activeness is left exactly as it was (or {@code false} for a
     * new profile) — see {@link #activate}.
     *
     * @param profile the profile to store
     */
    void save(OwnedControlProfile profile);

    /**
     * Makes {@code id} the owner's active profile for its own vehicle kind, atomically deactivating
     * whichever of their profiles held that place before.
     *
     * @param owner the owner
     * @param id    the profile to activate; must belong to {@code owner}
     * @throws java.util.NoSuchElementException if no such profile is stored for {@code owner}
     */
    void activate(UserId owner, ControlProfileId id);

    /**
     * Deletes one profile. Deleting the active profile simply leaves the owner with none, and the
     * built-in takes over again.
     *
     * @param id the profile to delete; deleting an unknown id is a no-op
     */
    void delete(ControlProfileId id);
}
