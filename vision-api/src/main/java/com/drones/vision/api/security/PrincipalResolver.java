package com.drones.vision.api.security;

import com.drones.vision.map.application.MapAccessPolicy;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.kernel.UserId;

import java.util.Objects;
import java.util.Set;

/**
 * The seam {@link CurrentUser} delegates to for the acting request's identity — the one place the
 * "who is calling" decision is made, kept in {@code vision-api} so no controller ever touches a
 * token, a header, or Spring Security.
 *
 * <p><strong>Why this interface, not a Spring Security type:</strong> {@code vision-api} is a
 * driving adapter and deliberately carries <em>no</em> dependency on {@code
 * org.springframework.security} (docs/plans/done/U-AUTH-PLAN.md, wave 3). {@code vision-app} — the only module
 * allowed to know about Spring Security — provides the implementation: a fixed dev principal when
 * {@code vision.auth.enabled=false} (unchanged pre-auth behavior), or a resolver reading the
 * authenticated session's principal when {@code true}. Both are resolved per call, so a singleton
 * {@link CurrentUser} still answers correctly for each request.
 */
public interface PrincipalResolver {

    /**
     * The user the current request is attributed to.
     *
     * @return the acting user's id; never {@code null}
     */
    UserId userId();

    /**
     * The scope anything created on the current request belongs to.
     *
     * @return the acting user's ownership; never {@code null}
     */
    Ownership ownership();

    /**
     * What the current request may see — the acting user's resolved {@link VisibilityScope}
     * (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2). Controllers thread this into the scoped read/command
     * methods; when auth is disabled the dev principal resolves to {@link
     * VisibilityScope#unbounded()}, so nothing downstream changes.
     *
     * @return the acting user's visibility scope; never {@code null}
     */
    VisibilityScope scope();

    /**
     * Who the current request is, as the <em>map's</em> authorization model sees it
     * (docs/plans/done/MAP-REWORK-PLAN.md §3/§4): identity + every group whose membership should count toward
     * map visibility + the highest {@link Role} held.
     *
     * <p><strong>Deliberately not derived from {@link #scope()}.</strong> {@link
     * VisibilityScope#includesGroup} is hard-{@code false} for a PILOT's {@code ASSIGNED_ASSETS}
     * scope (it carries asset ids, not groups), which would make every {@code TEAM} layer
     * structurally invisible to the primary FPV-operator persona — the exact trap
     * docs/plans/done/MAP-REWORK-PLAN.md §1 records and {@link MapAccessPolicy}'s own javadoc explains. Map
     * visibility resolves from plain group membership instead.
     *
     * <p><strong>Group-subtree expansion is not re-implemented here.</strong> A manager's subtree is
     * already expanded, once, by {@code DefaultScopeResolver}; implementations are expected to reuse
     * that (a manager's {@code scope().groups()} <em>is</em> the expanded subtree) unioned with the
     * user's own direct memberships, rather than walking the group tree a second time.
     *
     * @return the acting user's map viewer; never {@code null}
     */
    MapAccessPolicy.Viewer viewer();

    /**
     * A resolver that always answers with one fixed {@link Ownership} (and its {@code ownerId} as
     * the acting user), and an {@link VisibilityScope#unbounded()} scope — the shape {@link
     * CurrentUser}'s pre-auth behavior had, kept for tests and for {@code vision-app}'s
     * dev-principal wiring when {@code vision.auth.enabled=false}. An unbounded scope is the
     * slice-2 guardrail: a scoped read given it returns exactly the unscoped result.
     *
     * <p>Its {@link #viewer()} is the matching map-side guardrail: {@link Role#ADMIN} over {@code
     * ownership.groupId()}, which {@link MapAccessPolicy} grants {@code MANAGE} on every layer — so
     * an auth-disabled deployment sees the whole map exactly as it did before layers existed.
     *
     * @param ownership the fixed ownership to answer with; must not be {@code null}
     * @return a resolver returning {@code ownership}, {@code ownership.ownerId()}, an unbounded scope
     *         and an ADMIN viewer
     */
    static PrincipalResolver fixed(Ownership ownership) {
        Objects.requireNonNull(ownership, "ownership must not be null");
        return new PrincipalResolver() {
            @Override
            public UserId userId() {
                return ownership.ownerId();
            }

            @Override
            public Ownership ownership() {
                return ownership;
            }

            @Override
            public VisibilityScope scope() {
                return VisibilityScope.unbounded();
            }

            @Override
            public MapAccessPolicy.Viewer viewer() {
                return new MapAccessPolicy.Viewer(ownership.ownerId(), Set.of(ownership.groupId()), Role.ADMIN);
            }
        };
    }
}
