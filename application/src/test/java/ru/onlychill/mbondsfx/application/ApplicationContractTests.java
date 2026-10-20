package ru.onlychill.mbondsfx.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

public final class ApplicationContractTests {
    private static final Currency RUB = Currency.getInstance("RUB");
    private static final PortfolioId PORTFOLIO = new PortfolioId("application-contract");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 20);
    private static int checks;

    private ApplicationContractTests() { }

    public static void main(String[] args) {
        testReferenceDataPortIsSparseAndImmutable();
        testServiceForwardsVersionedAppliedTransitions();
        testFailureDoesNotMutateRepository();
        testCommitRejectsUnappliedReplacementState();
        testRepositoryContractsAreTypedAndConfigIsImmutable();
        System.out.println("ApplicationContractTests: " + checks + " checks passed");
    }

    private static void testReferenceDataPortIsSparseAndImmutable() {
        SecurityId security = new SecurityId("SU0000000001");
        BondReference reference = new BondReference(security, "source-A", Optional.empty(),
                Optional.of(new BondReference.ReportedValue(new BigDecimal("1000.000"), "RUB/issue")),
                Optional.of(new BondReference.NextCoupon(Optional.of(DATE), Optional.empty())));
        BondReferenceDataSource source = requested -> requested.equals(security)
                ? Optional.of(reference) : Optional.empty();
        check(source.reference(security).orElseThrow().equals(reference),
                "source returns sparse source-attributed facts");
        check(reference.observedOn().isEmpty() && reference.faceValuePerUnit().orElseThrow().unit().equals("RUB/issue"),
                "unreported observation date remains absent and unit text is preserved");
        check(reference.faceValuePerUnit().orElseThrow().value().equals(new BigDecimal("1000.000")),
                "reported BigDecimal precision is not rounded or normalized");
        expectThrows(IllegalArgumentException.class,
                () -> new BondReference(security, " ", Optional.empty(), Optional.empty(), Optional.empty()),
                "source ID is required");
        expectThrows(IllegalArgumentException.class,
                () -> new BondReference.ReportedValue(BigDecimal.ONE, " "), "reported unit is required");
        expectThrows(IllegalArgumentException.class,
                () -> new BondReference.NextCoupon(Optional.empty(), Optional.empty()),
                "empty next-coupon record is not allowed");
    }

    private static void testServiceForwardsVersionedAppliedTransitions() {
        InMemoryContractRepository repository = new InMemoryContractRepository();
        LedgerApplicationService service = new LedgerApplicationService(repository);
        OperationResult opening = BondOperations.opening(id("app-open"), DATE, PORTFOLIO, RUB, money("100"));

        LedgerSnapshot first = service.apply(PORTFOLIO, false, opening);
        LedgerCommit firstCommit = repository.lastCommit;
        check(first.version() == 1 && first.lastSequence() == 1, "first commit initializes version and sequence");
        check(firstCommit.expectedVersion() == 0, "initial commit uses explicit absent version zero");
        check(firstCommit.before().equals(Ledger.open(PORTFOLIO, false).state()),
                "initial commit uses an explicit empty ledger with supplied cash policy");
        check(firstCommit.after().equals(Ledger.open(PORTFOLIO, false).apply(opening).state()),
                "service forwards the exact operation-derived after-state");

        OperationResult deposit = BondOperations.deposit(id("app-deposit"), DATE, PORTFOLIO, RUB, money("25"));
        LedgerSnapshot second = service.apply(PORTFOLIO, false, deposit);
        check(repository.lastCommit.expectedVersion() == 1 && second.version() == 2,
                "next operation uses the loaded expected version");
        check(second.lastSequence() == 2, "per-portfolio sequence increases once per accepted operation");
        check(second.state().equals(Ledger.open(PORTFOLIO, false).apply(opening).apply(deposit).state()),
                "service result equals direct immutable domain application");
        List<OperationRecord> journal = repository.operations(PORTFOLIO);
        check(journal.size() == 2 && journal.get(0).sequence() == 1 && journal.get(1).sequence() == 2,
                "journal keeps same-date operations in sequence order");
        check(journal.get(0).operation().equals(opening.operation())
                        && journal.get(1).operation().equals(deposit.operation()),
                "journal records are derived from the applied domain operations");

        int commitsBeforeDuplicate = repository.commitCalls;
        expectThrows(DuplicateOperationException.class, () -> service.apply(PORTFOLIO, false, opening),
                "duplicate operation IDs surface as a typed error");
        check(repository.commitCalls == commitsBeforeDuplicate, "duplicate operation is rejected before commit");

        int commitsBeforePolicyMismatch = repository.commitCalls;
        expectThrows(IllegalStateException.class,
                () -> service.apply(PORTFOLIO, true,
                        BondOperations.deposit(id("policy-mismatch"), DATE.plusDays(1), PORTFOLIO, RUB, money("1"))),
                "service never silently changes persisted cash policy");
        check(repository.commitCalls == commitsBeforePolicyMismatch && repository.snapshot.equals(Optional.of(second)),
                "policy mismatch does not attempt or mutate a commit");
    }

    private static void testFailureDoesNotMutateRepository() {
        InMemoryContractRepository commitFailureRepository = new InMemoryContractRepository();
        commitFailureRepository.failNextCommit = true;
        LedgerApplicationService commitFailureService = new LedgerApplicationService(commitFailureRepository);
        OperationResult opening = BondOperations.opening(id("failed-open"), DATE, PORTFOLIO, RUB, money("10"));
        expectThrows(IllegalStateException.class,
                () -> commitFailureService.apply(PORTFOLIO, false, opening), "repository commit failure propagates");
        check(commitFailureRepository.snapshot.isEmpty() && commitFailureRepository.operations(PORTFOLIO).isEmpty(),
                "failed atomic commit leaves repository state and journal unchanged");

        InMemoryContractRepository applyFailureRepository = new InMemoryContractRepository();
        LedgerApplicationService applyFailureService = new LedgerApplicationService(applyFailureRepository);
        OperationResult overdraft = BondOperations.withdrawal(id("failed-withdrawal"), DATE,
                PORTFOLIO, RUB, money("10"));
        expectThrows(IllegalStateException.class,
                () -> applyFailureService.apply(PORTFOLIO, false, overdraft),
                "domain apply failure is returned without committing");
        check(applyFailureRepository.commitCalls == 0 && applyFailureRepository.snapshot.isEmpty(),
                "failed domain apply never mutates repository");
    }

    private static void testCommitRejectsUnappliedReplacementState() {
        LedgerState empty = Ledger.open(PORTFOLIO, false).state();
        OperationResult opening = BondOperations.opening(id("commit-open"), DATE, PORTFOLIO, RUB, money("10"));
        expectThrows(IllegalArgumentException.class,
                () -> new LedgerCommit(0, empty, opening, empty),
                "commit cannot inject an unrelated after-state");
        Ledger populated = Ledger.open(PORTFOLIO, false).apply(opening);
        expectThrows(IllegalArgumentException.class,
                () -> new LedgerCommit(0, populated.state(),
                        BondOperations.deposit(id("commit-deposit"), DATE.plusDays(1), PORTFOLIO, RUB, money("1")),
                        populated.state()),
                "version zero cannot initialize from a nonempty ledger");
    }

    private static void testRepositoryContractsAreTypedAndConfigIsImmutable() {
        expectThrows(IllegalArgumentException.class, () -> new LedgerSnapshot(PORTFOLIO, 0, 0,
                Ledger.open(PORTFOLIO, false).state()), "empty portfolio is represented by Optional.empty");
        check(new LedgerConcurrencyException(PORTFOLIO, 4, 5).actualVersion() == 5,
                "version conflict exposes actual version");
        check(new DuplicateOperationException(PORTFOLIO, id("duplicate")).operation().equals(id("duplicate")),
                "duplicate operation has a typed identity");
        Map<String, String> settings = new java.util.LinkedHashMap<>();
        settings.put("path", "data.db");
        RepositoryConfig config = new RepositoryConfig(settings);
        settings.clear();
        check(config.settings().equals(Map.of("path", "data.db")), "repository config copies settings");
        expectThrows(UnsupportedOperationException.class, () -> config.settings().clear(),
                "repository config settings are immutable");
    }

    private static OperationId id(String value) { return new OperationId(value); }
    private static Money money(String amount) { return new Money(new BigDecimal(amount), RUB); }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static <T extends Throwable> T expectThrows(Class<T> expected, Runnable action, String message) {
        try {
            action.run();
        } catch (Throwable thrown) {
            if (expected.isInstance(thrown)) {
                checks++;
                return expected.cast(thrown);
            }
            throw new AssertionError(message + ": expected " + expected.getName()
                    + " but got " + thrown.getClass().getName(), thrown);
        }
        throw new AssertionError(message + ": expected " + expected.getName());
    }

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
