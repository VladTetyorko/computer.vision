package com.drones.vision.platform;

/**
 * One verb a caller may hold, independent of what they may <em>see</em> (docs/plans/active/AUTH-ROLES-PLAN.md
 * §3.3). Paired with a {@link VisibilityScope} inside {@link Authority}, which is where the two axes
 * — visibility and authority — are actually combined into an answer.
 *
 * <p>Granting capabilities from a {@link com.drones.vision.identity.domain.model.Role} is org
 * policy and lives in {@code vision-identity}'s {@code application.scope.RoleAuthority}, not here —
 * this enum only names the verbs, the same "the seam is the type, the policy is elsewhere" split
 * {@link AuditAction} already draws for what happened versus who may make it happen.
 *
 * <p>Deliberately <strong>no per-asset command verb</strong> (e.g. "may fly this one aircraft") —
 * that question is answered by {@code AssetAuthority} (docs/plans/active/AUTH-ROLES-PLAN.md §3.9,
 * {@code vision-api}), which narrows a capability by the specific asset and, where relevant, the
 * caller's assignment seat. A capability here is a deployment-wide grant; whether it applies to one
 * particular aircraft is a separate, narrower question this type does not try to answer.
 */
public enum Capability {

    /** May control a stream's camera/payload — CV controls, tracker, per-stream profile. */
    OPERATE_PAYLOAD,

    /** May command flight — arm/disarm/mode/RTL/estop/aux, and the manual-control link. */
    COMMAND_FLIGHT,

    /** May administer a fleet within scope — asset/device/category/geofence lifecycle writes. */
    MANAGE_FLEET,

    /** May manage the organization — users, groups, assignments. */
    MANAGE_ORG
}
