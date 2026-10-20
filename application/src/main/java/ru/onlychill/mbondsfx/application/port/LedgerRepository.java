package ru.onlychill.mbondsfx.application.port;

import java.util.Optional;
import ru.onlychill.mbondsfx.core.id.PortfolioId;

/** Atomic versioned snapshot-and-journal boundary for one or more portfolios. */
public interface LedgerRepository extends LedgerJournal {
    Optional<LedgerSnapshot> load(PortfolioId portfolio);

    /**
     * Atomically checks the expected version and exact current before-state, rejects duplicate operation IDs,
     * stores the validated after-state and record derived from the supplied operation, and assigns the next
     * per-portfolio sequence. A missing portfolio has version 0 and can be initialized only from an explicit
     * empty before-state. Snapshot and journal updates are one transaction; conflicts are never retried here.
     *
     * @throws LedgerConcurrencyException if the version or before-state is stale
     * @throws DuplicateOperationException if the portfolio already contains the operation ID
     */
    LedgerSnapshot commit(LedgerCommit commit);
}
