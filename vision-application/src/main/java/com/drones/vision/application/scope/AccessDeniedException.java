package com.drones.vision.application.scope;

import com.drones.vision.application.flight.DefaultFlightCommandService;
import com.drones.vision.application.identity.DefaultAssignmentService;
/**
 * Thrown when the acting user's {@link VisibilityScope} does not permit an operation they attempted
 * on a resource that does exist (docs/U-SCOPE-PLAN.md, U-e slice 2).
 *
 * <p>Deliberately distinct from {@link java.util.NoSuchElementException}: a scoped <em>read</em>
 * that hits an out-of-scope asset throws {@code NoSuchElementException} (a 404) so existence is not
 * revealed, whereas a scoped <em>command/grant</em> throws this (mapped to 403 by {@code vision-api}
 * in a later wave) — for those actions it is more honest to say "you may not do this" than to
 * pretend the asset is not there. See {@link DefaultFlightCommandService} and
 * {@link DefaultAssignmentService} for the two call sites.
 */
public class AccessDeniedException extends RuntimeException {

    /**
     * Creates the exception with a human-readable reason.
     *
     * @param message why access was denied
     */
    public AccessDeniedException(String message) {
        super(message);
    }
}
