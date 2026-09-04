package com.drones.vision.api.exception;

import com.drones.vision.api.controller.BootstrapController;

/**
 * Thrown by {@link BootstrapController} when a bootstrap attempt arrives after the one-way
 * "an admin already exists" latch has closed (docs/plans/active/AUTH-ROLES-PLAN.md §3.5, wave B3).
 * Mapped to {@code 409} with code {@code ALREADY_INITIALIZED} by {@link ApiExceptionHandler}.
 */
public class AlreadyInitializedException extends RuntimeException {

    public AlreadyInitializedException(String message) {
        super(message);
    }
}
