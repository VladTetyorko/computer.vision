package com.drones.vision.app;

import com.drones.vision.api.security.OpenByDesign;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every REST handler must answer "who is allowed to do this?" — or say in writing that it does not
 * have to (docs/plans/done/LIVE-SCOPE-PLAN.md §2, W1).
 *
 * <p>This exists because the platform audit found 29 handlers with no authority check of any kind,
 * and not one of them was a decision — they were omissions, in a codebase where authorization is
 * hand-written at every one of its 80 other call sites and therefore has no framework fallback to
 * catch a missing check. A rule that merely asked for an annotation would document the omissions
 * rather than prevent them, so the rule instead requires the handler to actually <em>reach</em>
 * {@code CurrentUser} or an {@code *Access} collaborator through its own call graph.
 *
 * <p>{@link #TEMPORARY_UNSCOPED} is the shrinking ledger of holes the audit found. Each later
 * LIVE-SCOPE wave deletes its own entries; the second assertion below fails on a stale entry, so the
 * ledger cannot quietly outlive the problem it describes.
 */
class EndpointAuthorizationTest {

    /**
     * Handlers known to be unscoped when LIVE-SCOPE W1 landed. Waves W2-W5 empty this.
     *
     * <p>docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R7 (finding A2) resolved the three
     * remaining live-surface entries: {@code HlsProxyController#proxy} now gates on {@code
     * StreamAccess.requireVisible} (a genuine scope check); {@code DeviceProbeController#probe} was
     * annotated {@code @OpenByDesign} (a caller-supplied connection descriptor names no existing
     * asset, so there is nothing to scope); {@code EventController#forStream}/{@code #recent} now
     * gate on {@code StreamAccess}/{@code CurrentUser.scope()}. All four are removed below rather
     * than left as redundant entries.
     */
    private static final Set<String> TEMPORARY_UNSCOPED = new TreeSet<>(Set.of(
            // NOT in LIVE-SCOPE — the other T1 holes, still unowned (see PLATFORM-AUDIT-SCOPE.md)
            "AssetController#telemetry",
            "DemoController#status",
            "DiscoveryController#scan",
            "GeoRegionController#list",
            "GeoRegionController#progress",
            "OnboardingController#probeCandidate",
            "SystemNetworkController#network",
            "SystemStatusController#status",
            "TrainingJobController#job",
            "TrainingJobController#jobs",
            "UsageTimelineController#recording",
            "UsageTimelineController#timeline"
    ));

    private static final String CURRENT_USER = "com.drones.vision.api.security.CurrentUser";
    /**
     * The two seams that answer "may they?": {@code scope()} for assets and {@code viewer()} for the
     * map, which {@code MapAccessPolicy} deliberately keeps separate from {@code VisibilityScope}.
     * {@code userId()}/{@code ownership()} are excluded on purpose — they answer "who is this?",
     * attribution for the audit trail, which every write already passes and which reads exactly like
     * a permission check without being one. Accepting them would have declared device CRUD
     * authorized when the audit had just proven it is not.
     */
    private static final Set<String> AUTHORITY_METHODS = Set.of("scope", "viewer");
    private static final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "org.springframework.web.bind.annotation.RequestMapping",
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.PatchMapping",
            "org.springframework.web.bind.annotation.DeleteMapping");

    private static JavaClasses apiClasses;

    @BeforeAll
    static void importClasses() {
        apiClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.drones.vision.api");
    }

    @Test
    void everyHandlerEitherChecksAuthorityOrSaysWhyItDoesNot() {
        List<String> offenders = new ArrayList<>();
        for (JavaMethod handler : handlers()) {
            String key = key(handler);
            if (TEMPORARY_UNSCOPED.contains(key) || isExempt(handler)) {
                continue;
            }
            if (!reachesAuthorityCheck(handler)) {
                offenders.add(key);
            }
        }
        assertTrue(offenders.isEmpty(),
                () -> "These handlers perform no authority check and are not annotated @OpenByDesign:\n  "
                        + String.join("\n  ", new TreeSet<>(offenders))
                        + "\nEither consult CurrentUser/an *Access collaborator, or annotate with a reason.");
    }

    @Test
    void theTemporaryLedgerHasNoStaleEntries() {
        Set<String> live = new HashSet<>();
        for (JavaMethod handler : handlers()) {
            live.add(key(handler));
        }
        Set<String> stale = new TreeSet<>(TEMPORARY_UNSCOPED);
        stale.removeAll(live);
        assertTrue(stale.isEmpty(),
                () -> "TEMPORARY_UNSCOPED names handlers that no longer exist — delete these entries:\n  "
                        + String.join("\n  ", stale));
    }

    private static List<JavaMethod> handlers() {
        List<JavaMethod> found = new ArrayList<>();
        for (JavaClass type : apiClasses) {
            if (!type.isAnnotatedWith(RestController.class)) {
                continue;
            }
            for (JavaMethod method : type.getMethods()) {
                if (MAPPING_ANNOTATIONS.stream().anyMatch(a -> method.isAnnotatedWith(a))) {
                    found.add(method);
                }
            }
        }
        return found;
    }

    private static boolean isExempt(JavaMethod handler) {
        return handler.isAnnotatedWith(OpenByDesign.class)
                || handler.getOwner().isAnnotatedWith(OpenByDesign.class);
    }

    /**
     * Walks the handler's own call graph (breadth-first, staying inside vision-api) looking for a
     * call to {@code CurrentUser.scope()} or into an {@code *Access} collaborator. Transitive rather
     * than direct, because a controller may legitimately push the check into a private helper.
     */
    private static boolean reachesAuthorityCheck(JavaMethod handler) {
        Deque<JavaMethod> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(handler);
        while (!queue.isEmpty()) {
            JavaMethod current = queue.poll();
            if (!seen.add(current.getFullName())) {
                continue;
            }
            for (JavaMethodCall call : current.getMethodCallsFromSelf()) {
                String owner = call.getTargetOwner().getName();
                boolean authority = (CURRENT_USER.equals(owner) && AUTHORITY_METHODS.contains(call.getName()))
                        || owner.endsWith("Access");
                if (authority) {
                    return true;
                }
                if (owner.startsWith("com.drones.vision.api")) {
                    Optional<JavaMethod> target = call.getTarget().resolveMember();
                    target.ifPresent(queue::add);
                }
            }
        }
        return false;
    }

    private static String key(JavaMethod handler) {
        return handler.getOwner().getSimpleName() + "#" + handler.getName();
    }
}
