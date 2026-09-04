package com.drones.vision.api.demo;

import com.drones.vision.identity.application.GroupService;
import com.drones.vision.identity.application.GroupSpec;
import com.drones.vision.identity.application.UserService;
import com.drones.vision.identity.application.UserSpec;
import com.drones.vision.platform.Authority;
import com.drones.vision.identity.domain.model.Group;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.identity.domain.model.Membership;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.kernel.UserId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The people half of the demo scenario: one squad group and a roster of pilots to fly the demo
 * fleet, created through {@link UserService}/{@link GroupService} exactly as the org-settings page
 * would create them — no repository is touched directly.
 *
 * <p><strong>Dev-only credentials.</strong> Every demo user is created with the same well-known
 * password ({@link #PASSWORD}), the same stance the {@code admin}/{@code admin} account seeded by
 * {@code storage/persistence}'s {@code db/seed/dev} migration takes (docs/plans/done/POSTGRES-ONLY-CONTEXT.md
 * W1, when {@code vision.persistence.seed-dev-users=true}): fine behind {@code
 * vision.auth.enabled=false} on a laptop, never acceptable in a real deployment — which is why the
 * whole demo package is removable with one property.
 *
 * <p>Re-runnable: usernames are de-duplicated against the users that already exist (a second press
 * of the button creates {@code demo.falcon2}, not a 409), and the squad group is reused rather than
 * re-created.
 */
@Component
@ConditionalOnProperty(prefix = "vision.demo", name = "enabled", matchIfMissing = true)
public class DemoPeople {

    /** Group every demo user is placed in; reused across presses. */
    static final String GROUP_NAME = "Demo Squad";

    /** Username prefix that marks an account as demo-created. */
    static final String USERNAME_PREFIX = "demo.";

    /** DEV-ONLY password shared by every demo user — see this class's own javadoc. */
    public static final String PASSWORD = "demo";

    /** Call signs the roster is drawn from, cycled with a numeric suffix once exhausted. */
    private static final List<String> CALL_SIGNS = List.of("Falcon", "Kestrel", "Osprey", "Harrier", "Merlin",
            "Condor", "Raven", "Vulture", "Griffin", "Phoenix");

    /** Every fourth demo user manages the squad; the rest fly it. */
    private static final int MANAGER_EVERY = 4;

    private final UserService users;
    private final GroupService groups;

    public DemoPeople(UserService users, GroupService groups) {
        this.users = Objects.requireNonNull(users, "users must not be null");
        this.groups = Objects.requireNonNull(groups, "groups must not be null");
    }

    /**
     * Creates {@code count} demo users in the demo squad group.
     *
     * <p>Individually fault-tolerant: a user the acting scope may not create (or whose name races
     * another press) is reported through {@code problems} and skipped, never aborting the rest.
     *
     * @param count    how many users to create
     * @param actor    the pressing user's own id, for audit attribution (docs/plans/active/AUTH-ROLES-PLAN.md
     *                 D15, wave B3)
     * @param acting   the pressing user's authority (docs/plans/active/AUTH-ROLES-PLAN.md wave B6,
     *                 superseding the bare {@code VisibilityScope} this took before) — every gate
     *                 {@link UserService}/{@link GroupService} enforces still applies
     * @param problems sink for one human-readable line per user that could not be created
     * @return the created users, in creation order (possibly shorter than {@code count})
     */
    public List<User> seed(int count, UserId actor, Authority acting, Consumer<String> problems) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(acting, "acting must not be null");
        Objects.requireNonNull(problems, "problems must not be null");
        if (count <= 0) {
            return List.of();
        }

        Group squad;
        Set<String> taken;
        try {
            squad = squadGroup(acting);
            taken = users.list(acting.scope()).stream().map(User::username).collect(Collectors.toSet());
        } catch (RuntimeException e) {
            problems.accept("users: " + describe(e));
            return List.of();
        }

        List<User> created = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String callSign = callSign(index);
            String username = freeUsername(callSign, taken);
            taken.add(username);
            Role role = index % MANAGER_EVERY == 0 ? Role.MANAGER : Role.PILOT;
            try {
                created.add(users.create(new UserSpec(username, callSign + " (demo)",
                        username + "@demo.local", PASSWORD, List.of(new Membership(squad.id(), role))), actor,
                        acting));
            } catch (RuntimeException e) {
                problems.accept("user " + username + ": " + describe(e));
            }
        }
        return List.copyOf(created);
    }

    /** The demo squad group, created on first press and reused afterwards. */
    private Group squadGroup(Authority acting) {
        List<Group> existing = groups.list(acting.scope());
        return existing.stream()
                .filter(group -> GROUP_NAME.equalsIgnoreCase(group.name()))
                .findFirst()
                // Parent it under whichever root group already exists (the fixed-id "Root" seeded by
                // storage/persistence's V13__identity_baseline.sql, or created by hand), so a
                // MANAGER-scoped press — which may not create a root group at all — still succeeds.
                .orElseGet(() -> groups.create(new GroupSpec(GROUP_NAME, rootOf(existing)), acting));
    }

    private static GroupId rootOf(List<Group> groups) {
        return groups.stream()
                .filter(group -> group.parentGroupId() == null)
                .map(Group::id)
                .findFirst()
                .orElse(null);
    }

    private static String callSign(int index) {
        String base = CALL_SIGNS.get(index % CALL_SIGNS.size());
        int lap = index / CALL_SIGNS.size();
        return lap == 0 ? base : base + " " + (lap + 1);
    }

    /** {@code demo.falcon}, or {@code demo.falcon2}, {@code demo.falcon3}… when already taken. */
    private static String freeUsername(String callSign, Set<String> taken) {
        String base = USERNAME_PREFIX + callSign.toLowerCase(Locale.ROOT).replace(' ', '-');
        String candidate = base;
        for (int suffix = 2; taken.contains(candidate); suffix++) {
            candidate = base + suffix;
        }
        return candidate;
    }

    private static String describe(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
