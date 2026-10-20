package ru.onlychill.mbondsfx;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/** Blackbox JPMS checks against the produced installDist distribution. */
final class ModulePathContract {
    private static final String ROOT_MODULE = "ru.onlychill.mbondsfx";
    private static final String ROOT_MAIN = "ru.onlychill.mbondsfx.Main";
    private static final String CORE_MODULE = "ru.onlychill.mbondsfx.core";
    private static final String APPLICATION_MODULE = "ru.onlychill.mbondsfx.application";
    private static final String INTEGRATIONS_MODULE = "ru.onlychill.mbondsfx.integrations";
    private static final String MOEX_MODULE = "ru.onlychill.mbondsfx.integrations.moex";
    private static final String CBR_MODULE = "ru.onlychill.mbondsfx.integrations.cbr";
    private static final String MEMORY_MODULE = "ru.onlychill.mbondsfx.persistence.memory";
    private static final String BOND_SERVICE =
            "ru.onlychill.mbondsfx.integrations.provider.BondReferenceDataSourceProvider";
    private static final String INDEX_SERVICE =
            "ru.onlychill.mbondsfx.integrations.provider.IndexValueSourceProvider";
    private static final String REPOSITORY_SERVICE =
            "ru.onlychill.mbondsfx.application.port.LedgerRepositoryFactory";
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration COMPILER_TIMEOUT = Duration.ofSeconds(60);

    private final Path projectRoot;
    private final Path installDir;
    private final Path fixtureRoot;
    private final Path logsDir;
    private final Path javaExecutable;
    private final Path javac;
    final Map<String, Path> installedModules;
    private final Map<String, ModuleDescriptor> descriptors;
    final List<Path> installedJars;
    private final Path installLib;
    private final Path startScript;
    final Map<String, String> installedHashesBefore;

    ModulePathContract(Path projectRoot, Path installDir, Path fixtureWorkspace)
            throws IOException, NoSuchAlgorithmException {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.installDir = installDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(this.projectRoot)) {
            throw new IllegalArgumentException("projectRoot is not a directory: " + this.projectRoot);
        }
        if (!Files.isDirectory(this.installDir)) {
            throw new IllegalArgumentException("installDir is not a directory: " + this.installDir);
        }
        Path javaHome = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
        this.javaExecutable = executable(javaHome.resolve("bin").resolve(toolName("java")));
        this.javac = executable(javaHome.resolve("bin").resolve(toolName("javac")));
        this.fixtureRoot = fixtureWorkspace.toAbsolutePath().normalize().resolve("modulepath-fixtures");
        Files.createDirectories(fixtureRoot);
        this.logsDir = fixtureRoot.resolve("logs");
        Files.createDirectories(logsDir);

        this.installLib = this.installDir.resolve("lib");
        if (!Files.isDirectory(installLib)) {
            throw new IllegalArgumentException("installDir has no lib directory: " + installLib);
        }
        List<Path> jars;
        try (Stream<Path> children = Files.list(installLib)) {
            jars = children.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        if (jars.isEmpty()) {
            throw new IllegalArgumentException("installDir/lib contains no installed jars: " + installLib);
        }
        this.installedJars = List.copyOf(jars);
        this.installedHashesBefore = hashInstalledJars();
        Map<String, Path> modulesByName = new HashMap<>();
        Map<String, ModuleDescriptor> descriptorsByName = new HashMap<>();
        for (Path jar : installedJars) {
            Set<ModuleReference> references = ModuleFinder.of(jar).findAll();
            if (references.size() != 1) {
                throw new IllegalStateException("Installed jar is not one explicit named module: " + jar);
            }
            ModuleDescriptor descriptor = references.iterator().next().descriptor();
            Path prior = modulesByName.put(descriptor.name(), jar);
            if (prior != null) {
                throw new IllegalStateException("Duplicate installed module " + descriptor.name()
                        + " in " + prior + " and " + jar);
            }
            descriptorsByName.put(descriptor.name(), descriptor);
        }
        this.installedModules = Map.copyOf(modulesByName);
        this.descriptors = Map.copyOf(descriptorsByName);
        for (String expected : List.of(ROOT_MODULE, CORE_MODULE, APPLICATION_MODULE, INTEGRATIONS_MODULE,
                MOEX_MODULE, CBR_MODULE, MEMORY_MODULE)) {
            if (!installedModules.containsKey(expected)) {
                throw new IllegalStateException("Missing expected installed module: " + expected);
            }
        }
        this.startScript = findStartScript(this.installDir.resolve("bin"));
    }

