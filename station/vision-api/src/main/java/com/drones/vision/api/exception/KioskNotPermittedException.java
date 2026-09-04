package com.drones.vision.api.exception;

import com.drones.vision.api.controller.AuthController;

/**
 * Thrown by {@link AuthController#login} when a login requests a kiosk (long-lived) session for a
 * user whose highest role is not {@code VIEWER} (docs/plans/active/AUTH-ROLES-PLAN.md §3.5/§3.7, wave
 * B3) — a kiosk session is for an always-on wall display, never for an account that can command
 * anything. Mapped to {@code 400} with code {@code KIOSK_NOT_PERMITTED} by {@link ApiExceptionHandler}.
 */
public class KioskNotPermittedException extends RuntimeException {

    public KioskNotPermittedException(String message) {
        super(message);
    }
}
