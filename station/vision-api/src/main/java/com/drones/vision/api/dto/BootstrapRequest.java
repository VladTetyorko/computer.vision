package com.drones.vision.api.dto;

/**
 * Request body for {@code POST /api/auth/bootstrap} (docs/plans/active/AUTH-ROLES-PLAN.md §3.5, wave B3) —
 * the very first administrator's own choice of credentials, made once, at first-run setup.
 *
 * <p>Deliberately not shape-validated beyond what {@link
 * com.drones.vision.identity.application.FirstAdminSpec}/{@code User}'s own compact constructors
 * already enforce (blank username/displayName, malformed email) — the same "map shapes, let the
 * domain validate" idiom {@code CreateUserRequest} documents. {@code password} strength is checked
 * separately by {@link com.drones.vision.api.security.PasswordPolicy}.
 *
 * @param username    desired login handle for the first administrator
 * @param displayName human-readable name
 * @param email       contact address
 * @param password    the plaintext password to hash; the operator's own choice
 */
public record BootstrapRequest(String username, String displayName, String email, String password) {
}
