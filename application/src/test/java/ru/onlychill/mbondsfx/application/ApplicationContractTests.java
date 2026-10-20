package ru.onlychill.mbondsfx.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ru.onlychill.mbondsfx.application.port.BondReference;
import ru.onlychill.mbondsfx.application.port.BondReferenceDataSource;
import ru.onlychill.mbondsfx.application.port.DuplicateOperationException;
import ru.onlychill.mbondsfx.application.port.LedgerCommit;
import ru.onlychill.mbondsfx.application.port.LedgerConcurrencyException;
import ru.onlychill.mbondsfx.application.port.LedgerRepository;
import ru.onlychill.mbondsfx.application.port.LedgerSnapshot;
import ru.onlychill.mbondsfx.application.port.OperationRecord;
import ru.onlychill.mbondsfx.application.port.RepositoryConfig;
import ru.onlychill.mbondsfx.application.service.LedgerApplicationService;
import ru.onlychill.mbondsfx.core.id.OperationId;
import ru.onlychill.mbondsfx.core.id.PortfolioId;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.core.ledger.BondOperations;
import ru.onlychill.mbondsfx.core.ledger.Ledger;
import ru.onlychill.mbondsfx.core.ledger.LedgerState;
import ru.onlychill.mbondsfx.core.ledger.OperationResult;
import ru.onlychill.mbondsfx.core.money.Money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationContractTests {
    private static final Currency RUB = Currency.getInstance("RUB");
    private static final PortfolioId PORTFOLIO = new PortfolioId("application-contract");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 20);

    @Test
    void referenceDataPortIsSparseAndImmutable() {
        SecurityId security = new SecurityId("SU0000000001");
        BondReference reference = new BondReference(security, "source-A", Optional.empty(),
                Optional.of(new BondReference.ReportedValue(new BigDecimal("1000.000"), "RUB/issue")),
                Optional.of(new BondReference.NextCoupon(Optional.of(DATE), Optional.empty())));
        BondReferenceDataSource source = requested -> requested.equals(security)
                ? Optional.of(reference) : Optional.empty();
        assertEquals(reference, source.reference(security).orElseThrow(),
                "source returns sparse source-attributed facts");
        assertTrue(reference.observedOn().isEmpty() && reference.faceValuePerUnit().orElseThrow().unit().equals("RUB/issue"),
                "unreported observation date remains absent and unit text is preserved");
        assertEquals(new BigDecimal("1000.000"), reference.faceValuePerUnit().orElseThrow().value(),
                "reported BigDecimal precision is not rounded or normalized");
        assertThrows(IllegalArgumentException.class,
                () -> new BondReference(security, " ", Optional.empty(), Optional.empty(), Optional.empty()),
                "source ID is required");
        assertThrows(IllegalArgumentException.class,
                () -> new BondReference.ReportedValue(BigDecimal.ONE, " "), "reported unit is required");
        assertThrows(IllegalArgumentException.class,
                () -> new BondReference.NextCoupon(Optional.empty(), Optional.empty()),
                "empty next-coupon record is not allowed");
    }

    @Test
    void serviceForwardsVersionedAppliedTransitions() {
        InMemoryContractRepository repository = new InMemoryContractRepository();
        LedgerApplicationService service = new LedgerApplicationService(repository);
        OperationResult opening = BondOperations.opening(id("app-open"), DATE, PORTFOLIO, RUB, money("100"));

        LedgerSnapshot first = service.apply(PORTFOLIO, false, opening);
        LedgerCommit firstCommit = repository.lastCommit;
        assertEquals(1, first.version(), "first commit initializes version");
        assertEquals(1, first.lastSequence(), "first commit initializes sequence");
        assertEquals(0, firstCommit.expectedVersion(), "initial commit uses explicit absent version zero");
        assertEquals(Ledger.open(PORTFOLIO, false).state(), firstCommit.before(),
                "initial commit uses an explicit empty ledger with supplied cash policy");
        assertEquals(Ledger.open(PORTFOLIO, false).apply(opening).state(), firstCommit.after(),
                "service forwards the exact operation-derived after-state");

        OperationResult deposit = BondOperations.deposit(id("app-deposit"), DATE, PORTFOLIO, RUB, money("25"));
        LedgerSnapshot second = service.apply(PORTFOLIO, false, deposit);
        assertEquals(1, repository.lastCommit.expectedVersion(), "next operation uses the loaded expected version");
        assertEquals(2, second.version(), "next operation increments the version");
        assertEquals(2, second.lastSequence(), "per-portfolio sequence increases once per accepted operation");
        assertEquals(Ledger.open(PORTFOLIO, false).apply(opening).apply(deposit).state(), second.state(),
                "service result equals direct immutable domain application");
        List<OperationRecord> journal = repository.operations(PORTFOLIO);
        assertEquals(2, journal.size(), "journal contains both operations");
        assertEquals(1, journal.get(0).sequence(), "journal sequence begins at one");
        assertEquals(2, journal.get(1).sequence(),
                "journal keeps same-date operations in sequence order");
        assertEquals(opening.operation(), journal.get(0).operation(), "journal records the opening operation");
        assertEquals(deposit.operation(), journal.get(1).operation(), "journal records the deposit operation");
    }

    @Test
    void serviceRejectsDuplicateBeforeCommit() {
        InMemoryContractRepository repository = new InMemoryContractRepository();
        LedgerApplicationService service = new LedgerApplicationService(repository);
        OperationResult opening = BondOperations.opening(id("app-duplicate"), DATE, PORTFOLIO, RUB, money("100"));
        service.apply(PORTFOLIO, false, opening);
        int commitsBeforeDuplicate = repository.commitCalls;
        assertThrows(DuplicateOperationException.class, () -> service.apply(PORTFOLIO, false, opening),
                "duplicate operation IDs surface as a typed error");
        assertEquals(commitsBeforeDuplicate, repository.commitCalls, "duplicate operation is rejected before commit");
    }

    @Test
    void serviceRejectsPersistedPolicyChangeWithoutCommit() {
        InMemoryContractRepository repository = new InMemoryContractRepository();
        LedgerApplicationService service = new LedgerApplicationService(repository);
        OperationResult opening = BondOperations.opening(id("app-policy-open"), DATE, PORTFOLIO, RUB, money("100"));
        LedgerSnapshot snapshot = service.apply(PORTFOLIO, false, opening);
        int commitsBeforePolicyMismatch = repository.commitCalls;
        assertThrows(IllegalStateException.class,
                () -> service.apply(PORTFOLIO, true,
                        BondOperations.deposit(id("policy-mismatch"), DATE.plusDays(1), PORTFOLIO, RUB, money("1"))),
                "service never silently changes persisted cash policy");
        assertEquals(commitsBeforePolicyMismatch, repository.commitCalls,
                "policy mismatch does not attempt a commit");
        assertEquals(Optional.of(snapshot), repository.snapshot, "policy mismatch does not mutate the snapshot");
    }

    @Test
    void repositoryCommitFailureDoesNotMutateRepository() {
        InMemoryContractRepository commitFailureRepository = new InMemoryContractRepository();
        commitFailureRepository.failNextCommit = true;
        LedgerApplicationService commitFailureService = new LedgerApplicationService(commitFailureRepository);
        OperationResult opening = BondOperations.opening(id("failed-open"), DATE, PORTFOLIO, RUB, money("10"));
        assertThrows(IllegalStateException.class,
                () -> commitFailureService.apply(PORTFOLIO, false, opening), "repository commit failure propagates");
        assertTrue(commitFailureRepository.snapshot.isEmpty(), "failed atomic commit leaves repository state unchanged");
        assertTrue(commitFailureRepository.operations(PORTFOLIO).isEmpty(),
                "failed atomic commit leaves the journal unchanged");
    }

    @Test
    void domainApplyFailureDoesNotCommit() {
        InMemoryContractRepository applyFailureRepository = new InMemoryContractRepository();
        LedgerApplicationService applyFailureService = new LedgerApplicationService(applyFailureRepository);
        OperationResult overdraft = BondOperations.withdrawal(id("failed-withdrawal"), DATE,
                PORTFOLIO, RUB, money("10"));
        assertThrows(IllegalStateException.class,
                () -> applyFailureService.apply(PORTFOLIO, false, overdraft),
                "domain apply failure is returned without committing");
        assertEquals(0, applyFailureRepository.commitCalls, "failed domain apply never attempts commit");
        assertTrue(applyFailureRepository.snapshot.isEmpty(), "failed domain apply never mutates repository");
    }

    @Test
    void commitRejectsUnappliedReplacementState() {
        LedgerState empty = Ledger.open(PORTFOLIO, false).state();
        OperationResult opening = BondOperations.opening(id("commit-open"), DATE, PORTFOLIO, RUB, money("10"));
        assertThrows(IllegalArgumentException.class,
                () -> new LedgerCommit(0, empty, opening, empty),
                "commit cannot inject an unrelated after-state");
        Ledger populated = Ledger.open(PORTFOLIO, false).apply(opening);
        assertThrows(IllegalArgumentException.class,
                () -> new LedgerCommit(0, populated.state(),
                        BondOperations.deposit(id("commit-deposit"), DATE.plusDays(1), PORTFOLIO, RUB, money("1")),
                        populated.state()),
                "version zero cannot initialize from a nonempty ledger");
    }

    @Test
    void repositoryContractsAreTypedAndConfigIsImmutable() {
        assertThrows(IllegalArgumentException.class, () -> new LedgerSnapshot(PORTFOLIO, 0, 0,
                Ledger.open(PORTFOLIO, false).state()), "empty portfolio is represented by Optional.empty");
        assertEquals(5, new LedgerConcurrencyException(PORTFOLIO, 4, 5).actualVersion(),
                "version conflict exposes actual version");
        assertEquals(id("duplicate"), new DuplicateOperationException(PORTFOLIO, id("duplicate")).operation(),
                "duplicate operation has a typed identity");
        Map<String, String> settings = new java.util.LinkedHashMap<>();
        settings.put("path", "data.db");
        RepositoryConfig config = new RepositoryConfig(settings);
        settings.clear();
        assertEquals(Map.of("path", "data.db"), config.settings(), "repository config copies settings");
        assertThrows(UnsupportedOperationException.class, () -> config.settings().clear(),
                "repository config settings are immutable");
    }

    private static OperationId id(String value) { return new OperationId(value); }
    private static Money money(String amount) { return new Money(new BigDecimal(amount), RUB); }

    private static final class InMemoryContractRepository implements LedgerRepository {
        private Optional<LedgerSnapshot> snapshot = Optional.empty();
        private List<OperationRecord> journal = new ArrayList<>();
        private LedgerCommit lastCommit;
        private int commitCalls;
        private boolean failNextCommit;

        @Override
        public Optional<LedgerSnapshot> load(PortfolioId portfolio) {
            if (snapshot.isPresent() && !snapshot.orElseThrow().portfolio().equals(portfolio)) {
                return Optional.empty();
            }
            return snapshot;
        }

        @Override
        public List<OperationRecord> operations(PortfolioId portfolio) {
            if (snapshot.isPresent() && !snapshot.orElseThrow().portfolio().equals(portfolio)) {
                return List.of();
            }
            return List.copyOf(journal);
        }

        @Override
        public LedgerSnapshot commit(LedgerCommit commit) {
            commitCalls++;
            if (failNextCommit) {
                failNextCommit = false;
                throw new IllegalStateException("deliberate commit failure");
            }
            long actualVersion = snapshot.map(LedgerSnapshot::version).orElse(0L);
            if (commit.expectedVersion() != actualVersion) {
                throw new LedgerConcurrencyException(commit.portfolio(), commit.expectedVersion(), actualVersion);
            }
            if (snapshot.isPresent()) {
                LedgerSnapshot current = snapshot.orElseThrow();
                if (!current.state().equals(commit.before())) {
                    throw new LedgerConcurrencyException(commit.portfolio(), commit.expectedVersion(), actualVersion);
                }
                if (current.state().operationIds().contains(commit.operation().operation())) {
                    throw new DuplicateOperationException(commit.portfolio(), commit.operation().operation());
                }
            } else if (!commit.before().equals(Ledger.open(commit.portfolio(),
                    commit.before().allowNegativeCash()).state())) {
                throw new IllegalArgumentException("First commit must start from an empty state");
            }
            long nextSequence = snapshot.map(current -> Math.addExact(current.lastSequence(), 1)).orElse(1L);
            LedgerSnapshot next = new LedgerSnapshot(commit.portfolio(), Math.addExact(actualVersion, 1),
                    nextSequence, commit.after());
            OperationRecord record = OperationRecord.from(commit.operation(), nextSequence);
            List<OperationRecord> nextJournal = new ArrayList<>(journal);
            nextJournal.add(record);
            snapshot = Optional.of(next);
            journal = nextJournal;
            lastCommit = commit;
            return next;
        }
    }
}
