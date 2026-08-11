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
            "learning -> flight",
            "learning -> identity",
            "learning -> perception",       // capture a frame from a live stream
            "learning -> warehouse",
            "map -> flight",
            "map -> identity",
            "map -> perception",
            "perception -> flight",         // AnnotatedFrame carries Telemetry for the OSD
            "simulation -> perception",
            "simulation -> warehouse",
            "warehouse -> flight",
            "warehouse -> identity",

            // --- DEBT: the three cross-cutting infrastructure ports (W1 §5 C7) ---
            // EventPublisherPort, LiveUpdatePublisherPort and AuditTrailPort are platform seams
            // every context writes to, yet they sit inside `events`/`identity` and their signatures
            // name Telemetry, DetectionResult, MapEvent, DetectionEvent. That makes those two
            // contexts hubs that both depend on everyone and are depended on by everyone — five of
            // the seven module cycles below are this one problem.
            "events -> flight",
            "events -> learning",
            "events -> map",
            "events -> perception",
            "flight -> events",
            "map -> events",
            "perception -> events",
            "warehouse -> events",
            "learning -> events",
            "flight -> identity",

            // --- DEBT: AssetUsage (flight) vs the usage read services (warehouse), W1 §5 C8 ---
            // A usage is a flight session; its read side was filed under warehouse. Half of
            // flight <-> warehouse is this split ownership, not a real dependency.

            // --- DEBT C3/C6: warehouse asks perception "is this asset live?", and perception's
            // ports accept a whole warehouse Device ---
            "perception -> warehouse",
            "warehouse -> perception"));

    /**
     * Module-level cycles that exist today. Every one blocks W1.6 for <em>all</em> contexts, since
     * Maven cannot express a cycle — this set must reach empty before extraction. Exact-match, so
     * paying a cycle off fails the test until the entry is deleted.
     */
    private static final Set<String> DECLARED_CYCLES = new TreeSet<>(Set.of(
            "events <-> flight",
            "events <-> learning",
            "events <-> map",
            "events <-> perception",
            "flight <-> warehouse",
            "identity <-> warehouse",
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
     * Held against {@link #DECLARED_CYCLES} rather than asserted empty, because seven exist today —
     * the honest state, tracked as a burn-down instead of hidden behind a disabled test.
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

    private static Set<String> observedEdges() {
        Set<String> observed = new TreeSet<>();
        for (JavaClass origin : classes) {
            String from = contextOf(origin);
            if (from == null || KERNEL.equals(from)) {
                continue;
            }
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                String to = contextOf(dependency.getTargetClass());
                if (to != null && !KERNEL.equals(to) && !to.equals(from)) {
                    observed.add(from + " -> " + to);
                }
            }
        }
        return observed;
    }

    /** {@code com.drones.vision.<context>..} &rarr; that context; the kernel answers "kernel". */
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
        return CONTEXTS.contains(head) ? head : null;
    }
}
