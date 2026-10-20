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
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceMemoryContractTests {
    private static final Currency RUB = Currency.getInstance("RUB");
    private static final Currency USD = Currency.getInstance("USD");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);

    @Test
    void factoryAndEmptyLoad() {
        InMemoryRepositoryFactory factory = new InMemoryRepositoryFactory();
        PortfolioId portfolio = portfolio("factory");
        LedgerRepository first = factory.create(RepositoryConfig.empty());
        LedgerRepository second = factory.create(RepositoryConfig.empty());
        assertEquals("memory", factory.providerId(), "factory has stable memory provider ID");
        assertTrue(first.load(portfolio).isEmpty() && first.operations(portfolio).isEmpty(),
                "new repository begins empty");
        assertTrue(second.load(portfolio).isEmpty(), "each factory call returns an isolated repository");
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(new RepositoryConfig(Map.of("path", "ignored"))),
                "unsupported memory settings are rejected explicitly");
        assertEquals(factory.providerId(), new InMemoryRepositoryFactory().providerId(),
                "public no-argument factory has consistent identity");
    }

    @Test
    void richAccountingRoundTripAndExactValues() {
        PortfolioId portfolio = portfolio("rich-chain");
        LedgerRepository repository = repository();
        LedgerApplicationService service = new LedgerApplicationService(repository);
        Ledger direct = Ledger.open(portfolio, false);

        OperationResult opening = BondOperations.opening(id("chain-open-rub"), DAY,
                portfolio, RUB, money("10000.000", RUB));
        direct = direct.apply(opening);
        LedgerSnapshot snapshot = service.apply(portfolio, false, opening);
        assertEquals(direct.state(), snapshot.state(), "RUB opening matches direct apply");

        OperationResult usdOpening = BondOperations.opening(id("chain-open-usd"), DAY,
                portfolio, USD, money("12.345", USD));
        direct = direct.apply(usdOpening);
        snapshot = service.apply(portfolio, false, usdOpening);
        assertEquals(direct.state(), snapshot.state(), "same-date second-currency opening matches direct apply");

        Position empty = Position.empty(new PositionId(portfolio, new SecurityId("SU-MEMORY-1")), RUB);
        OperationResult buy = BondOperations.buy(id("chain-buy"), DAY.plusDays(1), empty,
                10, money("95.005", RUB), money("2.005", RUB), money("100.005", RUB), money("0.015", RUB));
        direct = direct.apply(buy);
        snapshot = service.apply(portfolio, false, buy);
        assertEquals(direct.state(), snapshot.state(), "buy snapshot restores to direct ledger state");

        OperationResult coupon = BondOperations.coupon(id("chain-coupon"), DAY.plusDays(2),
                buy.positionAfter().orElseThrow(), money("25.075", RUB), Optional.of(money("0.005", RUB)));
        direct = direct.apply(coupon);
        snapshot = service.apply(portfolio, false, coupon);
        assertEquals(direct.state(), snapshot.state(), "coupon snapshot restores to direct ledger state");

        OperationResult amortization = BondOperations.amortization(id("chain-amort"), DAY.plusDays(3),
                coupon.positionAfter().orElseThrow(), money("500.025", RUB), money("500.005", RUB), Optional.empty());
        direct = direct.apply(amortization);
        snapshot = service.apply(portfolio, false, amortization);
        assertEquals(direct.state(), snapshot.state(), "amortization snapshot restores to direct ledger state");

        OperationResult sale = BondOperations.sell(id("chain-sell"), DAY.plusDays(4),
                amortization.positionAfter().orElseThrow(), 10, money("100.005", RUB),
                money("0.005", RUB), money("0.005", RUB), Optional.empty());
        direct = direct.apply(sale);
        snapshot = service.apply(portfolio, false, sale);
        Ledger restored = Ledger.restore(repository.load(portfolio).orElseThrow().state());
        assertTrue(snapshot.state().equals(direct.state()) && restored.state().equals(direct.state()),
                "buy/coupon/amortization/sell load-restore chain matches direct apply");
        assertEquals(6, snapshot.version(), "version advances once per operation");
        assertEquals(6, snapshot.lastSequence(), "per-portfolio sequence advances once per operation");
        assertEquals(RUB, snapshot.state().balances().get(account(portfolio, AccountKind.CASH, RUB)).currency(),
                "RUB account retains its currency identity");
        assertTrue(snapshot.state().balances().get(account(portfolio, AccountKind.CASH, USD))
                        .amount().compareTo(new BigDecimal("12.345")) == 0,
                "exact USD BigDecimal value survives snapshot load");

        List<OperationRecord> records = repository.operations(portfolio);
        assertEquals(6, records.size(), "journal contains each committed operation");
        for (int i = 0; i < records.size(); i++) {
            assertEquals(i + 1L, records.get(i).sequence(), "journal sequence is contiguous and monotonic");
        }
        assertTrue(records.get(0).date().equals(DAY) && records.get(1).date().equals(DAY)
                        && records.get(0).sequence() < records.get(1).sequence(),
                "same-date records are ordered by sequence");
    }

    @Test
    void duplicateCommitsAreRejectedWithoutMutation() {
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
        assertThrows(DuplicateOperationException.class, () -> repository.commit(originalCommit),
                "duplicate is detected before stale expected-version rejection");
        assertTrue(repository.load(portfolio).orElseThrow().equals(beforeDuplicate)
                        && repository.operations(portfolio).equals(journalBeforeDuplicate),
                "duplicate commit leaves snapshot and journal unchanged");
        assertThrows(DuplicateOperationException.class, () -> service.apply(portfolio, false, opening),
                "duplicate after load uses typed duplicate error");
    }

    @Test
    void staleCasIsRejectedWithoutMutation() {
        PortfolioId portfolio = portfolio("stale-cas");
        LedgerRepository repository = repository();
        LedgerApplicationService service = new LedgerApplicationService(repository);
        OperationResult opening = BondOperations.opening(id("stale-open"), DAY,
                portfolio, RUB, money("100", RUB));
        service.apply(portfolio, false, opening);
        LedgerSnapshot current = repository.load(portfolio).orElseThrow();
        OperationResult staleOperation = BondOperations.deposit(id("stale-candidate"), DAY.plusDays(1),
                portfolio, RUB, money("1", RUB));
        LedgerCommit stale = transition(current, staleOperation);
        OperationResult winner = BondOperations.deposit(id("cas-winner"), DAY.plusDays(1),
                portfolio, RUB, money("2", RUB));
        service.apply(portfolio, false, winner);
        LedgerSnapshot afterWinner = repository.load(portfolio).orElseThrow();
        List<OperationRecord> journalAfterWinner = repository.operations(portfolio);
        LedgerConcurrencyException conflict = assertThrows(LedgerConcurrencyException.class,
                () -> repository.commit(stale), "stale expected version is rejected");
        assertEquals(1, conflict.expectedVersion(), "CAS error exposes expected version");
        assertEquals(2, conflict.actualVersion(), "CAS error exposes actual version");
        assertTrue(repository.load(portfolio).orElseThrow().equals(afterWinner)
                        && repository.operations(portfolio).equals(journalAfterWinner),
                "stale commit has no partial snapshot or journal effect");
    }

    @Test
    void beforeAfterAndPolicyValidation() {
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
        assertThrows(LedgerConcurrencyException.class, () -> repository.commit(forgedBefore),
                "matching version cannot authorize a forged before-state or policy");
        assertEquals(actual, repository.load(portfolio).orElseThrow(),
                "before-state mismatch leaves repository unchanged");

        assertThrows(IllegalArgumentException.class,
                () -> new LedgerCommit(actual.version(), actual.state(), deposit, actual.state()),
                "commit rejects an after-state unrelated to Ledger.apply");
        assertThrows(IllegalStateException.class,
                () -> service.apply(portfolio, true, deposit),
                "service does not silently change a persisted cash policy");
        assertTrue(repository.load(portfolio).orElseThrow().equals(actual)
                        && repository.operations(portfolio).size() == 1,
                "invalid after-state and policy attempts do not mutate persisted state");
    }

    @Test
    void sameDateSequenceBackdateAndImmutableReads() {
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
        assertEquals(3, snapshot.version(), "same-date commits preserve monotonic version");
        assertEquals(3, snapshot.lastSequence(), "same-date commits preserve monotonic sequence");
        assertTrue(journal.get(1).operation().equals(id("same-deposit-a"))
                        && journal.get(2).operation().equals(id("same-deposit-b")),
                "same-date journal order follows committed sequence");

        assertThrows(IllegalStateException.class,
                () -> service.apply(portfolio, false, BondOperations.deposit(id("backdated"),
                        DAY.minusDays(1), portfolio, RUB, money("1", RUB))),
                "backdated operation is rejected by the unchanged core ledger invariant");
        assertTrue(repository.load(portfolio).orElseThrow().equals(snapshot)
                        && repository.operations(portfolio).equals(journal),
                "backdate rejection leaves repository state unchanged");

        assertThrows(UnsupportedOperationException.class, () -> journal.clear(),
                "journal list is immutable");
        assertThrows(UnsupportedOperationException.class, () -> snapshot.state().balances().clear(),
                "loaded snapshot balances are immutable");
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.state().postingsByDate().firstEntry().getValue().clear(),
                "loaded snapshot history lists are immutable");
        assertEquals(2, beforeLast.lastSequence(), "previous immutable snapshot remains unchanged");
    }

    @Test
    void portfolioIsolation() {
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
        assertEquals(1, firstSnapshot.version(), "first portfolio version is independent");
        assertEquals(1, secondSnapshot.version(), "second portfolio version is independent");
        assertEquals(1, firstSnapshot.lastSequence(), "first portfolio sequence is independent");
        assertEquals(1, secondSnapshot.lastSequence(), "second portfolio sequence is independent");
        assertTrue(firstSnapshot.state().balances().get(account(first, AccountKind.CASH, RUB)).equals(money("10", RUB))
                        && secondSnapshot.state().balances().get(account(second, AccountKind.CASH, RUB))
                        .equals(money("20", RUB)),
                "same operation ID in different portfolios does not collide");
        assertEquals(1, repository.operations(first).size(), "first journal query is portfolio-scoped");
        assertEquals(1, repository.operations(second).size(), "second journal query is portfolio-scoped");
        assertTrue(factory.create(RepositoryConfig.empty()).load(first).isEmpty(),
                "separate repository instances do not share state");
    }

    @Test
    void concurrentCommitCasIsAtomic() throws Exception {
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
            assertTrue(ready.await(10, TimeUnit.SECONDS), "both same-version writers reach barrier");
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
        assertEquals(1, successes.get(), "exactly one concurrent same-version commit succeeds");
        assertEquals(1, conflicts.get(), "the other concurrent same-version commit conflicts");
        assertEquals(2, after.version(), "concurrent CAS publishes one new snapshot version");
        assertEquals(2, after.lastSequence(), "concurrent CAS publishes one new sequence");
        assertEquals(2, journal.size(), "concurrent CAS publishes one journal entry");
        BigDecimal winningCash = after.state().balances().get(account(portfolio, AccountKind.CASH, RUB)).amount();
        assertTrue(after.state().operationIds().size() == 2
                        && (winningCash.compareTo(new BigDecimal("101")) == 0
                        || winningCash.compareTo(new BigDecimal("102")) == 0),
                "losing writer contributes no partial operation or balance");
        assertTrue(journal.get(1).operation().equals(id("parallel-one"))
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
}
