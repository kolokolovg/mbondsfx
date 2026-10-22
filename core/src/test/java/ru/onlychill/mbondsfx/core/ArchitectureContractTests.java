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

    // DE-N-10: no standard account dictionary or BondAccountingPolicy leaks into engine sources.
    @Test
    void doubleEntryDictionaryIsOutsideTheEngine() throws IOException {
        String sourceDirectory = System.getProperty("coreMainSourceDir");
        if (sourceDirectory == null || sourceDirectory.isBlank()) {
            throw new IllegalStateException("Gradle must set coreMainSourceDir for architecture checks");
        }
        Path ledger = Path.of(sourceDirectory).resolve("ru/onlychill/mbondsfx/core/ledger");
        List<String> engineFiles = List.of("Ledger.java", "LedgerState.java", "LedgerChart.java",
                "Account.java", "Posting.java");
        List<String> standardCodes = List.of("CASH", "EQUITY", "SECURITIES", "ACCRUED_INTEREST",
                "COUPON_INCOME", "AMORTIZATION_GAIN", "REALIZED_PNL", "TAX_WITHHELD",
                "COMMISSION_EXPENSE");
        for (String file : engineFiles) {
            String source = Files.readString(ledger.resolve(file));
            assertFalse(source.contains("BondAccountingPolicy"), file + " does not depend on bond policy");
            for (String code : standardCodes) {
                assertFalse(Pattern.compile("AccountCode\\s*\\.\\s*of\\s*\\(\\s*\\\""
                                + Pattern.quote(code) + "\\\"").matcher(source).find(),
                        file + " does not hardcode standard account " + code);
            }
        }

        String operations = Files.readString(ledger.resolve("BondOperations.java"));
        assertFalse(Pattern.compile("AccountCode\\s*\\.\\s*of\\s*\\(").matcher(operations).find(),
                "bond operations do not embed account-code literals");
        Pattern securityFactory = Pattern.compile("private\\s+Account\\s+account\\s*\\(\\s*Position\\s+position,"
                + "\\s*AccountCode\\s+code\\s*\\)\\s*\\{\\s*return\\s+new\\s+Account\\("
                + "position\\.id\\(\\)\\.portfolio\\(\\),\\s*code,\\s*Optional\\.of\\("
                + "position\\.id\\(\\)\\.security\\(\\)\\),\\s*currencyOf\\(position\\)\\);\\s*\\}");
        Pattern flatFactory = Pattern.compile("private\\s+static\\s+Account\\s+flat\\s*\\("
                + "\\s*PortfolioId\\s+portfolio,\\s*Currency\\s+currency,\\s*AccountCode\\s+code\\s*\\)"
                + "\\s*\\{\\s*return\\s+new\\s+Account\\(portfolio,\\s*code,\\s*Optional\\.empty\\(\\),"
                + "\\s*currency\\);\\s*\\}");
        assertTrue(securityFactory.matcher(operations).find(),
                "security account construction is contained in the policy-code account factory");
        assertTrue(flatFactory.matcher(operations).find(),
                "flat account construction is contained in the policy-code flat factory");
        assertEquals(2, occurrences(operations, "new Account("),
                "the only BondOperations Account construction sites are those two factories");
        for (String slot : List.of("cash", "equity", "securities", "accruedInterest", "couponIncome",
                "amortizationGain", "realizedPnl", "taxWithheld", "commissionExpense")) {
            assertTrue(operations.contains("policy." + slot + "()"),
                    "operation account mappings source " + slot + " from BondAccountingPolicy");
        }
    }

    private static int occurrences(String source, String sought) {
        int matches = 0;
        int offset = 0;
        while ((offset = source.indexOf(sought, offset)) >= 0) {
            matches++;
            offset += sought.length();
        }
        return matches;
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
