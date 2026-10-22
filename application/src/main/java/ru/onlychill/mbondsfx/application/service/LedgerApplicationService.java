package ru.onlychill.mbondsfx.application.service;

import java.util.Objects;
import java.util.Optional;
import ru.onlychill.mbondsfx.application.port.LedgerCommit;
import ru.onlychill.mbondsfx.application.port.DuplicateOperationException;
import ru.onlychill.mbondsfx.application.port.LedgerRepository;
import ru.onlychill.mbondsfx.application.port.LedgerSnapshot;
import ru.onlychill.mbondsfx.core.id.PortfolioId;
import ru.onlychill.mbondsfx.core.ledger.BondAccountingPolicy;
import ru.onlychill.mbondsfx.core.ledger.Ledger;
import ru.onlychill.mbondsfx.core.ledger.OperationResult;

/** Loads, applies, and atomically commits one explicit domain operation without retrying conflicts. */
public final class LedgerApplicationService {
    private final LedgerRepository repository;
    private final BondAccountingPolicy policy;

    public LedgerApplicationService(LedgerRepository repository, BondAccountingPolicy policy) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public LedgerSnapshot apply(PortfolioId portfolio, boolean allowNegativeCash, OperationResult operation) {
        Objects.requireNonNull(portfolio, "portfolio");
        Objects.requireNonNull(operation, "operation");
        Optional<LedgerSnapshot> loaded = Objects.requireNonNull(repository.load(portfolio), "repository.load result");

        Ledger before;
        long expectedVersion;
        if (loaded.isPresent()) {
            LedgerSnapshot snapshot = loaded.orElseThrow();
            if (!portfolio.equals(snapshot.portfolio())) {
                throw new IllegalStateException("Repository returned a snapshot for a different portfolio");
            }
            if (allowNegativeCash != snapshot.state().allowNegativeCash()) {
                throw new IllegalStateException("Cash policy differs from the persisted ledger policy");
            }
            if (!policy.chart().equals(snapshot.state().chart())) {
                throw new IllegalStateException("Chart differs from the persisted ledger chart");
            }
            before = Ledger.restore(snapshot.state());
            expectedVersion = snapshot.version();
        } else {
            before = Ledger.open(portfolio, policy.chart(), allowNegativeCash);
            expectedVersion = 0;
        }

        if (before.operationIds().contains(operation.operation())) {
            throw new DuplicateOperationException(portfolio, operation.operation());
        }
        Ledger after = before.apply(operation);
        return repository.commit(new LedgerCommit(expectedVersion, before.state(), operation, after.state()));
    }
}
