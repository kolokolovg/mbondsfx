package ru.onlychill.mbondsfx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeMap;
import java.util.function.Function;
import ru.onlychill.mbondsfx.application.port.LedgerRepository;
import ru.onlychill.mbondsfx.application.port.LedgerRepositoryFactory;
import ru.onlychill.mbondsfx.application.port.RepositoryConfig;
import ru.onlychill.mbondsfx.application.port.BondReferenceDataSource;
import ru.onlychill.mbondsfx.integrations.provider.BondReferenceDataSourceProvider;
import ru.onlychill.mbondsfx.integrations.provider.IndexValueSourceProvider;
import ru.onlychill.mbondsfx.integrations.provider.ProviderContext;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        try {
            run(CliOptions.parse(args));
        } catch (RuntimeException failure) {
            String message = failure.getMessage();
            System.err.println("mbondsfx: " + (message == null ? failure.getClass().getSimpleName() : message));
            System.exit(2);
        }
    }

    private static void run(CliOptions options) {
        List<BondReferenceDataSourceProvider> bondServices = services(BondReferenceDataSourceProvider.class);
        List<IndexValueSourceProvider> indexServices = services(IndexValueSourceProvider.class);
        List<LedgerRepositoryFactory> repositoryServices = services(LedgerRepositoryFactory.class);
        ProviderCatalog<BondReferenceDataSourceProvider> bondCatalog = catalog(
                "bond", bondServices, BondReferenceDataSourceProvider::providerId);
        ProviderCatalog<IndexValueSourceProvider> indexCatalog = catalog(
                "index", indexServices, IndexValueSourceProvider::providerId);
        ProviderCatalog<LedgerRepositoryFactory> repositoryCatalog = catalog(
                "repository", repositoryServices, LedgerRepositoryFactory::providerId);
        List<String> catalogErrors = new ArrayList<>();
        catalogErrors.addAll(bondCatalog.errors());
        catalogErrors.addAll(indexCatalog.errors());
        catalogErrors.addAll(repositoryCatalog.errors());
        if (!catalogErrors.isEmpty()) {
            throw new IllegalStateException(String.join("; ", catalogErrors));
        }
        Map<String, BondReferenceDataSourceProvider> bondProviders = bondCatalog.providers();
        Map<String, IndexValueSourceProvider> indexProviders = indexCatalog.providers();
        Map<String, LedgerRepositoryFactory> repositoryProviders = repositoryCatalog.providers();

        String bondId = "none";
        if (options.includesBond()) {
            ProviderSelection<BondReferenceDataSourceProvider> selected = requireProvider(
                    "bond", options.bondProvider(), bondProviders);
            BondReferenceDataSource source = selected.provider().create(new ProviderContext(Map.of()));
            if (source == null) {
                throw new IllegalStateException("Bond provider '" + selected.id() + "' returned null");
            }
            bondId = selected.id();
        }

        String indexId = "none";
        if (options.includesIndex()) {
            ProviderSelection<IndexValueSourceProvider> selected = requireProvider(
                    "index", options.indexProvider(), indexProviders);
            if (selected.provider().create(new ProviderContext(Map.of())) == null) {
                throw new IllegalStateException("Index provider '" + selected.id() + "' returned null");
            }
            indexId = selected.id();
        }

        ProviderSelection<LedgerRepositoryFactory> repository = requireProvider(
                "repository", options.repositoryProvider(), repositoryProviders);
        LedgerRepository ledgerRepository = repository.provider().create(RepositoryConfig.empty());
        if (ledgerRepository == null) {
            throw new IllegalStateException("Repository provider '" + repository.id() + "' returned null");
        }

        System.out.println("profile=" + options.profile() + " bond=" + bondId + " index=" + indexId
                + " repository=" + repository.id());
    }

    private static <T> List<T> services(Class<T> serviceType) {
        return ServiceLoader.load(serviceType).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
    }

    private static <T> ProviderCatalog<T> catalog(String capability, List<T> providers,
            Function<T, String> providerId) {
        Map<String, T> byId = new TreeMap<>();
        List<String> errors = new ArrayList<>();
        for (T provider : providers) {
            String id = providerId.apply(provider);
            if (id == null || id.isBlank()) {
                errors.add("The " + capability + " provider has a blank provider ID");
                continue;
            }
            if (byId.putIfAbsent(id, provider) != null) {
                errors.add("Duplicate " + capability + " provider ID '" + id + "'");
            }
        }
        return new ProviderCatalog<>(Map.copyOf(byId), List.copyOf(errors));
    }

    private static <T> ProviderSelection<T> requireProvider(String capability, String id, Map<String, T> providers) {
        T provider = providers.get(id);
        if (provider == null) {
            String available = String.join(", ", new ArrayList<>(new java.util.TreeSet<>(providers.keySet())));
            throw new IllegalArgumentException("No " + capability + " provider with ID '" + id
                    + "' (available: " + available + ")");
        }
        return new ProviderSelection<>(id, provider);
    }

    private record ProviderSelection<T>(String id, T provider) {
    }

    private record ProviderCatalog<T>(Map<String, T> providers, List<String> errors) {
    }

    private record CliOptions(String profile, String bondProvider, String indexProvider, String repositoryProvider) {
        private static CliOptions parse(String[] args) {
            Map<String, String> options = new HashMap<>();
            for (String argument : args) {
                int separator = argument == null ? -1 : argument.indexOf('=');
                if (separator < 0) {
                    throw new IllegalArgumentException("Expected --option=value, got '" + argument + "'");
                }
                String name = argument.substring(0, separator);
                String value = argument.substring(separator + 1);
                if (!List.of("--profile", "--bond-provider", "--index-provider", "--repository-provider")
                        .contains(name)) {
                    throw new IllegalArgumentException("Unknown option '" + name + "'");
                }
                if (value.isBlank()) {
                    throw new IllegalArgumentException("Option '" + name + "' must not be blank");
                }
                if (options.putIfAbsent(name, value) != null) {
                    throw new IllegalArgumentException("Option '" + name + "' was supplied more than once");
                }
            }

            String profile = options.getOrDefault("--profile", "all");
            if (!List.of("all", "moex", "cbr").contains(profile)) {
                throw new IllegalArgumentException("Invalid profile '" + profile + "' (expected all, moex, or cbr)");
            }
            return new CliOptions(profile,
                    options.getOrDefault("--bond-provider", "moex"),
                    options.getOrDefault("--index-provider", "cbr"),
                    options.getOrDefault("--repository-provider", "memory"));
        }

        private boolean includesBond() {
            return "all".equals(profile) || "moex".equals(profile);
        }

        private boolean includesIndex() {
            return "all".equals(profile) || "cbr".equals(profile);
        }
    }
}
