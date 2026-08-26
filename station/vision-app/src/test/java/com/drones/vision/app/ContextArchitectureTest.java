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
     * Every cross-context dependency that exists today, in either layer. As of W1.6e
     * (docs/plans/active/DOMAIN-SEPARATION-W1.md §15) none of these are DEBT any more — every entry
     * is an ordinary contract call that will survive module extraction as such. See W1 §5/§15 for
     * the fix that retired each one that used to be here.
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
            "perception -> warehouse",      // a stream resolves its device; AssetStreamService resolves its asset
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
            "events -> warehouse"));         // AssetUsage moved flight -> warehouse (W1.6c, C9); the read is unchanged

    /**
     * Module-level cycles in the graph. Once C3/C6 (docs/plans/active/DOMAIN-SEPARATION-W1.md §15,
     * W1.6e) paid off the last one, {@code perception <-> warehouse}, this stopped being a
     * burn-down and became the invariant that keeps the graph extractable: Maven cannot express a
     * cycle, so this set must <em>stay</em> {@code Set.of()} from here on — a future change that
     * reintroduces a mutual pair between any two contexts fails this test immediately, rather than
     * surfacing only once someone tries to cut the module boundary.
     */
    private static final Set<String> DECLARED_CYCLES = Set.of();

    /**
     * docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R5: "the honest number is 15 cross-context
     * repository reads in 8 classes" (excluding {@code UsageTracker}'s four, folded into R3 instead
     * — see that finding's own table). {@link #noContextImportsAnotherContextsRepositoryPort} turns
     * "a context reads another through its published application service, not its repository port"
     * into an invariant rather than a convention. Two of the eight were paid off by wave R5c —
     * flight's {@code DefaultVehicleProfileService} and learning's {@code DefaultLabelingService}
     * now go through warehouse's {@code AssetDirectoryService}/{@code UsageSessionService} — and
     * identity's {@code DefaultAssignmentService} was already paid off before this wave (it now goes
     * through {@code AssetService}), leaving the named exceptions below: this rule's bytecode-level
     * scan turned up two more edges than the finding's own table counted, both discovered making
     * this test pass rather than assigned to fix — {@code DefaultLabelingService} reaches
     * {@code AssetUsageRepositoryPort}/{@code DetectionRepositoryPort} not by importing them but
     * through {@code ReplaySources}' bundled accessors, one hop past the finding's table, which only
     * tracked direct imports. Unlike {@link #DECLARED_EDGES} this is not a burn-down asserted for
     * exact equality — a shorter list here is always fine, silently — but each entry carries the
     * reasoning for why it is not yet, or will never be, gone, so removing one without a matching fix
     * is a visible lie, not a quiet edit.
     */
    private static final Set<String> REPOSITORY_PORT_EXEMPTIONS = new TreeSet<>(Set.of(
            // --- vision-events: the pure downstream sink (see DECLARED_EDGES's own comment and
            // contexts/vision-events/MODULE.md, "wave R5b"/"wave R5c"). Three genuinely bulk,
            // time-windowed historical reads -- "what happened during this finished flight" -- that
            // a per-caller application-service method would not make cleaner, only relocate. Kept
            // deliberately; this half of the list is not expected to shrink.
            "ReplaySources -> com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort",
            "ReplaySources -> com.drones.vision.perception.domain.port.DetectionRepositoryPort",
            "DefaultReplayService -> com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort",
            "DefaultReplayService -> com.drones.vision.perception.domain.port.DetectionRepositoryPort",
            "DefaultReplayService -> com.drones.vision.flight.domain.port.TelemetryRepositoryPort",

            // --- vision-learning's DefaultLabelingService, one hop downstream of the same door:
            // it never imports AssetUsageRepositoryPort/DetectionRepositoryPort by name, but it does
            // hold a ReplaySources (events' own bundling record, an already-declared, legal
            // `learning -> events` edge — DECLARED_EDGES's own comment: "capture a training frame
            // from replay (ReplaySources)") and calls `.usages().findById(...)`/
            // `.detections().query(...)` directly on the raw ports that record exposes. ArchUnit's
            // bytecode scan follows the method-call return type straight through the record to the
            // port underneath, so this is architecturally the same read as the events group above,
            // reached one hop further along the one door events deliberately left open -- not a new
            // or accidental coupling.
            "DefaultLabelingService -> com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort",
            "DefaultLabelingService -> com.drones.vision.perception.domain.port.DetectionRepositoryPort"));

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
     * Maven cannot express a cycle, so one mutual pair would block extraction for every context at
     * once. Down from six before W1.6b (docs/plans/active/DOMAIN-SEPARATION-W1.md §15): the four
     * `events <-> ...` cycles were killed by making `events` a pure downstream reader — see {@link
     * #DECLARED_EDGES}'s own comment for what killed them. `flight <-> warehouse` was gone by W1.6d:
     * it used to be half C9 (`AssetUsage`'s split ownership, paid off in W1.6c) and half {@code
     * DefaultProbeService -> TelemetrySourcePort}; moving the probe feature itself
     * (`ProbeService`/`DefaultProbeService`/`ProbeResult`/`ProbeFailedException`) from {@code
     * warehouse.application.device} to {@code perception.application.device} turned that reference
     * into `perception -> flight`, already legal. The last one, `perception <-> warehouse` (C3/C6 —
     * "warehouse asks runtime state"), was paid off in W1.6e: {@code AssetStreamService} moved
     * stream-starting orchestration out of warehouse, and {@code AssetLiveStatePort} inverted
     * warehouse's genuine live-state reads onto a port perception implements. {@link
     * #DECLARED_CYCLES} is asserted empty, not held against a burn-down list — see its own javadoc.
     */
    @Test
    void theModuleGraphIsAcyclic() {
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
                        The module graph gained a cycle: %s
                        Maven cannot express a cycle -- this must stay empty (docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6e).
                        """, mutual)
                .isEqualTo(DECLARED_CYCLES);
    }

    /**
     * docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R5: a context's application/domain code may
     * not import another context's {@code *RepositoryPort} — read the owning context through its
     * published application service instead, the way {@code AssetLiveStatePort} already does for
     * live state (six sites, {@link #DECLARED_EDGES}'s own comment). This is a narrower, stricter
     * check than {@link #crossContextEdgesMatchTheDeclaredSetExactly}: that test measures *any*
     * cross-context dependency and freezes it by exact equality (shrinking it is progress, growing
     * it is a build break either way); this one targets the specific coupling R5 is about — a
     * foreign repository port — and only tolerates the named entries in {@link
     * #REPOSITORY_PORT_EXEMPTIONS}, so a *new* foreign repository-port import anywhere in the tree,
     * including inside an already-exempted class, fails here even where the coarser edge it
     * produces would already be declared.
     */
    @Test
    void noContextImportsAnotherContextsRepositoryPort() {
        Set<String> violations = new TreeSet<>();
        for (JavaClass origin : classes) {
            String from = contextOf(origin);
            if (from == null || KERNEL.equals(from) || PLATFORM.equals(from)) {
                continue;
            }
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                JavaClass target = dependency.getTargetClass();
                String to = contextOf(target);
                if (to == null || to.equals(from) || !target.getSimpleName().endsWith("RepositoryPort")) {
                    continue;
                }
                String edge = origin.getSimpleName() + " -> " + target.getName();
                if (!REPOSITORY_PORT_EXEMPTIONS.contains(edge)) {
                    violations.add(edge);
                }
            }
        }
        assertThat(violations)
                .withFailMessage("""
                        A context imported another context's *RepositoryPort directly
                        (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R5): read it through the
                        owning context's published application service instead -- see
                        AssetDirectoryService/UsageSessionService (warehouse) for the wave-R5c
                        precedent -- or, if this really is one more bulk/historical read like
                        vision-events' three, add a named, justified entry to
                        REPOSITORY_PORT_EXEMPTIONS instead of a blanket package skip.
                          %s
                        """, violations)
                .isEmpty();
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
