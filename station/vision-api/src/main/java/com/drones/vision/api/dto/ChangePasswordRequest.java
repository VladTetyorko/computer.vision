package com.drones.vision.api.dto;

/**
 * Request body for {@code POST /api/auth/password} (docs/plans/active/AUTH-ROLES-PLAN.md §3.5, wave B3) —
 * a session's self-service password change, re-confirming its own current password rather than
 * attempting a fresh login.
 *
 * @param currentPassword the caller's claimed current plaintext password
 * @param newPassword     the new plaintext password to hash and store
 */
public record ChangePasswordRequest(String currentPassword, String newPassword) {
}