    void inspectProductionModules() {
        ModuleDescriptor root = descriptors.get(ROOT_MODULE);
        for (String service : List.of(BOND_SERVICE, INDEX_SERVICE, REPOSITORY_SERVICE)) {
            if (!root.uses().contains(service)) {
                throw new AssertionError("Root descriptor does not use service " + service);
            }
        }
        assertProviderModule(BOND_SERVICE, MOEX_MODULE);
        assertProviderModule(INDEX_SERVICE, CBR_MODULE);
        assertProviderModule(REPOSITORY_SERVICE, MEMORY_MODULE);
    }

    private void assertProviderModule(String service, String expectedModule) {
        List<String> providers = descriptors.values().stream()
                .filter(descriptor -> descriptor.provides().stream().anyMatch(p -> p.service().equals(service)))
                .map(ModuleDescriptor::name)
                .sorted()
                .toList();
        if (!providers.equals(List.of(expectedModule))) {
            throw new AssertionError("Expected only " + expectedModule + " to provide " + service
                    + "; found " + providers);
        }
        ModuleDescriptor provider = descriptors.get(expectedModule);
        if (!provider.exports().isEmpty()) {
            throw new AssertionError("Provider module exports implementation packages: " + expectedModule
                    + " -> " + provider.exports());
        }
    }

    void assertInstalledScriptProfile(String label, List<String> args, String expected) throws Exception {
        List<String> command = new ArrayList<>();
        if (isWindows()) {
            command.addAll(List.of("cmd.exe", "/c", startScript.toString()));
        } else {
            command.add(startScript.toString());
        }
        command.addAll(args);
        ProcessResult result = runProcess(label, command, PROCESS_TIMEOUT);
        assertProfileOutput(result, expected);
    }

    void assertModuleProfile(String label, List<Path> modulePath, List<String> args,
                                     String expected, boolean includeAllModules) throws Exception {
        ProcessResult result = runProcess(label, javaModuleCommand(modulePath, args, includeAllModules),
                PROCESS_TIMEOUT);
        assertProfileOutput(result, expected);
    }

    private List<String> javaModuleCommand(List<Path> modulePath, List<String> appArgs,
                                           boolean includeAllModules) {
        List<String> command = new ArrayList<>(List.of(javaExecutable.toString(), "--module-path",
                joinPaths(modulePath)));
        if (includeAllModules) {
            command.addAll(List.of("--add-modules", "ALL-MODULE-PATH"));
        }
        command.addAll(List.of("--module", ROOT_MODULE + "/" + ROOT_MAIN));
        command.addAll(appArgs);
        return command;
    }

    private void assertProfileOutput(ProcessResult result, String expectedLine) {
        assertSuccess(result);
        List<String> profileLines = result.output().lines()
                .map(String::trim)
                .filter(line -> line.startsWith("profile="))
                .toList();
        if (!profileLines.equals(List.of(expectedLine))) {
            throw new AssertionError("Expected exactly one deterministic profile line " + expectedLine
                    + " from " + result.label() + "; got " + profileLines + ". Full diagnostics:\n"
                    + result.output());
        }
    }

    void assertMissingProvider(String label, String moduleToRemove, List<String> args,
                                       String id, String capability) throws Exception {
        List<Path> reducedPath = new ArrayList<>(installedJars);
        reducedPath.remove(installedModules.get(moduleToRemove));
        ProcessResult result = runProcess(label, javaModuleCommand(reducedPath, args, false), PROCESS_TIMEOUT);
        assertFailure(result, id, capability,
                List.of("missing", "no provider", "no " + capability + " provider", "not found",
                        "unavailable", "unknown"));
    }

