package com.drones.vision.flight.domain.model;

import com.drones.vision.kernel.UserId;

import java.time.Instant;

/**
 * A saved {@link ControlProfile} together with the facts that only matter once it is stored: who
 * owns it, whether it is the one their next session will use, and when it last changed
 * (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md decision C6).
 *
 * <h2>Why ownership is not a field on {@link ControlProfile}</h2>
 * A profile is a <em>layout</em> — the same value whether it came from the database, from {@link
 * ControlProfile#forKind}, or from a request body. Ownership and activeness are properties of the
 * <em>record</em> of it, and the built-ins have neither: nobody owns "Multirotor", and it is active
 * exactly when the operator has saved nothing better. Keeping them apart is what lets the fallback
 * path stay total without inventing a null owner.
 *
 * @param owner     the user whose profile this is; profiles are per-operator because two people
 *                  flying the same rover from two laptops have different controllers in their hands
 * @param profile   the layout itself
 * @param active    whether this is the profile the owner's next session on this {@link
 *                  ControlProfile#kind()} will engage with. At most one of an owner's profiles per
 *                  kind may be active — a rule the repository enforces atomically, since two active
 *                  profiles would make the choice depend on read order
 * @param updatedAt when it last changed, for display and for "which of these did I edit last"
 * @param view      how the owner's transmitter is arranged, which changes how the layout is drawn
 *                  and nothing else — see {@link TransmitterView} for why it lives here rather than
 *                  on the layout
 */
public record OwnedControlProfile(UserId owner, ControlProfile profile, boolean active, Instant updatedAt,
                                   TransmitterView view) {

    /**
     * A record of a profile whose owner has not said how their transmitter is arranged — everything
     * saved before {@link TransmitterView} existed, and every profile created since, until the
     * operator changes the picture.
     */
    public OwnedControlProfile(UserId owner, ControlProfile profile, boolean active, Instant updatedAt) {
        this(owner, profile, active, updatedAt, TransmitterView.DEFAULT);
    }

    public OwnedControlProfile {
        if (owner == null) {
            throw new IllegalArgumentException("OwnedControlProfile owner must not be null");
        }
        if (profile == null) {
            throw new IllegalArgumentException("OwnedControlProfile profile must not be null");
        }
        if (profile.isBuiltIn()) {
            throw new IllegalArgumentException("A built-in profile cannot be owned or saved: " + profile.id()
                    + " -- copy it first (ControlProfile#copyAs)");
        }
        if (updatedAt == null) {
            throw new IllegalArgumentException("OwnedControlProfile updatedAt must not be null");
        }
        if (view == null) {
            throw new IllegalArgumentException("OwnedControlProfile view must not be null");
        }
    }

    /** @return this profile's identity, for callers that only need the key */
    public ControlProfileId id() {
        return profile.id();
    }

    /** @return the vehicle kind this profile is for */
    public VehicleKind kind() {
        return profile.kind();
    }
}
