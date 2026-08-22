package com.drones.vision.app;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.web.bind.annotation.RestController;


import static org.junit.jupiter.api.Assertions.assertFalse;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Enforces the hexagonal dependency rule described in ARCHITECTURE.md §2:
 * {@code vision-domain} &larr; {@code vision-application} &larr; adapters
 * &larr; {@code vision-app}. Nothing points outward, adapters never depend
 * on each other, and the core stays framework-free.
 */
class ArchitectureTest {

    private static JavaClasses classes;

    /**
     * {@code libs/mavlink-core} (docs/plans/active/MAVLINK-CORE-PLAN.md, W1) is a standalone,
     * reusable library, reachable here through a deliberately <b>test-scoped</b> dependency in this
     * module's pom: {@code vision-app} has no runtime need for it, but these rules — above all
     * "mavlink-core never depends on vision" — are what keep it reusable, and they must fail loudly
     * rather than skip. Reading the classes off disk instead would let every one of them pass
     * vacuously whenever the sibling module happened not to be built.
     */
    private static JavaClasses mavlinkCoreClasses;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.drones.vision");
        mavlinkCoreClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.drones.mavlink");
    }

    @Test
    void domainDependsOnlyOnDomainAndJava() {
        // `..kernel..` joins the allowed set with the bounded-context split
        // (docs/plans/active/DOMAIN-SEPARATION-W1.md, W1.5a): the shared kernel — typed ids and pure
        // value objects (GeoPosition, Ownership, StreamDescriptor, GeoProjection) — is domain code
        // that every context's domain may depend on. It sits at `com.drones.vision.kernel` rather
        // than under a `domain` segment precisely because it belongs to no single context, so the
        // `..domain..` pattern cannot match it. Its own contents are still bound by this same rule.
        // `..platform..` joins it for the identical reason, added in W1.6a: the cross-cutting seams
        // (Event/EventPublisherPort, the Audit* family, VisibilityScope/AccessDeniedException) sit at
        // `com.drones.vision.platform`, universal like the kernel and depending on nothing but it.
        ArchRule rule = noClasses().that().resideInAnyPackage("..domain..", "..kernel..", "..platform..")
                .should().dependOnClassesThat()
                .resideOutsideOfPackages("..domain..", "..kernel..", "..platform..", "java..");
        rule.check(classes);
    }

    @Test
    void applicationDependsOnlyOnApplicationDomainAndJava() {
        // `javax.imageio..` is admitted alongside `java..`: it is JDK standard library (the
        // `java.desktop` module), the sibling writer of the already-permitted `java.awt.image`
        // (`BufferedImage` is `java..` and allowed) — not a framework or adapter dependency, so the
        // boundary this rule actually protects (no Spring/adapter/external coupling in the use-case
        // layer) is fully preserved. Consumed by `TrainingFrameEncoder` to encode a full-resolution
        // JPEG of a captured training frame (docs/plans/done/CV-TRAINING-PLAN.md §D); allowing the layer to hold
        // a BufferedImage but not write one was an inconsistent line, not a principled one.
        // `..platform..` joins the allowed set in W1.6a for the same reason `..kernel..` already did.
        ArchRule rule = noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat()
                .resideOutsideOfPackages("..application..", "..domain..", "..kernel..", "..platform..", "java..",
                        "javax.imageio..");
        rule.check(classes);
    }

    @Test
    void adaptersDoNotDependOnEachOther() {
        ArchRule rule = slices().matching("com.drones.vision.adapter.(*)..")
                .should().notDependOnEachOther();
        rule.check(classes);
    }

    @Test
    void onlyAppMayDependOnAdapterPackages() {
        ArchRule rule = noClasses().that().resideOutsideOfPackages("..app..", "..adapter..")
                .should().dependOnClassesThat().resideInAPackage("..adapter..");
        rule.check(classes);
    }

    @Test
    void domainAndApplicationAreSpringAnnotationFree() {
        ArchRule rule = noClasses().that()
                .resideInAnyPackage("..domain..", "..application..", "..kernel..", "..platform..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework..");
        rule.check(classes);
    }

    // --- docs/plans/active/LAYERING-REFACTOR-PLAN.md §7 wave H: package-shape rules ---
    //
    // Note on the plan's fourth proposed rule ("no adapter package may depend on another
    // adapter package"): it is not repeated here because `adaptersDoNotDependOnEachOther`
    // above already expresses exactly that check, generically, via
    // `slices().matching("com.drones.vision.adapter.(*)..")` — it covers every adapter
    // slice (rtsp, mjpeg, mavlink, v4l2, publishhls, cvgrpc, discovery,
    // persistence, simulation) pairwise. Adding a second rule with the same meaning would
    // be redundant, not additive, and that rule is one of the five left unmodified per §6.4.

    @Test
    void restControllersLiveOnlyInApiControllerOrProxyPackage() {
        // `com.drones.vision.api.proxy` is the one documented exception (plan §3's
        // template: "proxy/ pass-through edges that own no application service
        // (HlsProxyController)"). `HlsProxyController` is a genuine `@RestController` that
        // intentionally lives outside `controller/` because it proxies HLS bytes rather
        // than fronting an application service — not a leftover violation.
        ArchRule rule = classes().that().areAnnotatedWith(RestController.class)
                .should().resideInAnyPackage("com.drones.vision.api.controller", "com.drones.vision.api.proxy");
        rule.check(classes);
    }

    @Test
    void configurationPropertiesClassesLiveOnlyInAppConfigPropertiesPackage() {
        ArchRule rule = classes().that().areAnnotatedWith(ConfigurationProperties.class)
                .should().resideInAPackage("com.drones.vision.app.config.properties");
        rule.check(classes);
    }

    @Test
    void applicationHasNoClassesLooseAtItsRootPackage() {
        // Non-recursive on purpose: "com.drones.vision.application" (no trailing "..")
        // matches only classes declared directly in that package, not its feature/pipeline/
        // scope/exception subpackages, which is exactly what Wave A's split is supposed to
        // leave empty.
        ArchRule rule = noClasses().should().resideInAPackage("com.drones.vision.application");
        rule.check(classes);
    }

    @Test
    void onlyAppMayDependOnConfigPropertiesTypes() {
        ArchRule rule = noClasses().that().resideOutsideOfPackages("..app..")
                .should().dependOnClassesThat().resideInAPackage("..app.config.properties..");
        rule.check(classes);
    }

    // --- docs/plans/active/MAVLINK-CORE-PLAN.md wave W1: libs/mavlink-core guards ---

    /**
     * Guards the guards: every rule below is a {@code noClasses()} rule, which passes trivially when
     * nothing was imported. If the test-scoped {@code mavlink-core} dependency is ever dropped, this
     * is the one test that notices.
     */
    @Test
    void mavlinkCoreClassesAreActuallyOnTheTestClasspath() {
        assertFalse(mavlinkCoreClasses.isEmpty(),
                "no com.drones.mavlink classes imported — the test-scoped mavlink-core dependency "
                        + "in vision-app/pom.xml is missing, and every mavlink-core rule below is vacuous");
    }
    //
    // These four rules run against mavlinkCoreClasses (imported straight off disk, see that
    // field's own javadoc), not the classes field every rule above uses. Each skips cleanly if
    // libs/mavlink-core has not been built yet in this working tree, rather than failing.

    @Test
    void mavlinkCoreNeverDependsOnVision() {
        // The single most important rule in this wave: mavlink-core is a reusable library with
        // zero project dependencies (plan D2). The moment it can import a com.drones.vision type,
        // it has stopped being reusable in another project, which is the entire point of the task.
        ArchRule rule = noClasses().that().resideInAPackage("com.drones.mavlink..")
                .should().dependOnClassesThat().resideInAPackage("com.drones.vision..");
        rule.check(mavlinkCoreClasses);
    }

    @Test
    void mavlinkCoreIsSpringFree() {
        ArchRule rule = noClasses().that().resideInAPackage("com.drones.mavlink..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework..");
        rule.check(mavlinkCoreClasses);
    }

    @Test
    void mavlinkCoreTransportDoesNotReachUpTheStack() {
        // Level rule (plan §3.2): a level may only reach the one below it. Transport (L1) sits
        // below codec (L2), which sits below session (L3, W2) and service (L4, W3) -- so transport
        // must never import any of the three.
        ArchRule rule = noClasses().that().resideInAPackage("com.drones.mavlink.transport..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.drones.mavlink.codec..", "com.drones.mavlink.session..", "com.drones.mavlink.service..");
        rule.check(mavlinkCoreClasses);
    }

    @Test
    void onlyCodecDependsOnTheMavlinkLibrary() {
        // Scoped to transport/config rather than a blanket ban: L0 (root package) and config are
        // universal per plan §3.2 and must stay free of the dronefleet dependency, but L3/L4 are
        // expected to keep flowing the library's own payload types per plan D3 -- this wave only
        // builds L0/L1/L2/config, so codec is the only package that can possibly touch dronefleet
        // yet, and this rule pins that rather than widening it by accident later.
        ArchRule rule = noClasses().that().resideInAnyPackage("com.drones.mavlink.transport..", "com.drones.mavlink.config..")
                .should().dependOnClassesThat().resideInAPackage("io.dronefleet.mavlink..");
        rule.check(mavlinkCoreClasses);
    }
}
