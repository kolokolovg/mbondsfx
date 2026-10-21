package ru.onlychill.mbondsfx.persistence.memory;

import java.util.Objects;
import ru.onlychill.mbondsfx.application.port.LedgerRepository;
import ru.onlychill.mbondsfx.application.port.LedgerRepositoryFactory;
import ru.onlychill.mbondsfx.application.port.InvestmentAccountRepository;
import ru.onlychill.mbondsfx.application.port.InvestmentAccountRepositoryFactory;
import ru.onlychill.mbondsfx.application.port.RepositoryConfig;

/** Creates an isolated, non-durable in-memory repository. */
public final class InMemoryRepositoryFactory implements LedgerRepositoryFactory, InvestmentAccountRepositoryFactory {
    public static final String PROVIDER_ID = "memory";

    public InMemoryRepositoryFactory() { }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public LedgerRepository create(RepositoryConfig config) {
        validateConfig(config);
        return new InMemoryLedgerRepository();
    }

    @Override
    public InvestmentAccountRepository createInvestmentAccountRepository(RepositoryConfig config) {
        validateConfig(config);
        return new InMemoryInvestmentAccountRepository();
    }

    private static void validateConfig(RepositoryConfig config) {
        Objects.requireNonNull(config, "config");
        if (!config.settings().isEmpty()) {
            throw new IllegalArgumentException("The memory repositories support no settings: "
                    + config.settings().keySet());
        }
    }
}
