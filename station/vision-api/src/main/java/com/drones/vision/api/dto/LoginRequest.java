package com.drones.vision.api.dto;

/**
 * Request body for {@code POST /api/auth/login} (docs/plans/done/U-AUTH-PLAN.md, wave 3's frozen wire
 * contract; {@code kiosk} added by docs/plans/active/AUTH-ROLES-PLAN.md §3.5/§3.7, wave B3).
 *
 * <p>Deliberately not shape-validated here: an unknown/blank username or password is a failed
 * login (401), not a 400 — the credential check itself (application-layer {@code AuthService}) is
 * the one arbiter, and it treats blank/unknown/wrong all alike so nothing leaks which field was
 * wrong.
 *
 * <p>{@code kiosk} requests the long-lived session an always-on wall display needs; {@code null}
 * (the field absent) is treated as {@code false} by {@link #kioskRequested()} — see {@link
 * #kioskRequested()}'s own javadoc for why this is the one place {@code null} is read as a default
 * rather than left for a service to interpret.
 *
 * @param username the login handle, any casing
 * @param password the plaintext password
 * @param kiosk    whether a kiosk (long-lived) session is requested; {@code null} means {@code false}
 */
public record LoginRequest(String username, String password, Boolean kiosk) {

    /**
     * {@code kiosk}, defaulted — a plain wire-shape nicety (a JSON body that omits the field
     * deserializes {@code kiosk} to {@code null|Boolean}), not the "null means off" collaborator
     * pattern java-clean-code SKILL.md §3 withdraws: this is a request DTO's own optional field,
     * read once at the boundary, never threaded further as a nullable parameter.
     *
     * @return {@code true} iff {@code kiosk} is non-null and {@code true}
     */
    public boolean kioskRequested() {
        return Boolean.TRUE.equals(kiosk);
    }
}
