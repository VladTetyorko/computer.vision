package com.drones.vision.app.security;

import com.drones.vision.kernel.GroupId;
import com.drones.vision.identity.domain.model.Membership;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.identity.domain.model.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Spring Security's view of an authenticated {@link User} (docs/plans/done/U-AUTH-PLAN.md, wave 3) — the
 * principal stored in the session's {@code Authentication} when {@code vision.auth.enabled=true}.
 *
 * <p>It carries the whole domain {@link User} plus the derived {@link Ownership} so {@link
 * SecurityContextPrincipalResolver} can answer {@link com.drones.vision.api.CurrentUser} straight
 * from the session with no repository hit.
 *
 * <p><strong>Ownership derivation.</strong> {@code ownerId} is the user's own id. {@code groupId}
 * is the group of the user's highest-privilege membership; a user with <em>no</em> membership yet
 * falls back to a <em>personal group whose id equals the user's own id</em> — a well-known, stable
 * rule (docs/plans/done/U-AUTH-PLAN.md, wave 3) so an unassigned user still has a valid, non-null scope to own
 * assets under until slice 2's real visibility model lands. Roles map to authorities as {@code
 * ROLE_<name>} (e.g. {@code ROLE_ADMIN}).
 */
public final class VisionUserDetails implements UserDetails {

    private final User user;
    private final Ownership ownership;

    public VisionUserDetails(User user) {
        this.user = Objects.requireNonNull(user, "user must not be null");
        this.ownership = ownershipOf(user);
    }

    /** The authenticated domain user. */
    public User user() {
        return user;
    }

    /** The scope this user owns things under (see class javadoc for the derivation rule). */
    public Ownership ownership() {
        return ownership;
    }

    private static Ownership ownershipOf(User user) {
        GroupId group = user.memberships().stream()
                .max(Comparator.comparing(Membership::role))
                .map(Membership::groupId)
                .orElseGet(() -> new GroupId(user.id().value()));
        return new Ownership(user.id(), group);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return user.memberships().stream()
                .map(m -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + m.role().name()))
                .distinct()
                .toList();
    }

    @Override
    public String getPassword() {
        return user.passwordHash();
    }

    @Override
    public String getUsername() {
        return user.username();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return user.enabled();
    }

    /** Authorities as a plain list, for building an authenticated token. */
    public List<GrantedAuthority> authorities() {
        return List.copyOf(getAuthorities());
    }
}
