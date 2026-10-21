package ru.onlychill.mbondsfx.application.port;

import ru.onlychill.mbondsfx.core.id.PortfolioId;

/** A portfolio already has account metadata that differs from the registration request. */
public final class InvestmentAccountConflictException extends IllegalStateException {
    private final PortfolioId portfolio;

    public InvestmentAccountConflictException(PortfolioId portfolio) {
        super("Investment account metadata conflicts for portfolio " + portfolio.value());
        this.portfolio = portfolio;
    }

    public PortfolioId portfolio() {
        return portfolio;
    }
}
