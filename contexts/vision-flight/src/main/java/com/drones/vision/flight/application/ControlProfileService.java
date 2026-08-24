package com.drones.vision.flight.application;

import com.drones.vision.flight.domain.model.ActionMap;
import com.drones.vision.flight.domain.model.ChannelMap;
import com.drones.vision.flight.domain.model.ControlProfile;
import com.drones.vision.flight.domain.model.ControlProfileId;
import com.drones.vision.flight.domain.model.OwnedControlProfile;
import com.drones.vision.flight.domain.model.VehicleKind;
import com.drones.vision.kernel.UserId;

import java.util.List;

/**
 * The operator's controller layouts: what each stick, button and switch of their transmitter does
 * (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md waves C2/C5). One interface, one implementation
 * ({@link DefaultControlProfileService}), mirroring every other service in this package.
 *
 * <h2>Built-ins are not stored, and cannot be edited</h2>
 * {@link ControlProfile#forKind} answers for every vehicle kind without touching storage, so a fresh
 * install can fly before anyone has configured anything (decision C7). A built-in is therefore
 * <em>copied</em> into a saved profile rather than modified in place — {@link #create} is the only
 * door in, and every mutating method here refuses a built-in id outright.
 *
 * <h2>Ownership, not visibility scope</h2>
 * A profile describes the hardware in one person's hands, so it is gated by <em>owner</em> and not
 * by the {@link com.drones.vision.platform.VisibilityScope} that gates assets: two pilots may share
 * every aircraft on the field and still have entirely different transmitters. Every mutating method
 * refuses a profile belonging to someone else with {@link
 * com.drones.vision.platform.AccessDeniedException}.
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use; all shared state lives behind driven ports.
 */
public interface ControlProfileService {

    /**
     * The platform's own profiles, one per vehicle kind — the fallback, and the starting point every
     * saved profile is copied from.
     *
     * @return one profile per {@link VehicleKind}, never empty
     */
    List<ControlProfile> builtIns();

    /**
     * Every profile this operator has saved, newest edit first.
     *
     * @param owner whose profiles to list
     * @return their profiles; empty if they have saved none
     */
    List<OwnedControlProfile> saved(UserId owner);

    /**
     * The layout a session for {@code owner} on a {@code kind} vehicle will actually engage with —
     * their active saved profile if they have one, otherwise the built-in.
     *
     * <p>This is the exact resolution {@link ManualControlService#engage} performs, exposed so a
     * driving adapter can show the operator what they are about to fly with <em>before</em> they
     * take control rather than after.
     *
     * @param owner the operator
     * @param kind  the vehicle kind, as the link is currently reporting it
     * @return the resolved profile; never {@code null}
     */
    ControlProfile activeFor(UserId owner, VehicleKind kind);

    /**
     * Saves a new profile for {@code owner}, copied from the built-in for {@code kind}. Not
     * activated — activating is its own deliberate gesture ({@link #activate}).
     *
     * @param owner the owner-to-be
     * @param kind  the vehicle kind whose built-in to copy
     * @param name  what the operator called it; must not be blank
     * @return the saved profile
     * @throws IllegalArgumentException if {@code name} is blank
     */
    OwnedControlProfile create(UserId owner, VehicleKind kind, String name);

    /**
     * Replaces one saved profile's name and bindings.
     *
     * @param owner      the acting user
     * @param id         the profile to update
     * @param name       the new name; must not be blank
     * @param channelMap the new channel bindings
     * @param actionMap  the new action bindings
     * @return the updated profile
     * @throws java.util.NoSuchElementException if no such profile is stored
     * @throws com.drones.vision.platform.AccessDeniedException if it belongs to someone else
     * @throws IllegalArgumentException if {@code id} names a built-in, {@code name} is blank, or the
     *                                   two maps together are not a valid profile (one control bound
     *                                   twice, one RC channel driven twice)
     */
    OwnedControlProfile update(UserId owner, ControlProfileId id, String name, ChannelMap channelMap,
                               ActionMap actionMap);

    /**
     * Makes one profile the owner's active layout for its own vehicle kind, deactivating whichever
     * of theirs held that place before.
     *
     * @param owner the acting user
     * @param id    the profile to activate
     * @throws java.util.NoSuchElementException if no such profile is stored
     * @throws com.drones.vision.platform.AccessDeniedException if it belongs to someone else
     */
    void activate(UserId owner, ControlProfileId id);

    /**
     * Deletes one saved profile. Deleting the active one simply hands the vehicle kind back to its
     * built-in.
     *
     * @param owner the acting user
     * @param id    the profile to delete
     * @throws java.util.NoSuchElementException if no such profile is stored
     * @throws com.drones.vision.platform.AccessDeniedException if it belongs to someone else
     */
    void delete(UserId owner, ControlProfileId id);
}
