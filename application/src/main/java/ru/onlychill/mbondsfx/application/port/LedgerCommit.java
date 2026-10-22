package ru.onlychill.mbondsfx.application.port;

import java.util.Objects;
import ru.onlychill.mbondsfx.core.id.PortfolioId;
import ru.onlychill.mbondsfx.core.ledger.Ledger;
import ru.onlychill.mbondsfx.core.ledger.LedgerState;
import ru.onlychill.mbondsfx.core.ledger.OperationResult;

/** Proposed single-operation state transition; arbitrary replacement snapshots are not accepted. */
public record LedgerCommit(long expectedVersion, LedgerState before,
                           OperationResult operation, LedgerState after) {
    public LedgerCommit {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(after, "after");
        if (!before.portfolio().equals(after.portfolio())
                || !before.chart().equals(after.chart())
                || before.allowNegativeCash() != after.allowNegativeCash()) {
            throw new IllegalArgumentException("Commit cannot change portfolio, chart, or negative-cash policy");
        }
        if (expectedVersion == 0 && !isEmpty(before)) {
            throw new IllegalArgumentException("Version 0 must start from an explicit empty ledger state");
        }
        if (expectedVersion > 0 && before.operationIds().isEmpty()) {
            throw new IllegalArgumentException("A persisted version must have prior operations");
        }
        if (before.operationIds().contains(operation.operation())) {
            throw new DuplicateOperationException(before.portfolio(), operation.operation());
        }
        Ledger expectedAfter = Ledger.restore(before).apply(operation);
        if (!expectedAfter.state().equals(after)) {
            throw new IllegalArgumentException("After-state must equal before-state applied with operation");
        }
    }

    public PortfolioId portfolio() {
        return before.portfolio();
    }

    private static boolean isEmpty(LedgerState state) {
        return state.balances().isEmpty() && state.positions().isEmpty()
                && state.postingsByDate().isEmpty() && state.operationIds().isEmpty();
    }
}
