package com.drones.vision.app;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.web.bind.annotation.RestController;

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

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.drones.vision");
    }

    @Test
    void domainDependsOnlyOnDomainAndJava() {
        ArchRule rule = noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideOutsideOfPackages("..domain..", "java..");
        rule.check(classes);
    }

    @Test
    void applicationDependsOnlyOnApplicationDomainAndJava() {
        // `javax.imageio..` is admitted alongside `java..`: it is JDK standard library (the
        // `java.desktop` module), the sibling writer of the already-permitted `java.awt.image`
        // (`BufferedImage` is `java..` and allowed) — not a framework or adapter dependency, so the
        // boundary this rule actually protects (no Spring/adapter/external coupling in the use-case
        // layer) is fully preserved. Consumed by `TrainingFrameEncoder` to encode a full-resolution
        // JPEG of a captured training frame (docs/CV-TRAINING-PLAN.md §D); allowing the layer to hold
        // a BufferedImage but not write one was an inconsistent line, not a principled one.
        ArchRule rule = noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat()
                .resideOutsideOfPackages("..application..", "..domain..", "java..", "javax.imageio..");
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
        ArchRule rule = noClasses().that().resideInAnyPackage("..domain..", "..application..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework..");
        rule.check(classes);
    }

    // --- docs/LAYERING-REFACTOR-PLAN.md §7 wave H: package-shape rules ---
    //
    // Note on the plan's fourth proposed rule ("no adapter package may depend on another
    // adapter package"): it is not repeated here because `adaptersDoNotDependOnEachOther`
    // above already expresses exactly that check, generically, via
    // `slices().matching("com.drones.vision.adapter.(*)..")` — it covers every adapter
    // slice (rtsp, mjpeg, mavlink, v4l2, publishhls, overlay, cvgrpc, discovery,
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
}
