package com.drones.vision.api;

import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.UserId;

import java.util.Objects;

/**
 * The seam {@link CurrentUser} delegates to for the acting request's identity — the one place the
 * "who is calling" decision is made, kept in {@code vision-api} so no controller ever touches a
 * token, a header, or Spring Security.
 *
 * <p><strong>Why this interface, not a Spring Security type:</strong> {@code vision-api} is a
 * driving adapter and deliberately carries <em>no</em> dependency on {@code
 * org.springframework.security} (docs/U-AUTH-PLAN.md, wave 3). {@code vision-app} — the only module
 * allowed to know about Spring Security — provides the implementation: a fixed dev principal when
 * {@code vision.auth.enabled=false} (unchanged pre-auth behavior), or a resolver reading the
 * authenticated session's principal when {@code true}. Both are resolved per call, so a singleton
 * {@link CurrentUser} still answers correctly for each request.
 */
public interface PrincipalResolver {

    /**
     * The user the current request is attributed to.
     *
     * @return the acting user's id; never {@code null}
     */
    UserId userId();

    /**
     * The scope anything created on the current request belongs to.
     *
     * @return the acting user's ownership; never {@code null}
     */
    Ownership ownership();

    /**
     * A resolver that always answers with one fixed {@link Ownership} (and its {@code ownerId} as
     * the acting user) — the shape {@link CurrentUser}'s pre-auth behavior had, kept for tests and
     * for {@code vision-app}'s dev-principal wiring when {@code vision.auth.enabled=false}.
     *
     * @param ownership the fixed ownership to answer with; must not be {@code null}
     * @return a resolver returning {@code ownership} and {@code ownership.ownerId()}
     */
    static PrincipalResolver fixed(Ownership ownership) {
        Objects.requireNonNull(ownership, "ownership must not be null");
        return new PrincipalResolver() {
            @Override
            public UserId userId() {
                return ownership.ownerId();
            }

            @Override
            public Ownership ownership() {
                return ownership;
            }
        };
    }
}
