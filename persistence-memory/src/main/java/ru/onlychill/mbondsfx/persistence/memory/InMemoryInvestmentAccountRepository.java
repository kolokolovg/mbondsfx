package ru.onlychill.mbondsfx.persistence.memory;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import ru.onlychill.mbondsfx.application.port.InvestmentAccount;
import ru.onlychill.mbondsfx.application.port.InvestmentAccountConflictException;
import ru.onlychill.mbondsfx.application.port.InvestmentAccountRepository;
import ru.onlychill.mbondsfx.core.id.PortfolioId;

/** Thread-safe immutable-metadata registry isolated by portfolio; non-durable. */
final class InMemoryInvestmentAccountRepository implements InvestmentAccountRepository {
    private final ConcurrentMap<PortfolioId, InvestmentAccount> accounts = new ConcurrentHashMap<>();

    @Override
    public Optional<InvestmentAccount> account(PortfolioId portfolio) {
        return Optional.ofNullable(accounts.get(Objects.requireNonNull(portfolio, "portfolio")));
    }

    @Override
    public InvestmentAccount register(InvestmentAccount account) {
        Objects.requireNonNull(account, "account");
        return accounts.compute(account.portfolio(), (portfolio, existing) -> {
            if (existing == null || existing.equals(account)) {
                return account;
            }
            throw new InvestmentAccountConflictException(portfolio);
        });
    }
}
