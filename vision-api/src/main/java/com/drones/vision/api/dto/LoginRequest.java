package com.drones.vision.api.dto;

/**
 * Request body for {@code POST /api/auth/login} (docs/U-AUTH-PLAN.md, wave 3's frozen wire
 * contract).
 *
 * <p>Deliberately not shape-validated here: an unknown/blank username or password is a failed
 * login (401), not a 400 — the credential check itself (application-layer {@code AuthService}) is
 * the one arbiter, and it treats blank/unknown/wrong all alike so nothing leaks which field was
 * wrong.
 *
 * @param username the login handle, any casing
 * @param password the plaintext password
 */
public record LoginRequest(String username, String password) {
}
