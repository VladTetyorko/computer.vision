package com.drones.vision.api;

import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.UserId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Resolves who is acting on the current request — the one place in the system where
 * authentication is resolved.
 *
 * <p>Controllers ask this for a {@link UserId}/{@link Ownership} and pass the answer down as a
 * method argument, so services never see a token, a header, or Spring Security; they receive a
 * plain value. The actual "who" decision lives behind {@link PrincipalResolver}, which {@code
 * vision-app} supplies: a fixed dev principal when {@code vision.auth.enabled=false} (identical to
 * the pre-auth behavior — this class simply delegated to a fixed {@code Ownership} before), or a
 * resolver reading the authenticated session when {@code true} (docs/U-AUTH-PLAN.md, wave 3).
 *
 * <p>This is still "the single thing to replace": the seam moved into {@link PrincipalResolver}, so
 * every controller keeps calling {@link #userId()}/{@link #ownership()} unchanged.
 */
@Component
public class CurrentUser {

    private final PrincipalResolver resolver;

    /**
     * Production constructor: delegates to the {@link PrincipalResolver} {@code vision-app} wires
     * in based on {@code vision.auth.enabled}.
     *
     * @param resolver the request-identity seam; must not be {@code null}
     */
    @Autowired
    public CurrentUser(PrincipalResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
    }

    /**
     * Convenience constructor answering with one fixed {@link Ownership} — the pre-auth shape this
     * class had ({@code userId()} was the ownership's {@code ownerId}). Kept so existing controller
     * unit tests can construct a {@code CurrentUser} from a plain {@link Ownership} unchanged.
     *
     * @param fixed the fixed ownership to answer with; must not be {@code null}
     */
    public CurrentUser(Ownership fixed) {
        this(PrincipalResolver.fixed(fixed));
    }

    /**
     * The user every change made on this request is attributed to.
     *
     * @return the acting user's id; never {@code null}
     */
    public UserId userId() {
        return resolver.userId();
    }

    /**
     * The scope anything created on this request belongs to.
     *
     * @return the acting user's ownership; never {@code null}
     */
    public Ownership ownership() {
        return resolver.ownership();
    }
}
