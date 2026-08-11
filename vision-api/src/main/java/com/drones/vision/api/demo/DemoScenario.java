package com.drones.vision.api.demo;

import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.application.identity.AssignmentService;
import com.drones.vision.application.scope.VisibilityScope;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.kernel.UserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * One press of the green button: a fleet, a roster, the assignments between them, and the map
 * furniture a demo needs — all of it built out of the platform's own application services
 * ({@link DemoFleet}, {@link DemoPeople}, {@link DemoOperations}, {@link AssignmentService}), never
 * by writing to a repository or reaching around a service.
 *
 * <p>The whole demo package is additive: it introduces no port, changes no existing wiring, and
 * disappears entirely — beans, routes and all — when {@code vision.demo.enabled=false}.
 *
 * <h2>Fault tolerance over atomicity</h2>
 * Every step reports its own failures into {@link DemoSeedReport#problems()} and carries on. A demo
 * that cannot open three video streams because mediamtx is not running should still leave ten
 * assets, ten users and a populated map behind — a rolled-back all-or-nothing seed would be worse
 * for exactly the audience this exists for.
 *
 * <h2>Who the data belongs to</h2>
 * The pressing user, resolved once at the edge through {@link CurrentUser} — assets and marks carry
 * their {@link Ownership}, users and groups are created under their {@link VisibilityScope}, and
 * every gate the underlying services enforce still applies. With {@code vision.auth.enabled=false}
 * that is the dev principal with an unbounded scope, so a local press simply works.
 */
@Component
@ConditionalOnProperty(prefix = "vision.demo", name = "enabled", matchIfMissing = true)
public class DemoScenario {

    private static final Logger log = LoggerFactory.getLogger(DemoScenario.class);

    /** Every third asset gets a second pilot, so the roster shows both 1:1 and shared assignments. */
    private static final int SECOND_PILOT_EVERY = 3;

    private final DemoPeople people;
    private final DemoFleet fleet;
    private final DemoOperations operations;
    private final AssignmentService assignments;
    private final CurrentUser currentUser;

    public DemoScenario(DemoPeople people, DemoFleet fleet, DemoOperations operations,
                        AssignmentService assignments, CurrentUser currentUser) {
        this.people = Objects.requireNonNull(people, "people must not be null");
        this.fleet = Objects.requireNonNull(fleet, "fleet must not be null");
        this.operations = Objects.requireNonNull(operations, "operations must not be null");
        this.assignments = Objects.requireNonNull(assignments, "assignments must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Fills the platform with one demo's worth of data.
     *
     * <p>Synchronous and potentially slow: starting a stream opens a real video source, so a plan
     * asking for several streams can take seconds. Callers should show progress rather than assume
     * an instant response.
     *
     * @param plan how much of each thing to create
     * @return what was created, plus one line per step that failed
     */
    public DemoSeedReport seed(DemoPlan plan) {
        Objects.requireNonNull(plan, "plan must not be null");
        Ownership ownership = currentUser.ownership();
        UserId actor = currentUser.userId();
        VisibilityScope scope = currentUser.scope();
        List<String> problems = new ArrayList<>();

        List<User> roster = people.seed(plan.users(), scope, problems::add);
        List<DemoAsset> created = fleet.seed(plan.assets(), ownership, actor, problems::add);
        int assigned = assign(created, roster, scope, problems::add);
        int zones = operations.seedZones(problems::add);
        int marks = operations.seedMarks(currentUser.viewer(), problems::add);
        int streams = fleet.startStreams(created, plan.startStreams(), problems::add);

        log.warn("Demo data seeded: {} assets, {} users ({} assignments), {} zones, {} marks, {} streams, "
                        + "{} problem(s). Demo users share the DEV-ONLY password '{}' — disable the demo "
                        + "package with vision.demo.enabled=false outside local development.",
                created.size(), roster.size(), assigned, zones, marks, streams, problems.size(),
                DemoPeople.PASSWORD);

        return new DemoSeedReport(created.stream().map(DemoAsset::displayName).toList(),
                roster.stream().map(User::username).toList(), assigned, zones, marks, streams,
                fleet.videosUsed(created), problems);
    }

    /**
     * Hands every asset to a pilot, round-robin, so no roster entry is left without something to
     * fly and every asset has an owner in the assignment view.
     */
    private int assign(List<DemoAsset> created, List<User> roster, VisibilityScope scope,
                       Consumer<String> problems) {
        if (created.isEmpty() || roster.isEmpty()) {
            return 0;
        }
        int granted = 0;
        for (int index = 0; index < created.size(); index++) {
            DemoAsset asset = created.get(index);
            granted += grant(roster.get(index % roster.size()), asset, scope, problems);
            if (index % SECOND_PILOT_EVERY == 0 && roster.size() > 1) {
                granted += grant(roster.get((index + 1) % roster.size()), asset, scope, problems);
            }
        }
        return granted;
    }

    private int grant(User pilot, DemoAsset asset, VisibilityScope scope,
                      Consumer<String> problems) {
        try {
            assignments.assign(pilot.id(), asset.id(), scope);
            return 1;
        } catch (RuntimeException e) {
            problems.accept("assign " + pilot.username() + " to " + asset.displayName() + ": "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            return 0;
        }
    }
}
