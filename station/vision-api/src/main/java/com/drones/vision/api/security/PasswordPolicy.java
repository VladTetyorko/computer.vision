package com.drones.vision.api.security;

import com.drones.vision.api.exception.WeakPasswordException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one password-strength rule every password-setting endpoint enforces
 * (docs/plans/active/AUTH-ROLES-PLAN.md §3.5/§3.7, wave B3): a minimum length, no composition rules
 * (no forced mix of upper/lower/digit/symbol — length is the whole policy, deliberately).
 *
 * <p>Configurable via {@code vision.auth.password.min-length} (default {@code 12}) rather than a
 * literal scattered across {@link com.drones.vision.api.controller.BootstrapController}, {@link
 * com.drones.vision.api.controller.AuthPasswordController}, and {@link
 * com.drones.vision.api.controller.UserAdminController} — the three callers that set a password on
 * the wire (CLAUDE.md rule 1: no hardcoded values outside configuration/a named constant).
 *
 * <p>Deliberately not itself an interface (java-clean-code SKILL.md §1): there is exactly one
 * policy and one implementation, and it is never substituted in a test (tests construct it with a
 * fixed length via the package-visible constructor instead of faking an interface).
 */
@Component
public final class PasswordPolicy {

    private final int minLength;

    public PasswordPolicy(@Value("${vision.auth.password.min-length:12}") int minLength) {
        this.minLength = minLength;
    }

    /**
     * Checks {@code rawPassword} against the configured minimum length.
     *
     * @param rawPassword the plaintext password a caller wants to set
     * @throws WeakPasswordException if {@code rawPassword} is {@code null}, blank, or shorter than
     *                                the configured minimum
     */
    public void require(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < minLength) {
            throw new WeakPasswordException(
                    "password must be at least " + minLength + " characters long");
        }
    }
}
