package ru.onlychill.mbondsfx.application.port;

import java.util.Optional;
import ru.onlychill.mbondsfx.core.id.PortfolioId;

/** Registry for explicitly known investment-account metadata, isolated by portfolio. */
public interface InvestmentAccountRepository {
    /** Empty means unknown; it never implies a brokerage account. */
    Optional<InvestmentAccount> account(PortfolioId portfolio);

    /** Exact repeated registration is idempotent; conflicting metadata is rejected. */
    InvestmentAccount register(InvestmentAccount account);
}
