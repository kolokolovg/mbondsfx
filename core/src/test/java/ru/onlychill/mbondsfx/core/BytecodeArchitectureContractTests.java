package ru.onlychill.mbondsfx.core;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BytecodeArchitectureContractTests {
    private static final String CORE = "ru.onlychill.mbondsfx.core";
    private static final ArchRule NO_FORBIDDEN_INFRASTRUCTURE = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "ru.onlychill.mbondsfx.application..",
                    "ru.onlychill.mbondsfx.integrations..",
                    "java.net.http..", "com.sun.net.httpserver..",
                    "javax.xml..", "org.w3c.dom..", "org.xml.sax..");

    @Test
    void productionBytecodeRespectsCorePackageBoundaries() {
        JavaClasses classes = importProductionClasses();
        assertFalse(classes.isEmpty(), "Core production class output must not be empty");
        Map<Domain, Set<String>> packages = new EnumMap<>(Domain.class);
        for (Domain domain : Domain.values()) {
            packages.put(domain, new HashSet<>());
        }
        Set<String> names = new HashSet<>();
        for (JavaClass javaClass : classes) {
            names.add(javaClass.getName());
            assertTrue(isWithin(javaClass.getPackageName(), CORE),
                    "Unexpected class outside core output scope: " + javaClass.getName());
            Domain domain = domainForPackage(javaClass.getPackageName());
            assertNotNull(domain, "Unmapped core package: " + javaClass.getPackageName());
            packages.get(domain).add(javaClass.getPackageName());
        }
        assertTrue(names.contains(CORE + ".ledger.Ledger"), "Expected Ledger in production output");
        assertTrue(names.contains(CORE + ".money.Money"), "Expected Money in production output");
        packages.forEach((domain, found) -> assertFalse(found.isEmpty(), "Missing core domain: " + domain));
        assertEquals(Domain.CALCULATION, domainForPackage(CORE + ".bond.calculation.port"));

        NO_FORBIDDEN_INFRASTRUCTURE.check(classes);
        // These allowed directions form a DAG; reverse edges and leaf dependencies are forbidden.
        forbidDependencies(classes, packages.get(Domain.BOND),
                CORE + ".bond.calculation..", CORE + ".ledger..");
        forbidDependencies(classes, packages.get(Domain.CALCULATION), CORE + ".ledger..");
        forbidDependencies(classes, packages.get(Domain.LEDGER), CORE + ".bond..");
        forbidDependencies(classes, packages.get(Domain.ID),
                CORE + ".bond..", CORE + ".ledger..", CORE + ".money..");
        forbidDependencies(classes, packages.get(Domain.MONEY),
                CORE + ".bond..", CORE + ".ledger..", CORE + ".id..");
    }

    @Test
    void forbiddenHttpDependencyIsDetectedFromFixtureBytecode() {
        JavaClasses fixture = new ClassFileImporter().importClasses(HttpClientFixture.class);
        AssertionError violation = assertThrows(AssertionError.class,
                () -> NO_FORBIDDEN_INFRASTRUCTURE.check(fixture));
        assertTrue(violation.getMessage().contains("java.net.http.HttpClient"));
    }

    private static JavaClasses importProductionClasses() {
        String outputDirs = System.getProperty("coreMainClassesDirs");
        assertNotNull(outputDirs, "Gradle must provide coreMainClassesDirs");
        Path[] paths = Arrays.stream(outputDirs.split(Pattern.quote(File.pathSeparator)))
                .filter(path -> !path.isBlank())
                .map(Path::of)
                .peek(path -> assertTrue(Files.isDirectory(path),
                        "Core production class directory does not exist: " + path))
                .toArray(Path[]::new);
        assertTrue(paths.length > 0, "No core production class directories were provided");
        return new ClassFileImporter().importPaths(paths);
    }

    private static void forbidDependencies(JavaClasses classes, Set<String> sourcePackages, String... targets) {
        noClasses().that().resideInAnyPackage(sourcePackages.toArray(String[]::new))
                .should().dependOnClassesThat().resideInAnyPackage(targets)
                .check(classes);
    }

    private static Domain domainForPackage(String packageName) {
        if (isWithin(packageName, CORE + ".bond.calculation")) return Domain.CALCULATION;
        if (isWithin(packageName, CORE + ".bond")) return Domain.BOND;
        if (isWithin(packageName, CORE + ".ledger")) return Domain.LEDGER;
        if (isWithin(packageName, CORE + ".id")) return Domain.ID;
        if (isWithin(packageName, CORE + ".money")) return Domain.MONEY;
        return null;
    }

    private static boolean isWithin(String packageName, String prefix) {
        return packageName.equals(prefix) || packageName.startsWith(prefix + ".");
    }

    private enum Domain { BOND, CALCULATION, LEDGER, ID, MONEY }

    private static final class HttpClientFixture {
        java.net.http.HttpClient client() {
            return null;
        }
    }
}
