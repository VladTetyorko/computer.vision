package com.drones.vision.api.exception;

import com.drones.vision.api.controller.AuthPasswordController;

/**
 * Thrown by {@link AuthPasswordController} when a self-service password change is attempted while
 * {@code vision.auth.enabled=false} — there is no real credential to change, since every request
 * resolves to the fixed dev principal (docs/plans/active/AUTH-ROLES-PLAN.md §3.5, wave B3). Mapped to
 * {@code 409} with code {@code AUTH_DISABLED} by {@link ApiExceptionHandler}.
 */
public class AuthDisabledException extends RuntimeException {

    public AuthDisabledException(String message) {
        super(message);
    }
}