    Path compileBondProvider(String moduleName, String packageName, String className,
                                     String providerId) throws Exception {
        String moduleInfo = "module " + moduleName + " {\n"
                + "    requires " + INTEGRATIONS_MODULE + ";\n"
                + "    requires " + APPLICATION_MODULE + ";\n"
                + "    provides " + BOND_SERVICE + " with " + packageName + "." + className + ";\n"
                + "}\n";
        String source = "package " + packageName + ";\n"
                + "import java.util.Optional;\n"
                + "import ru.onlychill.mbondsfx.application.port.BondReferenceDataSource;\n"
                + "import ru.onlychill.mbondsfx.integrations.provider.BondReferenceDataSourceProvider;\n"
                + "import ru.onlychill.mbondsfx.integrations.provider.ProviderContext;\n"
                + "public final class " + className + " implements BondReferenceDataSourceProvider {\n"
                + "  public " + className + "() {}\n"
                + "  public String providerId() { return \"" + providerId + "\"; }\n"
                + "  public BondReferenceDataSource create(ProviderContext context) { return security -> Optional.empty(); }\n"
                + "}\n";
        return compileModule(moduleName, packageName, className, moduleInfo, source,
                contractModulePath(), Set.of(INTEGRATIONS_MODULE, APPLICATION_MODULE), Set.of(BOND_SERVICE));
    }

    private Path compileModule(String moduleName, String packageName, String className,
                               String moduleInfo, String source, List<Path> modulePath,
                               Set<String> expectedRequires,
                               Set<String> expectedServices) throws Exception {
        Path moduleRoot = fixtureRoot.resolve("sources").resolve(moduleName.replace('.', '-'));
        Path sourceRoot = moduleRoot.resolve("src");
        Path moduleInfoPath = sourceRoot.resolve("module-info.java");
        Path javaSourcePath = sourceRoot.resolve(packageName.replace('.', '/')).resolve(className + ".java");
        Path output = moduleRoot.resolve("classes");
        Path jar = moduleRoot.resolve(moduleName + ".jar");
        Files.createDirectories(javaSourcePath.getParent());
        Files.createDirectories(output);
        Files.writeString(moduleInfoPath, moduleInfo);
        Files.writeString(javaSourcePath, source);
        ProcessResult compilation = runProcess("compile-" + moduleName,
                List.of(javac.toString(), "--release", "21", "--module-path", joinPaths(modulePath),
                        "-d", output.toString(), moduleInfoPath.toString(), javaSourcePath.toString()),
                COMPILER_TIMEOUT);
        assertSuccess(compilation);
        writeJar(output, jar);
        ModuleDescriptor descriptor = descriptorOf(jar);
        if (!descriptor.name().equals(moduleName)) {
            throw new AssertionError("Dynamic module name mismatch: " + descriptor.name());
        }
        if (!descriptor.exports().isEmpty()) {
            throw new AssertionError("Dynamic fixture module must not export implementation packages: "
                    + descriptor.exports());
        }
        Set<String> actualRequires = descriptor.requires().stream()
                .map(ModuleDescriptor.Requires::name)
                .filter(name -> !name.equals("java.base"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!actualRequires.equals(expectedRequires)) {
            throw new AssertionError("Dynamic provider must require only stable contract modules; got "
                    + actualRequires);
        }
        Set<String> actualServices = descriptor.provides().stream()
                .map(ModuleDescriptor.Provides::service)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!actualServices.equals(expectedServices)) {
            throw new AssertionError("Unexpected dynamic services: " + actualServices);
        }
        return jar;
    }

    private List<Path> contractModulePath() {
        return List.of(installedModules.get(CORE_MODULE), installedModules.get(APPLICATION_MODULE),
                installedModules.get(INTEGRATIONS_MODULE));
    }

    void assertDynamicProviderModule(Path jar, String expectedModule, String service) {
        ModuleDescriptor descriptor = descriptorOf(jar);
        if (!descriptor.name().equals(expectedModule)
                || descriptor.exports().size() != 0
                || descriptor.provides().stream().noneMatch(p -> p.service().equals(service))) {
            throw new AssertionError("Unexpected fake provider module descriptor: " + descriptor);
        }
    }

    void assertDuplicateProviderIdRejected() throws Exception {
        Path duplicateProvider = compileBondProvider("fixture.duplicate.moex", "fixture.duplicate.internal",
                "DuplicateBondProvider", "moex");
        assertDynamicProviderModule(duplicateProvider, "fixture.duplicate.moex", BOND_SERVICE);
        List<Path> duplicatePath = append(installedJars, duplicateProvider);
        assertFailure("duplicate-bond-provider-moex", javaModuleCommand(duplicatePath,
                        List.of("--profile=moex", "--bond-provider=moex"), true),
                "moex", "bond", List.of("duplicate"));
    }

