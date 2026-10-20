package ru.onlychill.mbondsfx.application.port;

import java.util.Objects;
import ru.onlychill.mbondsfx.core.id.PortfolioId;

/** The repository's current version did not match the commit's expected version. */
public final class LedgerConcurrencyException extends RuntimeException {
    private final PortfolioId portfolio;
    private final long expectedVersion;
    private final long actualVersion;

    public LedgerConcurrencyException(PortfolioId portfolio, long expectedVersion, long actualVersion) {
        super("Ledger version conflict for " + Objects.requireNonNull(portfolio, "portfolio")
                + ": expected " + expectedVersion + ", actual " + actualVersion);
        this.portfolio = portfolio;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public PortfolioId portfolio() { return portfolio; }
    public long expectedVersion() { return expectedVersion; }
    public long actualVersion() { return actualVersion; }
}
