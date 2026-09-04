package com.drones.vision.api.dto;

import com.drones.vision.identity.domain.model.Membership;

import java.util.List;

/**
 * Request body for {@code PUT /api/users/{userId}/memberships} (docs/plans/active/AUTH-ROLES-PLAN.md D14,
 * wave B3) — a wholesale replacement of a user's group memberships, not a delta.
 *
 * <p>Reuses {@link CreateUserRequest.MembershipRequest} for the element shape (group id + role name,
 * matched case-insensitively) rather than duplicating the same two-field parse/validate logic a
 * second time.
 *
 * @param memberships the complete new membership set; {@code null} treated as empty (rejected by
 *                     {@code UserService#setMemberships} for a non-unbounded acting scope, same as
 *                     {@code CreateUserRequest})
 */
public record SetMembershipsRequest(List<CreateUserRequest.MembershipRequest> memberships) {

    /**
     * Converts to the domain {@link Membership} list {@code UserService#setMemberships} expects.
     *
     * @return the resolved memberships, empty if none were sent
     */
    public List<Membership> toMemberships() {
        return memberships == null ? List.of()
                : memberships.stream().map(CreateUserRequest.MembershipRequest::toMembership).toList();
    }
}
