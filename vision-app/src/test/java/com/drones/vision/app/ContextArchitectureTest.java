package com.drones.vision.app;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Freezes the bounded-context graph (docs/plans/active/DOMAIN-SEPARATION-W1.md §4).
 *
 * <p>Since W1.5a/W1.5b every context owns one package tree, {@code com.drones.vision.<context>..},
 * holding both its domain and its application layer. That makes this the <b>module</b> graph: what
 * this test measures is exactly what Maven will have to express in W1.6, so a cycle here is a
 * blocker, not a smell. Earlier revisions checked the two layers separately and could not see an
 * edge that crossed both — warehouse's application reaching perception's domain, say.
 *
 * <p>Two packages are universal rather than contexts: {@code kernel} (pure values, no ports) and,
 * since W1.6a, {@code platform} (the cross-cutting seams — {@code Event}/{@code EventPublisherPort},
 * the {@code Audit*} family, {@code VisibilityScope}/{@code AccessDeniedException} — every context
 * writes to). Both are excluded as origin and target the same way, so a context depending on either
 * never counts as a cross-context edge.
 *
 * <p>Why exact equality rather than a set of "must not depend on" rules: this is a burn-down. It
 * fails in <em>both</em> directions — a new cross-context edge breaks the build, and so does an
 * allowance that no longer matches reality. The second half is what forces {@link #DECLARED_EDGES}
 * to shrink as the debt is paid, instead of accumulating stale exemptions that quietly re-legalize
 * the coupling.
 *
 * <p>ArchUnit reads bytecode, so javadoc-only {@code {@link}} imports do not register here. They
 * were still real work (W1.4), because an unused import must resolve at compile time and would
 * break the moment a context becomes its own module.
 */
class ContextArchitectureTest {

    private static final String VISION_ROOT = "com.drones.vision.";
    private static final String KERNEL = "kernel";
    private static final String PLATFORM = "platform";

    /** The eight bounded contexts, each the root of one future Maven module. */
    private static final Set<String> CONTEXTS = Set.of(
            "identity", "warehouse", "perception", "flight", "map", "learning", "events", "simulation");

    /**
     * Every cross-context dependency that exists today, in either layer.
     *
     * <p>Entries marked DEBT are the ones that must go before W1.6 can extract modules; the rest
     * are ordinary contract calls that will survive as such. See W1 §5 for each fix.
     */
    private static final Set<String> DECLARED_EDGES = new TreeSet<>(Set.of(
            // --- ordinary contract calls: a context using another's published surface ---
            "flight -> warehouse",          // flight commands resolve the asset they act on
            "identity -> warehouse",        // assignment/activity read assets
            "learning -> events",           // capture a training frame from replay (ReplaySources)
            "learning -> perception",       // capture a frame from a live stream
            "learning -> warehouse",
            "map -> identity",              // MapAccessPolicy.Viewer carries a Role
            "map -> perception",
            "perception -> flight",         // UsageTracker opens flight's telemetry ports
            "simulation -> perception",
            "simulation -> warehouse",

            // --- events: the pure downstream reader (W1.6b, docs/plans/active/DOMAIN-SEPARATION-W1.md
            // §15). `ReplayService`/`UsageTimeline` read warehouse's AssetUsage, flight's
            // TelemetryRepositoryPort, and perception's DetectionResult/DetectionQuery/VideoFrame to
            // answer "what happened during this finished flight" — replay and history, never live
            // state. The four events <-> ... cycles W1.6b killed were entirely the god-port
            // LiveUpdatePublisherPort (deleted, split one port per publishing context) and the
            // misfiled DetectionRepositoryPort/DetectionEvent family (moved to perception, their
            // real owner) plus ReplayCaptureSpec (moved to learning, W1 §15 C8) — once those
            // moved, only the sink's own one-way reads were left, and nothing reads back into
            // events except `learning -> events` above (ReplaySources, kept here deliberately: a
            // dataset capture needs the exact frame a finished flight recorded).
            "events -> flight",
            "events -> perception",
            "events -> warehouse",          // AssetUsage moved flight -> warehouse (W1.6c, C9); the read is unchanged

            // --- DEBT C3/C6: warehouse asks perception "is this asset live?", and perception's
            // ports accept a whole warehouse Device ---
            "perception -> warehouse",
            "warehouse -> flight",          // DEBT C9 paid (W1.6c): down to DefaultProbeService -> TelemetrySourcePort
                                             // alone, the probe path W1.6d moves behind a warehouse-owned port
            "warehouse -> perception"));

    /**
     * Module-level cycles that exist today. Every one blocks W1.6 for <em>all</em> contexts, since
     * Maven cannot express a cycle — this set must reach empty before extraction. Exact-match, so
     * paying a cycle off fails the test until the entry is deleted.
     */
    private static final Set<String> DECLARED_CYCLES = new TreeSet<>(Set.of(
            "flight <-> warehouse",
            "perception <-> warehouse"));

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.drones.vision");
    }

    @Test
    void crossContextEdgesMatchTheDeclaredSetExactly() {
        Set<String> observed = observedEdges();

        Set<String> added = new TreeSet<>(observed);
        added.removeAll(DECLARED_EDGES);
        Set<String> removed = new TreeSet<>(DECLARED_EDGES);
        removed.removeAll(observed);

        assertThat(observed)
                .withFailMessage("""
                        Bounded-context graph changed (docs/plans/active/DOMAIN-SEPARATION-W1.md §4).

                          new edges (a context reached into another — fix it, or justify and declare it):
                            %s
                          gone edges (debt was paid — delete it from DECLARED_EDGES):
                            %s
                        """, added.isEmpty() ? "none" : String.join("\n    ", added),
                        removed.isEmpty() ? "none" : String.join("\n    ", removed))
                .isEqualTo(DECLARED_EDGES);
    }

    /**
     * Maven cannot express a cycle, so one mutual pair blocks extraction for every context at once.
     * Held against {@link #DECLARED_CYCLES} rather than asserted empty, because two exist today —
     * the honest state, tracked as a burn-down instead of hidden behind a disabled test. Down from
     * six before W1.6b (docs/plans/active/DOMAIN-SEPARATION-W1.md §15): the four `events <-> ...`
     * cycles are gone now that `events` is a pure downstream reader — see {@link #DECLARED_EDGES}'s
     * own comment for what killed them. The remaining two are still design debt, but W1.6c
     * (docs/plans/active/DOMAIN-SEPARATION-W1.md §15) paid one of their two causes: `flight <->
     * warehouse` used to be half C9 (`AssetUsage`'s split ownership) and half `DefaultProbeService
     * -> TelemetrySourcePort`; moving `AssetUsage` to warehouse paid C9, so what is left of that
     * cycle is `DefaultProbeService` alone. Both cycles are W1.6d's job: `perception <-> warehouse`
     * is C3/C6 ("warehouse asks runtime state"), `flight <-> warehouse` is the probe path — neither
     * has moved yet.
     */
    @Test
    void moduleCyclesAreOnlyTheKnownOnes() {
        Set<String> edges = observedEdges();
        Set<String> mutual = new TreeSet<>();
        for (String edge : edges) {
            String[] pair = edge.split(" -> ");
            if (edges.contains(pair[1] + " -> " + pair[0])) {
                mutual.add(pair[0].compareTo(pair[1]) < 0
                        ? pair[0] + " <-> " + pair[1]
                        : pair[1] + " <-> " + pair[0]);
            }
        }
        assertThat(mutual)
                .withFailMessage("""
                        Module cycles changed. Maven cannot express a cycle, so W1.6 needs this empty.
                          now: %s
                          declared: %s
                        """, mutual, DECLARED_CYCLES)
                .isEqualTo(DECLARED_CYCLES);
    }

    /**
     * The shared kernel is the one package every context may depend on, which only holds while it
     * depends on none of them — a kernel that reached into a context would smuggle that context's
     * edge into all eight at once.
     */
    @Test
    void kernelDependsOnNothingButItselfAndTheJdk() {
        Set<String> leaks = new TreeSet<>();
        for (JavaClass origin : classes) {
            if (!KERNEL.equals(contextOf(origin))) {
                continue;
            }
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                String target = contextOf(dependency.getTargetClass());
                if (target != null && !KERNEL.equals(target)) {
                    leaks.add(origin.getSimpleName() + " -> " + dependency.getTargetClass().getName());
                }
            }
        }
        assertThat(leaks).withFailMessage("Shared kernel reached into a context: %s", leaks).isEmpty();
    }

    /**
     * {@code platform} (W1.6a) is the second universal package, alongside {@code kernel}: every
     * context writes to it (audit trail, events, visibility scope), so it must depend on nothing but
     * the kernel itself. A {@code platform} type that reached into a context — say, back into
     * {@code identity} for {@code Role} — would smuggle that context's coupling into all eight others
     * at once, since everyone already depends on {@code platform}. This is exactly the failure mode
     * that made {@code identity <-> warehouse} a cycle before this wave: {@code VisibilityScope} and
     * {@code AuditTrailPort} sat inside {@code identity} while every other context wrote to them.
     */
    @Test
    void platformDependsOnNothingButTheKernel() {
        Set<String> leaks = new TreeSet<>();
        for (JavaClass origin : classes) {
            if (!PLATFORM.equals(contextOf(origin))) {
                continue;
            }
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                String target = contextOf(dependency.getTargetClass());
                if (target != null && !KERNEL.equals(target) && !PLATFORM.equals(target)) {
                    leaks.add(origin.getSimpleName() + " -> " + dependency.getTargetClass().getName());
                }
            }
        }
        assertThat(leaks).withFailMessage("platform reached into a context: %s", leaks).isEmpty();
    }

    private static Set<String> observedEdges() {
        Set<String> observed = new TreeSet<>();
        for (JavaClass origin : classes) {
            String from = contextOf(origin);
            if (from == null || KERNEL.equals(from) || PLATFORM.equals(from)) {
                continue;
            }
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                String to = contextOf(dependency.getTargetClass());
                if (to != null && !KERNEL.equals(to) && !PLATFORM.equals(to) && !to.equals(from)) {
                    observed.add(from + " -> " + to);
                }
            }
        }
        return observed;
    }

    /**
     * {@code com.drones.vision.<context>..} &rarr; that context; {@code kernel}/{@code platform}
     * answer their own name, since both are universal rather than a context.
     */
    private static String contextOf(JavaClass javaClass) {
        String name = javaClass.getPackageName();
        if (!name.startsWith(VISION_ROOT)) {
            return null;
        }
        String rest = name.substring(VISION_ROOT.length());
        int dot = rest.indexOf('.');
        String head = dot < 0 ? rest : rest.substring(0, dot);
        if (KERNEL.equals(head)) {
            return KERNEL;
        }
        if (PLATFORM.equals(head)) {
            return PLATFORM;
        }
        return CONTEXTS.contains(head) ? head : null;
    }
}
