package ru.onlychill.mbondsfx.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lightweight package and dependency checks for core production sources. */
final class ArchitectureContractTests {
    private static final String CORE = "ru.onlychill.mbondsfx.core";
    private static final String APPLICATION = "ru.onlychill.mbondsfx.application";
    private static final String INTEGRATIONS = "ru.onlychill.mbondsfx.integrations";
    private static final String ID = CORE + ".id";
    private static final String MONEY = CORE + ".money";
    private static final String BOND = CORE + ".bond";
    private static final String CALCULATION = CORE + ".bond.calculation";
    private static final String LEDGER = CORE + ".ledger";
    private static final List<String> MODULES = List.of(ID, MONEY, BOND, CALCULATION, LEDGER).stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();
    private static final Set<String> DOMAIN_LEAF_MODULES = Set.of(ID, MONEY);
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.]+(?:\\.\\*)?)\\s*;");
    @Test
    void packageDependencyMapAndLegacyBoundaries() {
        assertTrue(isAllowedDependency(BOND, ID), "bond may depend on identifiers");
        assertFalse(isAllowedDependency(BOND, CALCULATION), "bond cannot depend on calculation");
        assertEquals(LEDGER, moduleFor(LEDGER + ".LedgerState"),
                "ledger state recovery remains owned by core.ledger");
        assertEquals(CALCULATION, moduleFor(CALCULATION + ".port"),
                "calculation port uses the longest package match");
        assertFalse(isAllowedDependency(BOND, moduleFor(CALCULATION + ".port")),
                "bond cannot depend on calculation port");
        assertNull(moduleFor(CORE + ".index"), "legacy core.index package is rejected");
        assertFalse(isCoreImportAllowed(INTEGRATIONS + ".index"), "integration imports are forbidden in core");
        assertFalse(isCoreImportAllowed(APPLICATION + ".port"), "application imports are forbidden in core");
        assertFalse(isCoreImportAllowed("org.w3c.dom"), "XML imports are forbidden in core");
        assertFalse(isCoreImportAllowed("java.net.http"), "HTTP imports are forbidden in core");
        assertNull(moduleFor(CORE + ".port"), "unknown legacy core package is rejected");
    }

    @Test
    void productionSourcesRespectPackageAndImportBoundaries() throws IOException {
        String sourceDirectory = System.getProperty("coreMainSourceDir");
        if (sourceDirectory == null || sourceDirectory.isBlank()) {
            throw new IllegalStateException("Gradle must set coreMainSourceDir for architecture checks");
        }
        Path sourceRoot = Path.of(sourceDirectory);
        if (!Files.isDirectory(sourceRoot)) {
            throw new IllegalStateException("Core source directory does not exist: " + sourceRoot);
        }

        List<Path> sources;
        try (var files = Files.walk(sourceRoot)) {
            sources = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("module-info.java"))
                    .sorted()
                    .toList();
        }
        assertFalse(sources.isEmpty(), "core production source directory is not empty");
        for (Path source : sources) {
            String contents = Files.readString(source);
            String sourcePackage = firstGroup(PACKAGE, contents, source, "package declaration");
            String sourceModule = moduleFor(sourcePackage);
            assertNotNull(sourceModule, source + " is in an allowed core package");

            Matcher imports = IMPORT.matcher(contents);
            while (imports.find()) {
                String importedName = imports.group(1);
                String importPackage = importedName.endsWith(".*")
                        ? importedName.substring(0, importedName.length() - 2)
                        : importedName;
                assertTrue(isCoreImportAllowed(importPackage),
                        source + " does not import integration or HTTP/XML infrastructure " + importPackage);

                if (importPackage.startsWith(CORE + ".")) {
                    String targetModule = moduleFor(importPackage);
                    assertNotNull(targetModule,
                            source + " does not import unknown core package " + importPackage);
                    assertTrue(isAllowedDependency(sourceModule, targetModule),
                            source + " may depend on " + targetModule + " from " + sourceModule);
                }
            }
        }
    }

    private static String firstGroup(Pattern pattern, String contents, Path source, String description) {
        Matcher matcher = pattern.matcher(contents);
        assertTrue(matcher.find(), source + " is missing " + description);
        return matcher.group(1);
    }

    private static boolean isAllowedDependency(String sourceModule, String targetModule) {
        if (sourceModule == null || targetModule == null) {
            return false;
        }
        if (sourceModule.equals(targetModule)) {
            return true;
        }
        if (BOND.equals(sourceModule)) {
            return DOMAIN_LEAF_MODULES.contains(targetModule);
        }
        if (CALCULATION.equals(sourceModule)) {
            return Set.of(BOND, ID, MONEY).contains(targetModule);
        }
        if (LEDGER.equals(sourceModule)) {
            return Set.of(ID, MONEY).contains(targetModule);
        }
        return false;
    }

    private static String moduleFor(String packageName) {
        return MODULES.stream()
                .filter(module -> packageName.equals(module) || packageName.startsWith(module + "."))
                .findFirst()
                .orElse(null);
    }

    private static boolean isIntegrationPackage(String packageName) {
        return packageName.equals(INTEGRATIONS) || packageName.startsWith(INTEGRATIONS + ".");
    }

    private static boolean isCoreImportAllowed(String packageName) {
        return !isIntegrationPackage(packageName) && !isApplicationPackage(packageName)
                && !isForbiddenInfrastructureImport(packageName);
    }

    private static boolean isApplicationPackage(String packageName) {
        return packageName.equals(APPLICATION) || packageName.startsWith(APPLICATION + ".");
    }

    private static boolean isForbiddenInfrastructureImport(String packageName) {
        return packageName.equals("java.net.http") || packageName.startsWith("java.net.http.")
                || packageName.equals("javax.xml") || packageName.startsWith("javax.xml.")
                || packageName.equals("org.w3c.dom") || packageName.startsWith("org.w3c.dom.")
                || packageName.equals("org.xml.sax") || packageName.startsWith("org.xml.sax.")
                || packageName.equals("com.sun.net.httpserver")
                || packageName.startsWith("com.sun.net.httpserver.");
    }
}
