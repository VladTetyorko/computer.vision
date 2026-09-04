package com.drones.vision.identity.application.scope;

import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.platform.Capability;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * The one pure function that turns a {@link Role} into the {@link Capability} set it grants
 * (docs/plans/active/AUTH-ROLES-PLAN.md §3.3, wave B1) — org policy, the same reasoning that already
 * keeps {@code DefaultUserService#maxGrantableRole} out of {@code VisibilityScope}: granting
 * authority from a role is this context's business, not the platform seam's. No ports, no state — a
 * static-only utility, deliberately not a service (nothing here needs an interface, per
 * {@code .claude/skills/java-clean-code/SKILL.md} §1).
 *
 * <h2>Table — FROZEN (docs/plans/active/AUTH-ROLES-PLAN.md §3.3)</h2>
 * <table>
 *   <caption>Role &rarr; Capability</caption>
 *   <tr><th>Role</th><th>OPERATE_PAYLOAD</th><th>COMMAND_FLIGHT</th><th>MANAGE_FLEET</th><th>MANAGE_ORG</th></tr>
 *   <tr><td>{@link Role#VIEWER}</td><td>–</td><td>–</td><td>–</td><td>–</td></tr>
 *   <tr><td>{@link Role#PILOT}</td><td>✓</td><td>✓</td><td>–</td><td>–</td></tr>
 *   <tr><td>{@link Role#MANAGER}</td><td>✓</td><td>✓</td><td>✓</td><td>✓</td></tr>
 *   <tr><td>{@link Role#ADMIN}</td><td>✓</td><td>✓</td><td>✓</td><td>✓</td></tr>
 * </table>
 * {@code MANAGER} and {@code ADMIN} are deliberately identical here — they are separated on the
 * <em>scope</em> axis ({@link com.drones.vision.platform.VisibilityScope.Kind#GROUPS} vs. {@code
 * UNBOUNDED}, i.e. {@code canAdminister()}), not by a fifth capability.
 */
public final class RoleAuthority {

    private RoleAuthority() {
    }

    /**
     * The capabilities {@code role} grants, deployment/org-wide (before any per-asset narrowing —
     * see {@link Authority}'s own javadoc for why that narrowing lives elsewhere).
     *
     * @param role the role to look up; must not be {@code null}
     * @return an immutable set of the capabilities this role grants; empty for {@link Role#VIEWER}
     * @see com.drones.vision.platform.Authority Authority — where a {@link ScopeResolver} pairs this
     *      result with a {@link com.drones.vision.platform.VisibilityScope} to answer both axes at once
     */
    public static Set<Capability> capabilitiesOf(Role role) {
        Objects.requireNonNull(role, "role must not be null");
        return switch (role) {
            case VIEWER -> Set.of();
            case PILOT -> Set.copyOf(EnumSet.of(Capability.OPERATE_PAYLOAD, Capability.COMMAND_FLIGHT));
            case MANAGER, ADMIN -> Set.copyOf(EnumSet.allOf(Capability.class));
        };
    }
}
