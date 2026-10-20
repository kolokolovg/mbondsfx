package ru.onlychill.mbondsfx.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Dependency-free source and Gradle guards for the current six-module topology. */
public final class ApplicationArchitectureContractTests {
    private static final String ROOT = "ru.onlychill.mbondsfx";
    private static final String CORE = ROOT + ".core";
    private static final String APPLICATION = ROOT + ".application";
    private static final String INTEGRATIONS = ROOT + ".integrations";
    private static final String MOEX = INTEGRATIONS + ".moex";
    private static final String CBR = INTEGRATIONS + ".cbr";
    private static final String MEMORY = ROOT + ".persistence.memory";

    private static final Map<String, String> MODULE_NAMES = Map.of(
            "root", ROOT,
            "core", CORE,
            "application", APPLICATION,
            "integrations", INTEGRATIONS,
            "integrations-moex", MOEX,
            "integrations-cbr", CBR,
            "persistence-memory", MEMORY);
    private static final Map<String, List<String>> SOURCE_PROPERTIES = Map.of(
            "root", List.of("rootMainSourceDir"),
            "core", List.of("coreMainSourceDir"),
            "application", List.of("applicationMainSourceDir"),
            "integrations", List.of("integrationsMainSourceDir"),
            "integrations-moex", List.of("moexMainSourceDir"),
            "integrations-cbr", List.of("cbrMainSourceDir"),
            "persistence-memory", List.of("memoryMainSourceDir"));
    private static final Map<String, String> BUILD_PROPERTIES = Map.of(
            "root", "rootBuildFile",
            "core", "coreBuildFile",
            "application", "applicationBuildFile",
            "integrations", "integrationsBuildFile",
            "integrations-moex", "moexBuildFile",
            "integrations-cbr", "cbrBuildFile",
            "persistence-memory", "memoryBuildFile");
    private static final Map<String, Set<String>> OWN_PACKAGE_PREFIXES = Map.of(
            "root", Set.of(ROOT),
            "core", Set.of(CORE + ".bond", CORE + ".id", CORE + ".ledger", CORE + ".money"),
            "application", Set.of(APPLICATION),
            "integrations", Set.of(INTEGRATIONS + ".adapter", INTEGRATIONS + ".index",
                    INTEGRATIONS + ".provider", INTEGRATIONS + ".transport", INTEGRATIONS + ".xml"),
            "integrations-moex", Set.of(MOEX, INTEGRATIONS + ".marketdata"),
            "integrations-cbr", Set.of(CBR),
            "persistence-memory", Set.of(MEMORY));
    private static final Map<String, Set<String>> ALLOWED_PROJECT_DEPENDENCIES = Map.of(
            "root", Set.of("implementation :core", "implementation :application", "implementation :integrations",
                    "runtimeOnly :integrations-moex", "runtimeOnly :integrations-cbr",
                    "runtimeOnly :persistence-memory"),
            "core", Set.of(),
            "application", Set.of("api :core"),
            "integrations", Set.of("api :application"),
            "integrations-moex", Set.of("implementation :integrations"),
            "integrations-cbr", Set.of("implementation :integrations"),
            "persistence-memory", Set.of("implementation :application"));
    private static final Map<String, Set<String>> EXPECTED_EXPORTS = Map.of(
            "root", Set.of(),
            "core", Set.of(CORE + ".bond", CORE + ".bond.calculation", CORE + ".bond.calculation.port",
                    CORE + ".id", CORE + ".ledger", CORE + ".money"),
            "application", Set.of(APPLICATION + ".port", APPLICATION + ".service"),
            "integrations", Set.of(INTEGRATIONS + ".provider",
                    INTEGRATIONS + ".transport to " + CBR + ", " + MOEX,
                    INTEGRATIONS + ".xml to " + CBR + ", " + MOEX,
                    INTEGRATIONS + ".index to " + CBR + ", " + MOEX,
                    INTEGRATIONS + ".adapter to " + CBR + ", " + MOEX),
            "integrations-moex", Set.of(),
            "integrations-cbr", Set.of(),
            "persistence-memory", Set.of());
    private static final Map<String, Set<String>> EXPECTED_USES = Map.of(
            "root", Set.of(INTEGRATIONS + ".provider.BondReferenceDataSourceProvider",
                    INTEGRATIONS + ".provider.IndexValueSourceProvider",
                    APPLICATION + ".port.LedgerRepositoryFactory"),
            "core", Set.of(),
            "application", Set.of(),
            "integrations", Set.of(),
            "integrations-moex", Set.of(),
            "integrations-cbr", Set.of(),
            "persistence-memory", Set.of());
    private static final Map<String, Set<String>> EXPECTED_PROVIDES = Map.of(
            "root", Set.of(),
            "core", Set.of(),
            "application", Set.of(),
            "integrations", Set.of(),
            "integrations-moex", Set.of(INTEGRATIONS + ".provider.BondReferenceDataSourceProvider with "
                    + MOEX + ".internal.MoexProviderFactory"),
            "integrations-cbr", Set.of(INTEGRATIONS + ".provider.IndexValueSourceProvider with "
                    + CBR + ".internal.CbrProviderFactory"),
            "persistence-memory", Set.of(APPLICATION + ".port.LedgerRepositoryFactory with "
                    + MEMORY + ".InMemoryRepositoryFactory"));
    private static final Map<String, Set<String>> EXPECTED_JDK_REQUIRES = Map.of(
            "root", Set.of(),
            "core", Set.of(),
            "application", Set.of(),
            "integrations", Set.of("java.net.http", "java.xml"),
            "integrations-moex", Set.of("java.net.http", "java.xml"),
            "integrations-cbr", Set.of("java.net.http", "java.xml"),
            "persistence-memory", Set.of());

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.]+(?:\\.\\*)?)\\s*;");
    private static final Pattern MODULE = Pattern.compile("(?m)^\\s*module\\s+([\\w.]+)\\s*\\{");
    private static final Pattern REQUIRES = Pattern.compile(
            "(?m)^\\s*requires\\s+(?:(transitive|static)\\s+)?([\\w.]+)\\s*;");
    private static final Pattern EXPORTS = Pattern.compile(
            "(?m)^\\s*exports\\s+([\\w.]+)(?:\\s+to\\s+([^;]+))?\\s*;");
    private static final Pattern USES = Pattern.compile("(?m)^\\s*uses\\s+([\\w.]+)\\s*;");
    private static final Pattern PROVIDES = Pattern.compile(
            "(?m)^\\s*provides\\s+([\\w.]+)\\s+with\\s+([^;]+);");
    private static final Pattern OPENS = Pattern.compile("\\bopens\\s+[\\w.]+(?:\\s+to\\s+[^;]+)?;");
    private static final Pattern PROJECT_DEPENDENCY = Pattern.compile(
            "(?m)^[ \\t]*([A-Za-z][\\w]*)[ \\t]*(?:\\([ \\t]*)?project\\(['\"]:([\\w-]+)['\"]\\)[ \\t]*\\)?");
    private static int checks;

    private ApplicationArchitectureContractTests() {
    }

    public static void main(String[] args) throws IOException {
        negativeFixturesRejectForbiddenEdges();
        for (String module : SOURCE_PROPERTIES.keySet()) {
            scanModuleSources(module);
        }
        for (String module : BUILD_PROPERTIES.keySet()) {
            checkGradleDependencies(module);
        }
        checkModuleDescriptors();
        System.out.println("ApplicationArchitectureContractTests: " + checks + " checks passed");
    }

    private static void negativeFixturesRejectForbiddenEdges() {
        check(!isImportAllowed("core", APPLICATION + ".port.BondReferenceDataSource"),
                "negative fixture: core cannot import application");
        check(!isImportAllowed("application", INTEGRATIONS + ".provider.ProviderContext"),
                "negative fixture: application cannot import integration provider SPI");
        check(!isImportAllowed("application", INTEGRATIONS + ".adapter.StaticFixings"),
                "negative fixture: application cannot import integration adapters");
        check(!isImportAllowed("application", MEMORY + ".InMemoryLedgerRepository"),
                "negative fixture: application cannot import persistence implementation");
        check(!isImportAllowed("application", "com.example.external.Client"),
                "negative fixture: application cannot import non-JDK/non-core dependencies");
        check(!isImportAllowed("integrations-moex", CBR + ".internal.CbrIndexAdapter"),
                "negative fixture: MOEX cannot import the CBR provider implementation");
        check(!isImportAllowed("root", INTEGRATIONS + ".transport.Transport"),
                "negative fixture: root cannot import shared transport internals");
        check(!isImportAllowed("root", INTEGRATIONS + ".marketdata.model.BondMarketData"),
                "negative fixture: root cannot import MOEX raw data");
        check(!isImportAllowed("root", MOEX + ".internal.MoexBondAdapter"),
                "negative fixture: root cannot import MOEX implementation");
        check(!isImportAllowed("root", CBR + ".internal.CbrIndexAdapter"),
                "negative fixture: root cannot import CBR implementation");
        check(!isAllowedProjectDependency("application", "implementation", ":integrations"),
                "negative fixture: application Gradle cannot depend on integrations");
        check(!isAllowedProjectDependency("core", "implementation", ":application"),
                "negative fixture: core Gradle cannot depend on application");
        check(!isAllowedProjectDependency("integrations-moex", "implementation", ":application"),
                "negative fixture: provider Gradle depends directly only on shared integrations");
        check(!isAllowedProjectDependency("root", "implementation", ":integrations-moex"),
                "negative fixture: providers are runtime-only from root");
        check(!isAllowedProjectDependency("root", "runtimeOnly", ":application"),
                "negative fixture: root contracts are compile dependencies, not runtime-only");
        check(!exportsMatch("integrations", Set.of(INTEGRATIONS + ".transport to " + ROOT)),
                "negative fixture: shared implementation exports cannot name the root");
        check(!exportsMatch("integrations-moex", Set.of(MOEX + ".internal")),
                "negative fixture: provider modules export no packages");
        check(!hasNoOpens("module sample { opens sample.internal; }"),
                "negative fixture: JPMS opens are rejected");
    }

    private static void scanModuleSources(String module) throws IOException {
        Path sourceRoot = configuredPath(SOURCE_PROPERTIES.get(module).getFirst());
        if (!Files.isDirectory(sourceRoot)) {
            throw new IllegalStateException("Source directory does not exist for " + module + ": " + sourceRoot);
        }
        List<Path> sources;
        try (var files = Files.walk(sourceRoot)) {
            sources = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("module-info.java"))
                    .sorted()
                    .toList();
        }
        check(!sources.isEmpty(), module + " production source directory is not empty");
        for (Path source : sources) {
            String contents = Files.readString(source);
            Matcher packageMatcher = PACKAGE.matcher(contents);
            boolean hasPackage = packageMatcher.find();
            check(hasPackage, source + " has a package declaration");
            if (hasPackage) {
                check(isOwnedPackage(module, packageMatcher.group(1)),
                        source + " belongs to the " + module + " source packages");
            }

            Matcher imports = IMPORT.matcher(contents);
            while (imports.find()) {
                String importedName = imports.group(1);
                String importedPackage = importedName.endsWith(".*")
                        ? importedName.substring(0, importedName.length() - 2)
                        : importedName;
                check(isImportAllowed(module, importedPackage),
                        source + " may import " + importedPackage + " from " + module);
            }
        }
    }

    private static void checkGradleDependencies(String module) throws IOException {
        Path buildFile = configuredPath(BUILD_PROPERTIES.get(module));
        if (!Files.isRegularFile(buildFile)) {
            throw new IllegalStateException("Gradle build file does not exist for " + module + ": " + buildFile);
        }
        String contents = Files.readString(buildFile);
        Matcher dependencies = PROJECT_DEPENDENCY.matcher(contents);
        Set<String> actual = new java.util.HashSet<>();
        while (dependencies.find()) {
            actual.add(dependencies.group(1) + " :" + dependencies.group(2));
        }
        check(actual.equals(ALLOWED_PROJECT_DEPENDENCIES.get(module)),
                module + " Gradle project dependencies match " + ALLOWED_PROJECT_DEPENDENCIES.get(module)
                        + " (actual: " + actual + ")");
        check(!contents.contains("--add-exports") && !contents.contains("--add-opens"),
                module + " build does not bypass JPMS encapsulation with add-exports/add-opens");
    }

    private static void checkModuleDescriptors() throws IOException {
        for (String module : SOURCE_PROPERTIES.keySet()) {
            Path descriptor = configuredPath(SOURCE_PROPERTIES.get(module).getFirst()).resolve("module-info.java");
            if (!Files.isRegularFile(descriptor)) {
                check(false, module + " module descriptor exists at " + descriptor);
                continue;
            }
            String contents = Files.readString(descriptor);
            Matcher moduleMatcher = MODULE.matcher(contents);
            boolean hasModule = moduleMatcher.find();
            check(hasModule, descriptor + " declares a named module");
            if (hasModule) {
                check(MODULE_NAMES.get(module).equals(moduleMatcher.group(1)),
                        descriptor + " declares module " + MODULE_NAMES.get(module));
            }

            Set<String> actualRequires = matches(REQUIRES, contents, matcher -> {
                String modifier = matcher.group(1);
                return (modifier == null ? "" : modifier + " ") + matcher.group(2);
            });
            Set<String> expectedRequires = gradleRequires(module);
            expectedRequires.addAll(EXPECTED_JDK_REQUIRES.get(module));
            check(actualRequires.equals(expectedRequires),
                    module + " requires clauses align with Gradle and JDK APIs (actual: " + actualRequires
                            + ", expected: " + expectedRequires + ")");

            Set<String> actualExports = matches(EXPORTS, contents, matcher -> {
                String targets = matcher.group(2);
                if (targets == null) {
                    return matcher.group(1);
                }
                List<String> targetModules = java.util.Arrays.stream(targets.split(","))
                        .map(String::trim).sorted().toList();
                return matcher.group(1) + " to " + String.join(", ", targetModules);
            });
            check(exportsMatch(module, actualExports),
                    module + " exports match the exact JPMS boundary (actual: " + actualExports + ")");
            check(matches(USES, contents, matcher -> matcher.group(1)).equals(EXPECTED_USES.get(module)),
                    module + " uses only its declared service contracts");
            Set<String> actualProvides = matches(PROVIDES, contents, matcher -> {
                List<String> providers = java.util.Arrays.stream(matcher.group(2).split(","))
                        .map(String::trim).sorted().toList();
                return matcher.group(1) + " with " + String.join(", ", providers);
            });
            check(actualProvides.equals(EXPECTED_PROVIDES.get(module)),
                    module + " provides only its typed service factory (actual: " + actualProvides + ")");
            check(hasNoOpens(contents), module + " descriptor does not open packages");
        }
    }

    private static Set<String> gradleRequires(String module) {
        Set<String> result = new java.util.HashSet<>();
        for (String dependency : ALLOWED_PROJECT_DEPENDENCIES.get(module)) {
            String[] parts = dependency.split(" ", 2);
            if ("runtimeOnly".equals(parts[0])) {
                continue;
            }
            String targetModule = parts[1].substring(1);
            String requirement = MODULE_NAMES.get(targetModule);
            result.add("api".equals(parts[0]) ? "transitive " + requirement : requirement);
        }
        return result;
    }

    private static Set<String> matches(Pattern pattern, String contents,
            java.util.function.Function<Matcher, String> value) {
        Set<String> result = new java.util.HashSet<>();
        Matcher matcher = pattern.matcher(contents);
        while (matcher.find()) {
            result.add(value.apply(matcher));
        }
        return result;
    }

    private static boolean exportsMatch(String module, Set<String> actualExports) {
        return EXPECTED_EXPORTS.get(module).equals(actualExports);
    }

    private static boolean hasNoOpens(String descriptor) {
        return !OPENS.matcher(descriptor).find();
    }

    private static boolean isImportAllowed(String sourceModule, String importedPackage) {
        if (isJdkPackage(importedPackage) || isOwnedPackage(sourceModule, importedPackage)) {
            return true;
        }
        return switch (sourceModule) {
            case "root" -> isCorePackage(importedPackage) || isApplicationPackage(importedPackage)
                    || isProviderSpiPackage(importedPackage);
            case "core" -> false;
            case "application" -> isCorePackage(importedPackage);
            case "integrations" -> isCorePackage(importedPackage)
                    || isApplicationPackage(importedPackage)
                    || isSharedIntegrationPackage(importedPackage);
            case "integrations-moex", "integrations-cbr" -> isCorePackage(importedPackage)
                    || isApplicationPackage(importedPackage)
                    || isSharedIntegrationPackage(importedPackage);
            case "persistence-memory" -> isCorePackage(importedPackage)
                    || isApplicationPackage(importedPackage);
            default -> false;
        };
    }

    private static boolean isOwnedPackage(String module, String packageName) {
        if ("root".equals(module)) {
            return ROOT.equals(packageName);
        }
        return OWN_PACKAGE_PREFIXES.get(module).stream().anyMatch(prefix -> isWithin(packageName, prefix));
    }

    private static boolean isCorePackage(String packageName) {
        return isWithin(packageName, CORE);
    }

    private static boolean isApplicationPackage(String packageName) {
        return isWithin(packageName, APPLICATION);
    }

    private static boolean isProviderSpiPackage(String packageName) {
        return isWithin(packageName, INTEGRATIONS + ".provider");
    }

    private static boolean isSharedIntegrationPackage(String packageName) {
        return List.of(INTEGRATIONS + ".adapter", INTEGRATIONS + ".index", INTEGRATIONS + ".provider",
                INTEGRATIONS + ".transport", INTEGRATIONS + ".xml")
                .stream().anyMatch(prefix -> isWithin(packageName, prefix));
    }

    private static boolean isJdkPackage(String packageName) {
        return packageName.startsWith("java.") || packageName.startsWith("javax.")
                || isWithin(packageName, "org.w3c.dom") || isWithin(packageName, "org.xml.sax");
    }

    private static boolean isWithin(String packageName, String prefix) {
        return packageName.equals(prefix) || packageName.startsWith(prefix + ".");
    }

    private static boolean isAllowedProjectDependency(String module, String configuration, String target) {
        return ALLOWED_PROJECT_DEPENDENCIES.get(module).contains(configuration + " " + target);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static Path configuredPath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Gradle must set " + property + " for architecture checks");
        }
        return Path.of(value);
    }
}
