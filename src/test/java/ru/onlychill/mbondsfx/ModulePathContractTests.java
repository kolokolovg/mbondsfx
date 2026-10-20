package ru.onlychill.mbondsfx;

import java.io.IOException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Blackbox JPMS tests against the produced installDist distribution. */
public final class ModulePathContractTests {
    private static Path projectRoot;
    private static Path installDir;

    @TempDir
    private Path fixtureWorkspace;

    private ModulePathContract contract;

    @BeforeAll
    static void loadBuildInputs() {
        if (Runtime.version().feature() != 21) {
            throw new IllegalStateException("Module-path tests require JDK 21; running on " + Runtime.version());
        }
        projectRoot = requiredPathProperty("projectRoot");
        installDir = requiredPathProperty("installDir");
    }

    @BeforeEach
    void prepareIsolatedFixtureWorkspace() throws IOException, NoSuchAlgorithmException {
        contract = new ModulePathContract(projectRoot, installDir, fixtureWorkspace);
    }

    @AfterEach
    void producedInstallDistJarsRemainUnchanged() throws Exception {
        if (contract != null) {
            assertEquals(contract.installedHashesBefore, contract.hashInstalledJars(),
                    "JPMS fixtures must not modify produced installDist jars");
        }
    }

    @Test
    void installedDescriptorsDeclareThreeTypedServiceBindings() {
        contract.inspectProductionModules();
    }

    @ParameterizedTest(name = "installed start script: {0}")
    @MethodSource("profileCases")
    void installedStartScriptRunsEachProfile(String label, List<String> args, String expected) throws Exception {
        contract.assertInstalledScriptProfile(label, args, expected);
    }

    @ParameterizedTest(name = "direct module path: {0}")
    @MethodSource("profileCases")
    void modulePathRunsEachProfile(String label, List<String> args, String expected) throws Exception {
        contract.assertModuleProfile(label, contract.installedJars, args, expected, false);
    }

    @ParameterizedTest(name = "missing provider: {0}")
    @MethodSource("missingProviderCases")
    void missingProvidersHaveActionableDiagnostics(String label, String moduleToRemove, List<String> args,
                                                    String id, String capability) throws Exception {
        contract.assertMissingProvider(label, moduleToRemove, args, id, capability);
    }

    @Test
    void duplicateProviderIdsAreRejected() throws Exception {
        contract.assertDuplicateProviderIdRejected();
    }

    @Test
    void providerCanBeReplacedWithoutRecompilingProtectedModules() throws Exception {
        contract.assertProviderReplacementWorks();
    }

    @Test
    void javacDeniesImportsFromQualifiedAndNonExportedPackages() throws Exception {
        contract.assertDeniedImportsCompileFailure();
    }

    @Test
    void reflectionCannotOpenQualifiedOrNonExportedPackages() throws Exception {
        contract.assertDeniedReflection();
    }

    private static Stream<Arguments> profileCases() {
        return Stream.of(
                Arguments.of("all", List.of(), "profile=all bond=moex index=cbr repository=memory"),
                Arguments.of("moex", List.of("--profile=moex"), "profile=moex bond=moex index=none repository=memory"),
                Arguments.of("cbr", List.of("--profile=cbr"), "profile=cbr bond=none index=cbr repository=memory"));
    }

    private static Stream<Arguments> missingProviderCases() {
        return Stream.of(
                Arguments.of("missing-moex-provider-id", "ru.onlychill.mbondsfx.integrations.moex",
                        List.of("--profile=moex"), "moex", "bond"),
                Arguments.of("missing-cbr-provider-id", "ru.onlychill.mbondsfx.integrations.cbr",
                        List.of("--profile=cbr"), "cbr", "index"),
                Arguments.of("missing-memory-provider-id", "ru.onlychill.mbondsfx.persistence.memory",
                        List.of("--profile=moex"), "memory", "repository"));
    }

    private static Path requiredPathProperty(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Required system property is missing: " + property);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }
}
