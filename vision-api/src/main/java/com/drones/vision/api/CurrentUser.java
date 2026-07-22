package com.drones.vision.api;

import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.UserId;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Resolves who is acting on the current request — the one place in the system where
 * authentication is resolved.
 *
 * <p>Controllers ask this for a {@link UserId}/{@link Ownership} and pass the answer down as a
 * method argument, so services never see a token, a header, or Spring Security; they receive a
 * plain value. When authentication lands (ARCHITECTURE.md §6) this class starts reading the
 * request's JWT claims and nothing downstream changes.
 *
 * <p>Until then it answers with the dev principal injected from {@code vision-app}, which is the
 * single thing to replace.
 */
@Component
public class CurrentUser {

    private final Ownership fallback;

    public CurrentUser(Ownership fallback) {
        this.fallback = Objects.requireNonNull(fallback, "fallback must not be null");
    }

    /**
     * The user every change made on this request is attributed to.
     *
     * @return the acting user's id; never {@code null}
     */
    public UserId userId() {
        return fallback.ownerId();
    }

    /**
     * The scope anything created on this request belongs to.
     *
     * @return the acting user's ownership; never {@code null}
     */
    public Ownership ownership() {
        return fallback;
    }
}
