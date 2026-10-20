package ru.onlychill.mbondsfx.persistence.memory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import ru.onlychill.mbondsfx.application.port.DuplicateOperationException;
import ru.onlychill.mbondsfx.application.port.LedgerCommit;
import ru.onlychill.mbondsfx.application.port.LedgerConcurrencyException;
import ru.onlychill.mbondsfx.application.port.LedgerRepository;
import ru.onlychill.mbondsfx.application.port.LedgerSnapshot;
import ru.onlychill.mbondsfx.application.port.OperationRecord;
import ru.onlychill.mbondsfx.core.id.OperationId;
import ru.onlychill.mbondsfx.core.id.PortfolioId;
import ru.onlychill.mbondsfx.core.ledger.Ledger;
import ru.onlychill.mbondsfx.core.ledger.LedgerState;
import ru.onlychill.mbondsfx.core.ledger.Posting;

/** Per-portfolio locked implementation; all observable state is immutable and non-durable. */
final class InMemoryLedgerRepository implements LedgerRepository {
    private final ConcurrentMap<PortfolioId, PortfolioStore> portfolios = new ConcurrentHashMap<>();

    @Override
    public Optional<LedgerSnapshot> load(PortfolioId portfolio) {
        Objects.requireNonNull(portfolio, "portfolio");
        PortfolioStore store = portfolios.get(portfolio);
        if (store == null) {
            return Optional.empty();
        }
        store.lock.lock();
        try {
            return Optional.ofNullable(store.snapshot);
        } finally {
            store.lock.unlock();
        }
    }

    @Override
    public List<OperationRecord> operations(PortfolioId portfolio) {
        Objects.requireNonNull(portfolio, "portfolio");
        PortfolioStore store = portfolios.get(portfolio);
        if (store == null) {
            return List.of();
        }
        store.lock.lock();
        try {
            return List.copyOf(store.journal);
        } finally {
            store.lock.unlock();
        }
    }

    @Override
    public LedgerSnapshot commit(LedgerCommit commit) {
        Objects.requireNonNull(commit, "commit");
        PortfolioStore store = portfolios.computeIfAbsent(commit.portfolio(), ignored -> new PortfolioStore());
        store.lock.lock();
        try {
            validateStore(store, commit.portfolio());
            OperationId operationId = commit.operation().operation();
            if (store.operationIds.contains(operationId)) {
                throw new DuplicateOperationException(commit.portfolio(), operationId);
            }

            long actualVersion = store.snapshot == null ? 0 : store.snapshot.version();
            if (commit.expectedVersion() != actualVersion) {
                throw new LedgerConcurrencyException(commit.portfolio(), commit.expectedVersion(), actualVersion);
            }

            LedgerState actualBefore = store.snapshot == null
                    ? Ledger.open(commit.portfolio(), commit.before().allowNegativeCash()).state()
                    : store.snapshot.state();
            if (!actualBefore.equals(commit.before())) {
                throw new LedgerConcurrencyException(commit.portfolio(), commit.expectedVersion(), actualVersion);
            }
            if (actualBefore.operationIds().contains(operationId)) {
                throw new DuplicateOperationException(commit.portfolio(), operationId);
            }

            Ledger calculatedAfter = Ledger.restore(actualBefore).apply(commit.operation());
            if (!calculatedAfter.state().equals(commit.after())) {
                throw new IllegalArgumentException("Commit after-state differs from domain application");
            }

            long currentSequence = store.snapshot == null ? 0 : store.snapshot.lastSequence();
            long nextSequence = Math.addExact(currentSequence, 1);
            long nextVersion = Math.addExact(actualVersion, 1);
            OperationRecord record = OperationRecord.from(commit.operation(), nextSequence);

            List<OperationRecord> nextJournal = new ArrayList<>(store.journal);
            nextJournal.add(record);
            Set<OperationId> nextOperationIds = new HashSet<>(store.operationIds);
            if (!nextOperationIds.add(operationId)) {
                throw new DuplicateOperationException(commit.portfolio(), operationId);
            }
            LedgerSnapshot nextSnapshot = new LedgerSnapshot(commit.portfolio(), nextVersion, nextSequence,
                    commit.after());
            List<OperationRecord> committedJournal = List.copyOf(nextJournal);
            Set<OperationId> committedOperationIds = Set.copyOf(nextOperationIds);
            validateJournal(nextSnapshot, committedJournal, committedOperationIds);

            // Readers take the same portfolio lock, so these reference swaps are one visible commit.
            store.snapshot = nextSnapshot;
            store.journal = committedJournal;
            store.operationIds = committedOperationIds;
            return nextSnapshot;
        } finally {
            store.lock.unlock();
        }
    }

    private static void validateStore(PortfolioStore store, PortfolioId portfolio) {
        if (store.snapshot == null) {
            if (!store.journal.isEmpty() || !store.operationIds.isEmpty()) {
                throw new IllegalStateException("Empty portfolio has journal data");
            }
            return;
        }
        if (!portfolio.equals(store.snapshot.portfolio())) {
            throw new IllegalStateException("Stored snapshot portfolio is inconsistent");
        }
        validateJournal(store.snapshot, store.journal, store.operationIds);
    }

    private static void validateJournal(LedgerSnapshot snapshot, List<OperationRecord> journal,
                                       Set<OperationId> operationIds) {
        Ledger.restore(snapshot.state());
        Set<OperationId> journalIds = new HashSet<>();
        NavigableMap<LocalDate, List<Posting>> history = new TreeMap<>();
        LocalDate previousDate = null;
        long previousSequence = 0;
        long expectedSequence = 1;
        for (OperationRecord operation : journal) {
            if (operation.sequence() != expectedSequence) {
                throw new IllegalStateException("Journal sequence is not contiguous");
            }
            if (previousDate != null && (operation.date().isBefore(previousDate)
                    || (operation.date().equals(previousDate) && operation.sequence() <= previousSequence))) {
                throw new IllegalStateException("Journal is not ordered by (date, sequence)");
            }
            if (journalIds.contains(operation.operation())) {
                throw new IllegalStateException("Duplicate operation ID in in-memory journal");
            }
            journalIds.add(operation.operation());
            history.computeIfAbsent(operation.date(), ignored -> new ArrayList<>()).addAll(operation.postings());
            previousDate = operation.date();
            previousSequence = operation.sequence();
            expectedSequence = Math.addExact(expectedSequence, 1);
        }
        if (!journalIds.equals(operationIds) || !journalIds.equals(snapshot.state().operationIds())) {
            throw new IllegalStateException("Journal operation IDs differ from snapshot");
        }
        if (!history.equals(snapshot.state().postingsByDate())) {
            throw new IllegalStateException("Journal postings differ from snapshot history");
        }
        long count = journal.size();
        if (snapshot.version() != count || snapshot.lastSequence() != count) {
            throw new IllegalStateException("Snapshot version/sequence differ from retained journal count");
        }
    }

    private static final class PortfolioStore {
        private final ReentrantLock lock = new ReentrantLock();
        private LedgerSnapshot snapshot;
        private List<OperationRecord> journal = List.of();
        private Set<OperationId> operationIds = Set.of();
    }
}