    void assertProviderReplacementWorks() throws Exception {
        Map<String, String> protectedHashes = hashModules(Set.of(ROOT_MODULE, CORE_MODULE, APPLICATION_MODULE));
        Path swapProvider = compileBondProvider("fixture.moex.swap", "fixture.swap.internal",
                "SwapBondProvider", "swap");
        assertDynamicProviderModule(swapProvider, "fixture.moex.swap", BOND_SERVICE);
        List<Path> swapPath = new ArrayList<>(installedJars);
        swapPath.remove(installedModules.get(MOEX_MODULE));
        swapPath.add(swapProvider);
        assertModuleProfile("replacement-provider-swap-without-recompile", swapPath,
                List.of("--profile=moex", "--bond-provider=swap"),
                "profile=moex bond=swap index=none repository=memory", true);
        if (!protectedHashes.equals(hashModules(Set.of(ROOT_MODULE, CORE_MODULE, APPLICATION_MODULE)))) {
            throw new AssertionError("Root/core/application jars changed during provider replacement test");
        }
    }

    void assertDeniedImportsCompileFailure() throws Exception {
        String moduleName = "fixture.forbidden.imports";
        String moduleInfo = "module " + moduleName + " {\n"
                + "    requires " + INTEGRATIONS_MODULE + ";\n"
                + "    requires " + MOEX_MODULE + ";\n"
                + "    requires " + CBR_MODULE + ";\n"
                + "}\n";
        String packageName = "fixture.forbidden";
        String className = "ForbiddenImports";
        String source = "package " + packageName + ";\n"
                + "import ru.onlychill.mbondsfx.integrations.xml.SecureXml;\n"
                + "import ru.onlychill.mbondsfx.integrations.moex.internal.MoexProviderFactory;\n"
                + "import ru.onlychill.mbondsfx.integrations.cbr.internal.CbrProviderFactory;\n"
                + "public final class ForbiddenImports {\n"
                + "  Object[] types = { SecureXml.class, MoexProviderFactory.class, CbrProviderFactory.class };\n"
                + "}\n";
        Path moduleRoot = fixtureRoot.resolve("sources/forbidden-imports");
        Path sourceRoot = moduleRoot.resolve("src");
        Path moduleInfoPath = sourceRoot.resolve("module-info.java");
        Path sourcePath = sourceRoot.resolve(packageName.replace('.', '/')).resolve(className + ".java");
        Path output = moduleRoot.resolve("classes");
        Files.createDirectories(sourcePath.getParent());
        Files.createDirectories(output);
        Files.writeString(moduleInfoPath, moduleInfo);
        Files.writeString(sourcePath, source);
        ProcessResult result = runProcess("compile-forbidden-imports",
                List.of(javac.toString(), "--release", "21", "--module-path", joinPaths(installedJars),
                        "-d", output.toString(), moduleInfoPath.toString(), sourcePath.toString()),
                COMPILER_TIMEOUT);
        if (result.exitCode() == 0) {
            throw new AssertionError("External module unexpectedly compiled imports of non-exported packages");
        }
        assertExportDenial(result.output(), "ru.onlychill.mbondsfx.integrations.xml");
        assertExportDenial(result.output(), "ru.onlychill.mbondsfx.integrations.moex.internal");
        assertExportDenial(result.output(), "ru.onlychill.mbondsfx.integrations.cbr.internal");
        String lower = result.output().toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("module not found") || lower.contains("package does not exist")
                || lower.contains("cannot find symbol")) {
            throw new AssertionError("Forbidden-import compile failed for an unrelated reason:\n" + result.output());
        }
    }

    private static void assertExportDenial(String diagnostic, String packageName) {
        if (!diagnostic.contains(packageName) || !diagnostic.toLowerCase(java.util.Locale.ROOT).contains("does not export")) {
            throw new AssertionError("Expected JPMS export denial for " + packageName
                    + "; diagnostics were:\n" + diagnostic);
        }
    }

    void assertDeniedReflection() throws Exception {
        String moduleName = "fixture.forbidden.reflection";
        String packageName = "fixture.reflection";
        String className = "ReflectionProbe";
        String moduleInfo = "module " + moduleName + " {}\n";
        String source = "package " + packageName + ";\n"
                + "import java.lang.reflect.AccessibleObject;\n"
                + "import java.lang.reflect.Constructor;\n"
                + "import java.lang.reflect.Method;\n"
                + "import java.lang.reflect.InaccessibleObjectException;\n"
                + "public final class ReflectionProbe {\n"
                + "  public static void main(String[] args) {\n"
                + "    if (!ReflectionProbe.class.getModule().isNamed()) throw new AssertionError(\"unnamed consumer\");\n"
                + "    denied(\"xml\", \"ru.onlychill.mbondsfx.integrations.xml.SecureXml\", true);\n"
                + "    denied(\"moex-internal\", \"ru.onlychill.mbondsfx.integrations.moex.internal.MoexProviderFactory\", false);\n"
                + "    denied(\"cbr-internal\", \"ru.onlychill.mbondsfx.integrations.cbr.internal.CbrProviderFactory\", false);\n"
                + "  }\n"
                + "  private static void denied(String label, String name, boolean xml) {\n"
                + "    try {\n"
                + "      Class<?> target = Class.forName(name);\n"
                + "      AccessibleObject member = xml ? target.getDeclaredMethod(\"parse\", String.class)\n"
                + "          : target.getDeclaredConstructor();\n"
                + "      member.setAccessible(true);\n"
                + "      throw new AssertionError(\"unexpected reflective access: \" + label);\n"
                + "    } catch (InaccessibleObjectException denied) {\n"
                + "      System.out.println(\"DENIED \" + label + \" \" + denied.getClass().getSimpleName());\n"
                + "    } catch (ClassNotFoundException missing) {\n"
                + "      throw new AssertionError(\"class not found instead of encapsulation denial: \" + name, missing);\n"
                + "    } catch (ReflectiveOperationException failure) {\n"
                + "      throw new AssertionError(\"reflection probe failed: \" + name, failure);\n"
                + "    }\n"
                + "  }\n"
                + "}\n";
        Path jar = compileModule(moduleName, packageName, className, moduleInfo, source,
                List.of(), Set.of(), Set.of());
        List<Path> modulePath = append(installedJars, jar);
        List<String> command = new ArrayList<>(List.of(javaExecutable.toString(), "--module-path", joinPaths(modulePath),
                "--add-modules", "ALL-MODULE-PATH", "--module", moduleName + "/" + packageName + "." + className));
        ProcessResult result = runProcess("reflection-encapsulation", command, PROCESS_TIMEOUT);
        assertSuccess(result);
        assertReflectionDenied(result.output(), "xml");
        assertReflectionDenied(result.output(), "moex-internal");
        assertReflectionDenied(result.output(), "cbr-internal");
        if (result.output().contains("CLASS_NOT_FOUND")) {
            throw new AssertionError("Reflection check failed by missing target class:\n" + result.output());
        }
    }

    private static void assertReflectionDenied(String output, String label) {
        List<String> matching = output.lines().filter(line -> line.startsWith("DENIED " + label + " ")).toList();
        if (matching.size() != 1 || !(matching.getFirst().contains("InaccessibleObjectException")
                || matching.getFirst().contains("IllegalAccessException"))) {
            throw new AssertionError("Expected reflection access denial for " + label + "; output:\n" + output);
        }
    }

    private void assertFailure(String label, List<String> command, String id, String capability,
                               List<String> reasonAlternatives) throws Exception {
        assertFailure(runProcess(label, command, PROCESS_TIMEOUT), id, capability, reasonAlternatives);
    }

    private static void assertFailure(ProcessResult result, String id, String capability,
                                      List<String> reasonAlternatives) {
        if (result.exitCode() == 0) {
            throw new AssertionError("Expected nonzero status for " + result.label() + "; output:\n" + result.output());
        }
        String lower = result.output().toLowerCase(java.util.Locale.ROOT);
        if (!lower.contains(id.toLowerCase(java.util.Locale.ROOT))
                || !lower.contains(capability.toLowerCase(java.util.Locale.ROOT))) {
            throw new AssertionError("Failure diagnostic for " + result.label()
                    + " must identify " + capability + " provider ID " + id + "; output:\n" + result.output());
        }
        boolean reasonFound = reasonAlternatives.stream()
                .anyMatch(reason -> lower.contains(reason.toLowerCase(java.util.Locale.ROOT)));
        if (!reasonFound) {
            throw new AssertionError("Failure diagnostic for " + result.label()
                    + " must state why provider ID " + id + " failed; output:\n" + result.output());
        }
    }

    private ProcessResult runProcess(String label, List<String> command, Duration timeout) throws Exception {
        Path log = logsDir.resolve(safeName(label) + ".log");
        Files.deleteIfExists(log);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(projectRoot.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().put("JAVA_HOME", Path.of(System.getProperty("java.home")).toString());
        Process process = builder.start();
        boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            String output = Files.exists(log) ? Files.readString(log) : "";
            throw new AssertionError("Timed out after " + timeout + " running " + command
                    + "; diagnostics:\n" + output);
        }
        String output = Files.exists(log) ? Files.readString(log) : "";
        return new ProcessResult(label, command, process.exitValue(), output);
    }

    private static void assertSuccess(ProcessResult result) {
        if (result.exitCode() != 0) {
            throw new AssertionError("Expected success for " + result.label() + " but exit was "
                    + result.exitCode() + "; command=" + result.command() + "; diagnostics:\n" + result.output());
        }
    }

    Map<String, String> hashInstalledJars() throws IOException, NoSuchAlgorithmException {
        Map<String, String> hashes = new HashMap<>();
        for (Path jar : installedJars) {
            hashes.put(installLib.relativize(jar).toString(), sha256(jar));
        }
        return Map.copyOf(hashes);
    }

    Map<String, String> hashModules(Set<String> moduleNames) throws IOException, NoSuchAlgorithmException {
        Map<String, String> hashes = new HashMap<>();
        for (String moduleName : moduleNames) {
            Path jar = installedModules.get(moduleName);
            if (jar == null) {
                throw new IllegalStateException("Missing installed module to hash: " + moduleName);
            }
            hashes.put(moduleName, sha256(jar));
        }
        return Map.copyOf(hashes);
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static ModuleDescriptor descriptorOf(Path jar) {
        Set<ModuleReference> references = ModuleFinder.of(jar).findAll();
        if (references.size() != 1) {
            throw new IllegalStateException("Expected one named module in " + jar);
        }
        return references.iterator().next().descriptor();
    }

    private static void writeJar(Path classesDirectory, Path jar) throws IOException {
        List<Path> classFiles;
        try (Stream<Path> files = Files.walk(classesDirectory)) {
            classFiles = files.filter(Files::isRegularFile).sorted().toList();
        }
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Path file : classFiles) {
                String entryName = classesDirectory.relativize(file).toString().replace(file.getFileSystem()
                        .getSeparator(), "/");
                JarEntry entry = new JarEntry(entryName);
                entry.setTime(0L);
                output.putNextEntry(entry);
                Files.copy(file, output);
                output.closeEntry();
            }
        }
    }

    private Path findStartScript(Path binDirectory) throws IOException {
        if (!Files.isDirectory(binDirectory)) {
            throw new IllegalArgumentException("installDir has no bin directory: " + binDirectory);
        }
        try (Stream<Path> children = Files.list(binDirectory)) {
            List<Path> scripts = children.filter(Files::isRegularFile)
                    .filter(path -> isWindows()
                            ? path.getFileName().toString().endsWith(".bat")
                            : !path.getFileName().toString().endsWith(".bat"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            if (scripts.size() != 1) {
                throw new IllegalStateException("Expected one installed start script in " + binDirectory
                        + "; found " + scripts);
            }
            if (!isWindows() && !Files.isExecutable(scripts.getFirst())) {
                throw new IllegalStateException("Installed start script is not executable: " + scripts.getFirst());
            }
            return scripts.getFirst();
        }
    }

    private static List<Path> append(List<Path> paths, Path additional) {
        List<Path> result = new ArrayList<>(paths);
        result.add(additional);
        return List.copyOf(result);
    }

    private static String joinPaths(List<Path> paths) {
        return String.join(java.io.File.pathSeparator, paths.stream().map(Path::toString).toList());
    }

    private static Path requiredPathProperty(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Required system property is missing: " + property);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static Path executable(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Missing JDK tool: " + path);
        }
        return path;
    }

    private static String toolName(String name) {
        return isWindows() ? name + ".exe" : name;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    }

    private static String safeName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private record ProcessResult(String label, List<String> command, int exitCode, String output) { }
}
