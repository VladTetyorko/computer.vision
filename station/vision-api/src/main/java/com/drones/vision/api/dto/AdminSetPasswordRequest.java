package com.drones.vision.api.dto;

/**
 * Request body for {@code POST /api/users/{userId}/password} (docs/plans/active/AUTH-ROLES-PLAN.md
 * §3.5, wave B3) — an admin/manager choosing a temporary password on another user's behalf. Always
 * sets {@link com.drones.vision.identity.domain.model.User#mustChangePassword()} on the target,
 * forcing them to pick their own at next login.
 *
 * @param newPassword the new plaintext password to hash and store
 */
public record AdminSetPasswordRequest(String newPassword) {
}
