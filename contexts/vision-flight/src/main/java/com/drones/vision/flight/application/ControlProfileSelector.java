package com.drones.vision.flight.application;

import com.drones.vision.flight.domain.model.ControlProfile;
import com.drones.vision.flight.domain.model.VehicleKind;
import com.drones.vision.kernel.UserId;

/**
 * The one question a manual-control session asks about controller configuration: <em>which layout is
 * this operator flying this vehicle with?</em> (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md wave C2).
 *
 * <p>A function rather than a service dependency, deliberately. {@link DefaultManualControlService}
 * already carries three collaborators plus three watchdog knobs, and it does not need the profile
 * <em>service</em> — it cannot create, rename or delete a profile and never should. It needs exactly
 * this one resolution, so that is what it takes (java-clean-code §1/§3: an interface earns its place
 * by being genuinely substituted, and a constructor that keeps growing is a class doing too much).
 *
 * <p>{@link DefaultControlProfileService#activeFor} implements this signature exactly, so production
 * wiring is a method reference and nothing else.
 */
@FunctionalInterface
public interface ControlProfileSelector {

    /**
     * The layout to engage a session with.
     *
     * @param actor the operator taking control
     * @param kind  the vehicle kind, as the link is reporting it <em>right now</em> — never stored
     *              configuration (VEHICLE-CONTROL-PROFILES-CONTEXT.md P9)
     * @return the profile to use; implementations must never return {@code null}, because a session
     *         with no channel map has no safe behaviour to fall back to
     */
    ControlProfile forSession(UserId actor, VehicleKind kind);

    /**
     * The selector that knows nothing about saved profiles and always answers with the platform's
     * own built-in for the kind.
     *
     * <p>This is the behaviour every manual-control session had before saved profiles existed, and
     * it stays the documented default of the constructors that do not take a selector — so a test
     * or a wiring that does not care about profiles gets the old, total behaviour rather than a
     * null.
     *
     * @return a selector over {@link ControlProfile#forKind}
     */
    static ControlProfileSelector builtInOnly() {
        return (actor, kind) -> ControlProfile.forKind(kind);
    }
}
