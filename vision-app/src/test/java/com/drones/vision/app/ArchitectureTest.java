package com.drones.vision.app;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
        ArchRule rule = noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat()
                .resideOutsideOfPackages("..application..", "..domain..", "java..");
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
}
