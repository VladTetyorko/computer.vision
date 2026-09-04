package com.drones.vision.api.exception;

import com.drones.vision.api.security.PasswordPolicy;

/**
 * Thrown by {@link PasswordPolicy#require(String)} when a caller-supplied password does not meet
 * the configured minimum length (docs/plans/active/AUTH-ROLES-PLAN.md §3.5/§3.7, wave B3). Mapped to
 * {@code 400} with code {@code WEAK_PASSWORD} by {@link ApiExceptionHandler}.
 */
public class WeakPasswordException extends RuntimeException {

    public WeakPasswordException(String message) {
        super(message);
    }
}
