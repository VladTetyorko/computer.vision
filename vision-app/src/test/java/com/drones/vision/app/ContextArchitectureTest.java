package com.drones.vision.app;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Freezes the bounded-context graph of the application layer
 * (docs/plans/active/DOMAIN-SEPARATION-W1.md §4, wave W1.1).
 *
 * <p>Why an exact-match assertion rather than a set of "must not depend on" rules: this is a
 * burn-down, not a steady state. Exact equality fails in <em>both</em> directions — a new
 * cross-context edge breaks the build, and so does an allowance that no longer corresponds to a
 * real edge. The second half is what forces {@link #DECLARED_EDGES} to shrink as waves W1.2/W1.3
 * land, instead of accumulating stale exemptions that quietly re-legalize the coupling.
 *
 * <p>Scope is deliberately the application layer only. {@code vision-domain} is still one flat
 * {@code model} package, so it has no package boundary to enforce against; its walls arrive with
 * the package reorganization in W1.5.
 *
 * <p>ArchUnit reads bytecode, so the ~98 javadoc-only {@code {@link}} imports measured in W1 are
 * invisible here and correctly do not count as edges. They still have to go before Maven
 * extraction (W1.4), because an unused import must resolve at compile time.
 */
class ContextArchitectureTest {

    private static final String APPLICATION_ROOT = "com.drones.vision.application.";

    /** Package (first segment under {@code application}) &rarr; bounded context it belongs to. */
    private static final Map<String, String> CONTEXT_OF_PACKAGE = Map.ofEntries(
            Map.entry("identity", "identity"),
            Map.entry("scope", "identity"),
            Map.entry("asset", "warehouse"),
            Map.entry("device", "warehouse"),
            Map.entry("category", "warehouse"),
            Map.entry("discovery", "warehouse"),
            Map.entry("fleet", "warehouse"),
            Map.entry("usage", "warehouse"),
            Map.entry("stream", "perception"),
            Map.entry("pipeline", "perception"),
            Map.entry("flight", "flight"),
            Map.entry("geofence", "flight"),
            Map.entry("map", "map"),
            Map.entry("mark", "map"),
            Map.entry("training", "learning"),
            Map.entry("replay", "events"),
            Map.entry("simulation", "simulation"));

    /**
     * Every cross-context dependency that exists today, as measured in W1 §4.
     *
     * <p>Entries marked DEBT are the cycle {@code warehouse &rarr; perception &rarr; {warehouse,
     * flight} &rarr; warehouse}, which is what currently makes Maven extraction impossible (a
     * module graph cannot contain one). Each has a named fix in W1 §5 and must be deleted from
     * this set by the wave that removes it.
     */
    private static final Set<String> DECLARED_EDGES = new TreeSet<>(Set.of(
            // --- acyclic, expected to survive as contract calls in W2+ ---
            "flight -> identity",
            "flight -> warehouse",
            "learning -> events",
            "learning -> identity",
            "learning -> perception",
            "map -> identity",
            "map -> perception",
            "simulation -> perception",
            "simulation -> warehouse",
            "warehouse -> identity",

            // --- DEBT C3: warehouse asks perception "is this asset live?" (W1.3) ---
            // The last cross-context edge that is a design problem rather than a fact of life:
            // a CRUD context must not read runtime state, which is what stops warehouse from
            // being replicated independently. It no longer blocks extraction — C1/C2 made
            // perception a sink, so the graph is already acyclic.
            "warehouse -> perception"));

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.drones.vision.application");
    }

    @Test
    void crossContextEdgesMatchTheDeclaredSetExactly() {
        Set<String> observed = new TreeSet<>();
        for (JavaClass origin : classes) {
            String from = contextOf(origin);
            if (from == null) {
                continue;
            }
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                String to = contextOf(dependency.getTargetClass());
                if (to != null && !to.equals(from)) {
                    observed.add(from + " -> " + to);
                }
            }
        }

        Set<String> added = new TreeSet<>(observed);
        added.removeAll(DECLARED_EDGES);
        Set<String> removed = new TreeSet<>(DECLARED_EDGES);
        removed.removeAll(observed);

        assertThat(observed)
                .withFailMessage("""
                        Bounded-context graph changed (docs/plans/active/DOMAIN-SEPARATION-W1.md §4).

                          new edges (a context reached into another — fix it, or justify and declare it): %s
                          gone edges (debt was paid — delete it from DECLARED_EDGES): %s
                        """, added.isEmpty() ? "none" : added, removed.isEmpty() ? "none" : removed)
                .isEqualTo(DECLARED_EDGES);
    }

    /**
     * The context graph must stay acyclic: Maven cannot express a cycle, so a single mutual pair
     * would block extraction for every context at once (W1.6). Held at zero since W1.2.
     */
    @Test
    void acyclicContextsDoNotDependOnEachOtherInBothDirections() {
        Set<String> mutual = new TreeSet<>();
        for (String edge : DECLARED_EDGES) {
            String[] pair = edge.split(" -> ");
            if (DECLARED_EDGES.contains(pair[1] + " -> " + pair[0])) {
                // normalize, so one mutual pair is reported once rather than from both ends
                mutual.add(pair[0].compareTo(pair[1]) < 0
                        ? pair[0] + " <-> " + pair[1]
                        : pair[1] + " <-> " + pair[0]);
            }
        }
        assertThat(mutual)
                .withFailMessage("Mutually dependent contexts cannot become Maven modules: %s", mutual)
                .isEmpty();
    }

    private static String contextOf(JavaClass javaClass) {
        String name = javaClass.getPackageName();
        if (!name.startsWith(APPLICATION_ROOT)) {
            return null;
        }
        String rest = name.substring(APPLICATION_ROOT.length());
        int dot = rest.indexOf('.');
        return CONTEXT_OF_PACKAGE.get(dot < 0 ? rest : rest.substring(0, dot));
    }
}
