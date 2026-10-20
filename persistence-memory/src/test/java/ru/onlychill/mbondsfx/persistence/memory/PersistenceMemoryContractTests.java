package ru.onlychill.mbondsfx.persistence.memory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import ru.onlychill.mbondsfx.core.id.PositionId;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.core.ledger.Account;
import ru.onlychill.mbondsfx.core.ledger.AccountKind;
import ru.onlychill.mbondsfx.core.ledger.BondOperations;
import ru.onlychill.mbondsfx.core.ledger.Ledger;
import ru.onlychill.mbondsfx.core.ledger.LedgerState;
import ru.onlychill.mbondsfx.core.ledger.OperationResult;
import ru.onlychill.mbondsfx.core.ledger.Position;
import ru.onlychill.mbondsfx.core.money.Money;

public final class PersistenceMemoryContractTests {
    private static final Currency RUB = Currency.getInstance("RUB");
    private static final Currency USD = Currency.getInstance("USD");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);
    private static int checks;

    private PersistenceMemoryContractTests() { }

    public static void main(String[] args) throws Exception {
        testFactoryAndEmptyLoad();
        testRichAccountingRoundTripAndExactValues();
        testDuplicateAndStaleCas();
        testBeforeAfterAndPolicyValidation();
        testSameDateSequenceBackdateAndImmutableReads();
        testPortfolioIsolation();
        testConcurrentCommitCasIsAtomic();
        System.out.println("PersistenceMemoryContractTests: " + checks + " checks passed");
    }

    private static void testFactoryAndEmptyLoad() {
        InMemoryRepositoryFactory factory = new InMemoryRepositoryFactory();
        PortfolioId portfolio = portfolio("factory");
        LedgerRepository first = factory.create(RepositoryConfig.empty());
        LedgerRepository second = factory.create(RepositoryConfig.empty());
        check(factory.providerId().equals("memory"), "factory has stable memory provider ID");
        check(first.load(portfolio).isEmpty() && first.operations(portfolio).isEmpty(),
                "new repository begins empty");
        check(second.load(portfolio).isEmpty(), "each factory call returns an isolated repository");
        expectThrows(IllegalArgumentException.class,
                () -> factory.create(new RepositoryConfig(Map.of("path", "ignored"))),
                "unsupported memory settings are rejected explicitly");
        check(new InMemoryRepositoryFactory().providerId().equals(factory.providerId()),
                "public no-argument factory has consistent identity");
    }

    private static void testRichAccountingRoundTripAndExactValues() {
        PortfolioId portfolio = portfolio("rich-chain");
        LedgerRepository repository = repository();
        LedgerApplicationService service = new LedgerApplicationService(repository);
        Ledger direct = Ledger.open(portfolio, false);

        OperationResult opening = BondOperations.opening(id("chain-open-rub"), DAY,
                portfolio, RUB, money("10000.000", RUB));
        direct = direct.apply(opening);
        LedgerSnapshot snapshot = service.apply(portfolio, false, opening);
        check(snapshot.state().equals(direct.state()), "RUB opening matches direct apply");

        OperationResult usdOpening = BondOperations.opening(id("chain-open-usd"), DAY,
                portfolio, USD, money("12.345", USD));
        direct = direct.apply(usdOpening);
        snapshot = service.apply(portfolio, false, usdOpening);
        check(snapshot.state().equals(direct.state()), "same-date second-currency opening matches direct apply");

        Position empty = Position.empty(new PositionId(portfolio, new SecurityId("SU-MEMORY-1")), RUB);
        OperationResult buy = BondOperations.buy(id("chain-buy"), DAY.plusDays(1), empty,
                10, money("95.005", RUB), money("2.005", RUB), money("100.005", RUB), money("0.015", RUB));
        direct = direct.apply(buy);
        snapshot = service.apply(portfolio, false, buy);
        check(snapshot.state().equals(direct.state()), "buy snapshot restores to direct ledger state");

        OperationResult coupon = BondOperations.coupon(id("chain-coupon"), DAY.plusDays(2),
                buy.positionAfter().orElseThrow(), money("25.075", RUB), Optional.of(money("0.005", RUB)));
        direct = direct.apply(coupon);
        snapshot = service.apply(portfolio, false, coupon);
        check(snapshot.state().equals(direct.state()), "coupon snapshot restores to direct ledger state");

        OperationResult amortization = BondOperations.amortization(id("chain-amort"), DAY.plusDays(3),
                coupon.positionAfter().orElseThrow(), money("500.025", RUB), money("500.005", RUB), Optional.empty());
        direct = direct.apply(amortization);
        snapshot = service.apply(portfolio, false, amortization);
        check(snapshot.state().equals(direct.state()), "amortization snapshot restores to direct ledger state");

        OperationResult sale = BondOperations.sell(id("chain-sell"), DAY.plusDays(4),
                amortization.positionAfter().orElseThrow(), 10, money("100.005", RUB),
                money("0.005", RUB), money("0.005", RUB), Optional.empty());
        direct = direct.apply(sale);
        snapshot = service.apply(portfolio, false, sale);
        Ledger restored = Ledger.restore(repository.load(portfolio).orElseThrow().state());
        check(snapshot.state().equals(direct.state()) && restored.state().equals(direct.state()),
                "buy/coupon/amortization/sell load-restore chain matches direct apply");
        check(snapshot.version() == 6 && snapshot.lastSequence() == 6,
                "version and per-portfolio sequence advance once per operation");
        check(snapshot.state().balances().get(account(portfolio, AccountKind.CASH, RUB))
                        .currency().equals(RUB), "RUB account retains its currency identity");
        check(snapshot.state().balances().get(account(portfolio, AccountKind.CASH, USD))
                        .amount().compareTo(new BigDecimal("12.345")) == 0,
                "exact USD BigDecimal value survives snapshot load");

        List<OperationRecord> records = repository.operations(portfolio);
        check(records.size() == 6, "journal contains each committed operation");
        for (int i = 0; i < records.size(); i++) {
            check(records.get(i).sequence() == i + 1L, "journal sequence is contiguous and monotonic");
        }
        check(records.get(0).date().equals(DAY) && records.get(1).date().equals(DAY)
                        && records.get(0).sequence() < records.get(1).sequence(),
                "same-date records are ordered by sequence");
    }

    private static void testDuplicateAndStaleCas() {
        PortfolioId portfolio = portfolio("duplicate-cas");
        LedgerRepository repository = repository();
        LedgerApplicationService service = new LedgerApplicationService(repository);
        OperationResult opening = BondOperations.opening(id("duplicate-open"), DAY,
                portfolio, RUB, money("100", RUB));
        LedgerState empty = Ledger.open(portfolio, false).state();
        LedgerState afterOpen = Ledger.restore(empty).apply(opening).state();
        LedgerCommit originalCommit = new LedgerCommit(0, empty, opening, afterOpen);
        repository.commit(originalCommit);
        LedgerSnapshot beforeDuplicate = repository.load(portfolio).orElseThrow();
        List<OperationRecord> journalBeforeDuplicate = repository.operations(portfolio);
        expectThrows(DuplicateOperationException.class, () -> repository.commit(originalCommit),
                "duplicate is detected before stale expected-version rejection");
        check(repository.load(portfolio).orElseThrow().equals(beforeDuplicate)
                        && repository.operations(portfolio).equals(journalBeforeDuplicate),
                "duplicate commit leaves snapshot and journal unchanged");
        expectThrows(DuplicateOperationException.class, () -> service.apply(portfolio, false, opening),
                "duplicate after load uses typed duplicate error");

        LedgerSnapshot current = repository.load(portfolio).orElseThrow();
        OperationResult staleOperation = BondOperations.deposit(id("stale-candidate"), DAY.plusDays(1),
                portfolio, RUB, money("1", RUB));
        LedgerCommit stale = transition(current, staleOperation);
        OperationResult winner = BondOperations.deposit(id("cas-winner"), DAY.plusDays(1),
                portfolio, RUB, money("2", RUB));
        service.apply(portfolio, false, winner);
        LedgerSnapshot afterWinner = repository.load(portfolio).orElseThrow();
        List<OperationRecord> journalAfterWinner = repository.operations(portfolio);
        LedgerConcurrencyException conflict = expectThrows(LedgerConcurrencyException.class,
                () -> repository.commit(stale), "stale expected version is rejected");
        check(conflict.expectedVersion() == 1 && conflict.actualVersion() == 2,
                "CAS error exposes expected and actual versions");
        check(repository.load(portfolio).orElseThrow().equals(afterWinner)
                        && repository.operations(portfolio).equals(journalAfterWinner),
                "stale commit has no partial snapshot or journal effect");
    }

    private static void testBeforeAfterAndPolicyValidation() {
        PortfolioId portfolio = portfolio("forged-before");
        LedgerRepository repository = repository();
        LedgerApplicationService service = new LedgerApplicationService(repository);
        OperationResult opening = BondOperations.opening(id("forged-open"), DAY,
                portfolio, RUB, money("100", RUB));
        service.apply(portfolio, false, opening);
        LedgerSnapshot actual = repository.load(portfolio).orElseThrow();
        OperationResult deposit = BondOperations.deposit(id("forged-deposit"), DAY.plusDays(1),
                portfolio, RUB, money("1", RUB));

        LedgerState wrongPolicyBefore = Ledger.open(portfolio, true).apply(opening).state();
        LedgerState wrongPolicyAfter = Ledger.restore(wrongPolicyBefore).apply(deposit).state();
        LedgerCommit forgedBefore = new LedgerCommit(actual.version(), wrongPolicyBefore, deposit, wrongPolicyAfter);
        expectThrows(LedgerConcurrencyException.class, () -> repository.commit(forgedBefore),
                "matching version cannot authorize a forged before-state or policy");
        check(repository.load(portfolio).orElseThrow().equals(actual), "before-state mismatch leaves repository unchanged");

        expectThrows(IllegalArgumentException.class,
                () -> new LedgerCommit(actual.version(), actual.state(), deposit, actual.state()),
                "commit rejects an after-state unrelated to Ledger.apply");
        expectThrows(IllegalStateException.class,
                () -> service.apply(portfolio, true, deposit),
                "service does not silently change a persisted cash policy");
        check(repository.load(portfolio).orElseThrow().equals(actual)
                        && repository.operations(portfolio).size() == 1,
                "invalid after-state and policy attempts do not mutate persisted state");
    }

    private static void testSameDateSequenceBackdateAndImmutableReads() {
        PortfolioId portfolio = portfolio("same-date");
        LedgerRepository repository = repository();
        LedgerApplicationService service = new LedgerApplicationService(repository);
        service.apply(portfolio, false,
                BondOperations.opening(id("same-open"), DAY, portfolio, RUB, money("10", RUB)));
        service.apply(portfolio, false,
                BondOperations.deposit(id("same-deposit-a"), DAY, portfolio, RUB, money("1", RUB)));
        LedgerSnapshot beforeLast = repository.load(portfolio).orElseThrow();
        service.apply(portfolio, false,
                BondOperations.deposit(id("same-deposit-b"), DAY, portfolio, RUB, money("2", RUB)));
        LedgerSnapshot snapshot = repository.load(portfolio).orElseThrow();
        List<OperationRecord> journal = repository.operations(portfolio);
        check(snapshot.version() == 3 && snapshot.lastSequence() == 3,
                "same-date commits preserve monotonic version and sequence");
        check(journal.get(1).operation().equals(id("same-deposit-a"))
                        && journal.get(2).operation().equals(id("same-deposit-b")),
                "same-date journal order follows committed sequence");

        expectThrows(IllegalStateException.class,
                () -> service.apply(portfolio, false, BondOperations.deposit(id("backdated"),
                        DAY.minusDays(1), portfolio, RUB, money("1", RUB))),
                "backdated operation is rejected by the unchanged core ledger invariant");
        check(repository.load(portfolio).orElseThrow().equals(snapshot)
                        && repository.operations(portfolio).equals(journal),
                "backdate rejection leaves repository state unchanged");

        expectThrows(UnsupportedOperationException.class, () -> journal.clear(),
                "journal list is immutable");
        expectThrows(UnsupportedOperationException.class, () -> snapshot.state().balances().clear(),
                "loaded snapshot balances are immutable");
        expectThrows(UnsupportedOperationException.class,
                () -> snapshot.state().postingsByDate().firstEntry().getValue().clear(),
                "loaded snapshot history lists are immutable");
        check(beforeLast.lastSequence() == 2, "previous immutable snapshot remains unchanged");
    }

    private static void testPortfolioIsolation() {
        InMemoryRepositoryFactory factory = new InMemoryRepositoryFactory();
        LedgerRepository repository = factory.create(RepositoryConfig.empty());
        LedgerApplicationService service = new LedgerApplicationService(repository);
        PortfolioId first = portfolio("isolated-first");
        PortfolioId second = portfolio("isolated-second");
        OperationId sharedId = id("same-operation-id");

        service.apply(first, false, BondOperations.opening(sharedId, DAY, first, RUB, money("10", RUB)));
        LedgerSnapshot firstSnapshot = repository.load(first).orElseThrow();
        service.apply(second, false, BondOperations.opening(sharedId, DAY, second, RUB, money("20", RUB)));
        LedgerSnapshot secondSnapshot = repository.load(second).orElseThrow();
        check(firstSnapshot.version() == 1 && secondSnapshot.version() == 1,
                "versions and sequences are independent per portfolio");
        check(firstSnapshot.state().balances().get(account(first, AccountKind.CASH, RUB)).equals(money("10", RUB))
                        && secondSnapshot.state().balances().get(account(second, AccountKind.CASH, RUB))
                        .equals(money("20", RUB)),
                "same operation ID in different portfolios does not collide");
        check(repository.operations(first).size() == 1 && repository.operations(second).size() == 1,
                "journal queries remain portfolio-scoped");
        check(factory.create(RepositoryConfig.empty()).load(first).isEmpty(),
                "separate repository instances do not share state");
    }

    private static void testConcurrentCommitCasIsAtomic() throws Exception {
        PortfolioId portfolio = portfolio("parallel-cas");
        LedgerRepository repository = repository();
        LedgerApplicationService service = new LedgerApplicationService(repository);
        service.apply(portfolio, false,
                BondOperations.opening(id("parallel-open"), DAY, portfolio, RUB, money("100", RUB)));
        LedgerSnapshot before = repository.load(portfolio).orElseThrow();

        OperationResult firstOperation = BondOperations.deposit(id("parallel-one"), DAY.plusDays(1),
                portfolio, RUB, money("1", RUB));
        OperationResult secondOperation = BondOperations.deposit(id("parallel-two"), DAY.plusDays(1),
                portfolio, RUB, money("2", RUB));
        LedgerCommit first = transition(before, firstOperation);
        LedgerCommit second = transition(before, secondOperation);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstResult = executor.submit(() -> contend(repository, first, ready, start, successes, conflicts));
            Future<?> secondResult = executor.submit(() -> contend(repository, second, ready, start, successes, conflicts));
            check(ready.await(10, TimeUnit.SECONDS), "both same-version writers reach barrier");
            start.countDown();
            firstResult.get(10, TimeUnit.SECONDS);
            secondResult.get(10, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        LedgerSnapshot after = repository.load(portfolio).orElseThrow();
        List<OperationRecord> journal = repository.operations(portfolio);
        check(successes.get() == 1 && conflicts.get() == 1,
                "exactly one concurrent same-version commit succeeds");
        check(after.version() == 2 && after.lastSequence() == 2 && journal.size() == 2,
                "concurrent CAS publishes one complete snapshot and journal entry");
        BigDecimal winningCash = after.state().balances().get(account(portfolio, AccountKind.CASH, RUB)).amount();
        check(after.state().operationIds().size() == 2
                        && (winningCash.compareTo(new BigDecimal("101")) == 0
                        || winningCash.compareTo(new BigDecimal("102")) == 0),
                "losing writer contributes no partial operation or balance");
        check(journal.get(1).operation().equals(id("parallel-one"))
                        || journal.get(1).operation().equals(id("parallel-two")),
                "journal contains only the winning concurrent operation");
    }

    private static void contend(LedgerRepository repository, LedgerCommit commit, CountDownLatch ready,
                                CountDownLatch start, AtomicInteger successes, AtomicInteger conflicts) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("commit start barrier timed out");
            }
            repository.commit(commit);
            successes.incrementAndGet();
        } catch (LedgerConcurrencyException expected) {
            conflicts.incrementAndGet();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("commit thread interrupted", interrupted);
        }
    }

    private static LedgerCommit transition(LedgerSnapshot before, OperationResult operation) {
        Ledger after = Ledger.restore(before.state()).apply(operation);
        return new LedgerCommit(before.version(), before.state(), operation, after.state());
    }

    private static LedgerRepository repository() {
        return new InMemoryRepositoryFactory().create(RepositoryConfig.empty());
    }

    private static Account account(PortfolioId portfolio, AccountKind kind, Currency currency) {
        return new Account(portfolio, kind, Optional.empty(), currency);
    }

    private static PortfolioId portfolio(String value) { return new PortfolioId(value); }
    private static OperationId id(String value) { return new OperationId(value); }
    private static Money money(String value, Currency currency) {
        return new Money(new BigDecimal(value), currency);
    }

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
}
