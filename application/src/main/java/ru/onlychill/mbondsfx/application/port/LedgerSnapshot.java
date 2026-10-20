package ru.onlychill.mbondsfx.application.port;

import java.util.Objects;
import ru.onlychill.mbondsfx.core.id.PortfolioId;
import ru.onlychill.mbondsfx.core.ledger.Ledger;
import ru.onlychill.mbondsfx.core.ledger.LedgerState;

/** A validated state and its optimistic version/last journal sequence. */
public record LedgerSnapshot(PortfolioId portfolio, long version, long lastSequence, LedgerState state) {
    public LedgerSnapshot {
        Objects.requireNonNull(portfolio, "portfolio");
        Objects.requireNonNull(state, "state");
        if (version <= 0 || lastSequence <= 0) {
            throw new IllegalArgumentException("Persisted snapshot version and sequence must be positive");
        }
        if (!portfolio.equals(state.portfolio())) {
            throw new IllegalArgumentException("Snapshot portfolio differs from ledger state");
        }
        Ledger.restore(state);
    }
}
