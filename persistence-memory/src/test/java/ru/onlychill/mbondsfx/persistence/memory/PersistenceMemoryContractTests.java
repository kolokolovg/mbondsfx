package ru.onlychill.mbondsfx.persistence.memory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import ru.onlychill.mbondsfx.application.port.DuplicateOperationException;
import ru.onlychill.mbondsfx.application.port.InvestmentAccount;
import ru.onlychill.mbondsfx.application.port.InvestmentAccountRepository;
import ru.onlychill.mbondsfx.application.port.LedgerCommit;
import ru.onlychill.mbondsfx.application.port.LedgerConcurrencyException;
import ru.onlychill.mbondsfx.application.port.LedgerRepository;
import ru.onlychill.mbondsfx.application.port.LedgerSnapshot;
import ru.onlychill.mbondsfx.application.port.OperationRecord;
import ru.onlychill.mbondsfx.application.port.RepositoryConfig;
import ru.onlychill.mbondsfx.application.service.CouponReceipt;
import ru.onlychill.mbondsfx.application.service.CouponReceiptService;
import ru.onlychill.mbondsfx.application.service.LedgerApplicationService;
import ru.onlychill.mbondsfx.application.service.TaxCorrectionService;
import ru.onlychill.mbondsfx.core.id.OperationId;
import ru.onlychill.mbondsfx.core.id.PortfolioId;
import ru.onlychill.mbondsfx.core.id.PositionId;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.core.ledger.Account;
import ru.onlychill.mbondsfx.core.ledger.AccountCode;
import ru.onlychill.mbondsfx.core.ledger.BondAccountingPolicy;
import ru.onlychill.mbondsfx.core.ledger.BondOperations;
import ru.onlychill.mbondsfx.core.ledger.Ledger;
import ru.onlychill.mbondsfx.core.ledger.LedgerChart;
import ru.onlychill.mbondsfx.core.ledger.LedgerState;
import ru.onlychill.mbondsfx.core.ledger.OperationResult;
import ru.onlychill.mbondsfx.core.ledger.Position;
import ru.onlychill.mbondsfx.core.ledger.Posting;
import ru.onlychill.mbondsfx.core.money.Money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceMemoryContractTests {
    private static final Currency RUB = Currency.getInstance("RUB");
    private static final Currency USD = Currency.getInstance("USD");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);
    private static final BondAccountingPolicy STANDARD = BondAccountingPolicy.standard();
    private static final BondOperations OPERATIONS = STANDARD.operations();

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
        LedgerApplicationService service = new LedgerApplicationService(repository, STANDARD);
        Ledger direct = Ledger.open(portfolio, STANDARD.chart(), false);

        OperationResult opening = OPERATIONS.opening(id("chain-open-rub"), DAY,
                portfolio, RUB, money("10000.000", RUB));
        direct = direct.apply(opening);
        LedgerSnapshot snapshot = service.apply(portfolio, false, opening);
        assertEquals(direct.state(), snapshot.state(), "RUB opening matches direct apply");

        OperationResult usdOpening = OPERATIONS.opening(id("chain-open-usd"), DAY,
                portfolio, USD, money("12.345", USD));
        direct = direct.apply(usdOpening);
        snapshot = service.apply(portfolio, false, usdOpening);
        assertEquals(direct.state(), snapshot.state(), "same-date second-currency opening matches direct apply");

        Position empty = Position.empty(new PositionId(portfolio, new SecurityId("SU-MEMORY-1")), RUB);
        OperationResult buy = OPERATIONS.buy(id("chain-buy"), DAY.plusDays(1), empty,
                10, money("95.005", RUB), money("2.005", RUB), money("100.005", RUB), money("0.015", RUB));
        direct = direct.apply(buy);
        snapshot = service.apply(portfolio, false, buy);
        assertEquals(direct.state(), snapshot.state(), "buy snapshot restores to direct ledger state");

        OperationResult coupon = OPERATIONS.coupon(id("chain-coupon"), DAY.plusDays(2),
                buy.positionAfter().orElseThrow(), money("25.075", RUB), Optional.of(money("0.005", RUB)));
        direct = direct.apply(coupon);
        snapshot = service.apply(portfolio, false, coupon);
        assertEquals(direct.state(), snapshot.state(), "coupon snapshot restores to direct ledger state");

        OperationResult amortization = OPERATIONS.amortization(id("chain-amort"), DAY.plusDays(3),
                coupon.positionAfter().orElseThrow(), money("500.025", RUB), money("500.005", RUB), Optional.empty());
        direct = direct.apply(amortization);
        snapshot = service.apply(portfolio, false, amortization);
        assertEquals(direct.state(), snapshot.state(), "amortization snapshot restores to direct ledger state");

        OperationResult sale = OPERATIONS.sell(id("chain-sell"), DAY.plusDays(4),
                amortization.positionAfter().orElseThrow(), 10, money("100.005", RUB),
                money("0.005", RUB), money("0.005", RUB), Optional.empty());
        direct = direct.apply(sale);
        snapshot = service.apply(portfolio, false, sale);
        Ledger restored = Ledger.restore(repository.load(portfolio).orElseThrow().state());
        assertTrue(snapshot.state().equals(direct.state()) && restored.state().equals(direct.state()),
                "buy/coupon/amortization/sell load-restore chain matches direct apply");
        assertEquals(6, snapshot.version(), "version advances once per operation");
        assertEquals(6, snapshot.lastSequence(), "per-portfolio sequence advances once per operation");
        assertEquals(RUB, snapshot.state().balances().get(account(portfolio, STANDARD.cash(), RUB)).currency(),
                "RUB account retains its currency identity");
        assertTrue(snapshot.state().balances().get(account(portfolio, STANDARD.cash(), USD))
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
    void customPolicyEndToEndUsesRepositoryCouponReceiptCorrectionAndRestore() {
        // DE-P-13/14/16: first CAS freezes a nine-code custom policy used by real operations and services.
        PortfolioId portfolio = portfolio("custom-policy-e2e");
        BondAccountingPolicy policy = customPolicy("PORTFOLIO");
        assertEquals(9, policy.chart().knownCodes().size());
        assertTrue(policy.chart().knownCodes().stream().noneMatch(code -> Set.of("CASH", "EQUITY", "SECURITIES",
                "ACCRUED_INTEREST", "COUPON_INCOME", "AMORTIZATION_GAIN", "REALIZED_PNL", "TAX_WITHHELD",
                "COMMISSION_EXPENSE").contains(code.value())), "E2E policy uses no standard account code");
        BondOperations operations = policy.operations();
        LedgerRepository ledgers = repository();
        InvestmentAccountRepository accounts = new InMemoryRepositoryFactory()
                .createInvestmentAccountRepository(RepositoryConfig.empty());
        InvestmentAccount accountMetadata = InvestmentAccount.brokerage(portfolio);
        accounts.register(accountMetadata);
        LedgerApplicationService ledgerService = new LedgerApplicationService(ledgers, policy);

        OperationResult deposit = operations.deposit(id("custom-deposit"), DAY,
                portfolio, RUB, money("500", RUB));
        ledgerService.apply(portfolio, false, deposit);

        Position emptyPosition = Position.empty(
                new PositionId(portfolio, new SecurityId("SU-CUSTOM-MEMORY")), RUB);
        OperationResult buy = operations.buy(id("custom-buy"), DAY.plusDays(1), emptyPosition,
                2, money("90", RUB), money("1", RUB), money("100", RUB), money("3", RUB));
        ledgerService.apply(portfolio, false, buy);
        Position boughtPosition = buy.positionAfter().orElseThrow();

        OperationId couponId = id("custom-coupon");
        CouponReceiptService couponService = new CouponReceiptService(accounts, ledgers, policy);
        CouponReceipt receipt = couponService.confirm(accountMetadata, boughtPosition, couponId,
                DAY.plusDays(2), money("10", RUB), money("2", RUB), money("8", RUB), false);
        assertEquals(money("10", RUB), receipt.grossTotal());
        assertEquals(money("2", RUB), receipt.taxWithheldTotal());
        assertEquals(money("8", RUB), receipt.netReceivedTotal());
        assertEquals(3, receipt.ledgerSnapshot().version(), "receipt is committed as the third CAS transition");

        OperationResult sale = operations.sell(id("custom-sell"), DAY.plusDays(3),
                receipt.ledgerSnapshot().state().positions().get(boughtPosition.id().security()), 1,
                money("95", RUB), money("1", RUB), money("0.50", RUB), Optional.empty());
        ledgerService.apply(portfolio, false, sale);

        LedgerSnapshot corrected = new TaxCorrectionService(ledgers, policy).correct(portfolio,
                id("custom-correction"), DAY.plusDays(4), couponId, money("1.25", RUB), false);
        Ledger restored = Ledger.restore(corrected.state());

        Account cash = account(portfolio, policy.cash(), RUB);
        Account tax = account(portfolio, policy.taxWithheld(), RUB);
        Account equity = account(portfolio, policy.equity(), RUB);
        Account securities = new Account(portfolio, policy.securities(),
                Optional.of(boughtPosition.id().security()), RUB);
        Account couponIncome = account(portfolio, policy.couponIncome(), RUB);
        Account realizedPnl = account(portfolio, policy.realizedPnl(), RUB);
        Account commissions = account(portfolio, policy.commissionExpense(), RUB);
        assertEquals(money("419.25", RUB), restored.balance(cash));
        assertEquals(money("1.25", RUB), restored.balance(tax));
        assertEquals(money("-500", RUB), restored.balance(equity));
        assertEquals(money("90", RUB), restored.balance(securities));
        assertEquals(Money.zero(RUB), restored.balance(new Account(portfolio, policy.accruedInterest(),
                Optional.of(boughtPosition.id().security()), RUB)));
        assertEquals(money("-9", RUB), restored.balance(couponIncome));
        assertEquals(money("-5", RUB), restored.balance(realizedPnl));
        assertEquals(money("3.50", RUB), restored.balance(commissions));
        assertEquals(Map.of(RUB, money("419.25", RUB)), restored.cashBalances(),
                "spendable view uses the custom cash code and excludes withholding");
        assertEquals(Map.of(RUB, money("1.25", RUB)), restored.taxWithheldBalances(),
                "adjustable view uses the custom tax code");
        assertEquals(money("1.25", RUB), restored.taxWithheldForOperation(couponId));
        assertEquals(policy.chart(), restored.chart(), "the first successful commit freezes its custom chart");
        assertEquals(1, restored.positions().get(boughtPosition.id().security()).quantity());
        assertEquals(money("90", RUB), restored.positions().get(boughtPosition.id().security()).carryingAmount());
        assertEquals(money("100", RUB), restored.positions().get(boughtPosition.id().security()).faceAmount());
        assertEquals(Money.zero(RUB), restored.positions().get(boughtPosition.id().security()).purchasedAccrued());
        assertEquals(corrected.state(), restored.state(), "custom-chart state is self-contained on restore");

        List<OperationRecord> journal = ledgers.operations(portfolio);
        assertEquals(List.of(id("custom-deposit"), id("custom-buy"), couponId,
                        id("custom-sell"), id("custom-correction")),
                journal.stream().map(OperationRecord::operation).toList());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), journal.stream().map(OperationRecord::sequence).toList());
        assertEquals(policy.cash(), journal.get(0).postings().getFirst().entries().getFirst().account().code());
        assertEquals(1, journal.get(0).postings().size());
        assertEntryAmount(journal.get(0), policy.cash(), "500");
        assertEntryAmount(journal.get(0), policy.equity(), "-500");
        assertEquals(2, journal.get(1).postings().size(), "buy keeps commission in its separate posting");
        assertPostingEntryAmount(journal.get(1).postings().get(0), policy.securities(), "180");
        assertPostingEntryAmount(journal.get(1).postings().get(0), policy.accruedInterest(), "2");
        assertPostingEntryAmount(journal.get(1).postings().get(0), policy.cash(), "-182");
        assertPostingEntryAmount(journal.get(1).postings().get(1), policy.commissionExpense(), "3");
        assertPostingEntryAmount(journal.get(1).postings().get(1), policy.cash(), "-3");
        assertEntryAmount(journal.get(1), policy.securities(), "180");
        assertEntryAmount(journal.get(1), policy.accruedInterest(), "2");
        assertEntryAmount(journal.get(1), policy.cash(), "-185");
        assertEntryAmount(journal.get(1), policy.commissionExpense(), "3");
        assertEquals(2, journal.get(2).postings().size(), "coupon withholding is separately journaled");
        assertPostingEntryAmount(journal.get(2).postings().get(0), policy.cash(), "10");
        assertPostingEntryAmount(journal.get(2).postings().get(0), policy.accruedInterest(), "-2");
        assertPostingEntryAmount(journal.get(2).postings().get(0), policy.couponIncome(), "-8");
        assertPostingEntryAmount(journal.get(2).postings().get(1), policy.taxWithheld(), "2");
        assertPostingEntryAmount(journal.get(2).postings().get(1), policy.cash(), "-2");
        assertEntryAmount(journal.get(2), policy.couponIncome(), "-8");
        assertEntryAmount(journal.get(2), policy.taxWithheld(), "2");
        assertEquals(2, journal.get(3).postings().size(), "sale commission remains separately journaled");
        assertPostingEntryAmount(journal.get(3).postings().get(0), policy.cash(), "96");
        assertPostingEntryAmount(journal.get(3).postings().get(0), policy.securities(), "-90");
        assertPostingEntryAmount(journal.get(3).postings().get(0), policy.couponIncome(), "-1");
        assertPostingEntryAmount(journal.get(3).postings().get(0), policy.realizedPnl(), "-5");
        assertPostingEntryAmount(journal.get(3).postings().get(1), policy.commissionExpense(), "0.50");
        assertPostingEntryAmount(journal.get(3).postings().get(1), policy.cash(), "-0.50");
        assertEntryAmount(journal.get(3), policy.realizedPnl(), "-5");
        assertEntryAmount(journal.get(3), policy.commissionExpense(), "0.50");
        assertEquals(1, journal.get(4).postings().size(), "tax correction is append-only single transfer posting");
        assertEquals(Optional.of(couponId), journal.get(4).postings().getFirst().adjustsOperation());
        assertEntryAmount(journal.get(4), policy.taxWithheld(), "-0.75");
        assertEntryAmount(journal.get(4), policy.cash(), "0.75");
        assertTrue(journal.stream().flatMap(record -> record.postings().stream())
                        .flatMap(posting -> posting.entries().stream())
                        .allMatch(entry -> policy.chart().isKnown(entry.account().code())),
                "every committed posting uses a code from the custom chart");
    }

    @Test
    void repositoryPersistsEmptyNoNegativeChartAndAllowsNegativeCash() {
        // DE-P-16 (repository): cardinality-valid chart/read view is persisted and restore keeps policy.
        PortfolioId portfolio = portfolio("empty-no-negative");
        BondAccountingPolicy policy = customPolicy("NO_NEGATIVE");
        LedgerChart policyChart = policy.chart();
        LedgerChart noNegativeChart = new LedgerChart(policyChart.knownCodes(), policyChart.spendableCodes(),
                policyChart.adjustableCode(), policyChart.adjustmentCounterpartCode(), Set.of(),
                policyChart.securityScopedCodes(), policyChart.positionBindings());
        LedgerRepository repository = repository();
        OperationResult withdrawal = policy.operations().withdrawal(id("negative-withdrawal"), DAY,
                portfolio, RUB, money("25", RUB));
        LedgerState before = Ledger.open(portfolio, noNegativeChart, false).state();
        Ledger after = Ledger.restore(before).apply(withdrawal);
        LedgerSnapshot committed = repository.commit(new LedgerCommit(0, before, withdrawal, after.state()));
        Ledger restored = Ledger.restore(committed.state());

        assertEquals(noNegativeChart, restored.chart(), "custom chart including empty noNegative survives storage");
        assertEquals(money("-25", RUB), restored.balance(account(portfolio, policy.cash(), RUB)));
        assertEquals(Map.of(RUB, money("-25", RUB)), restored.cashBalances());
        assertEquals(1, committed.version());
        assertEquals(List.of(id("negative-withdrawal")), repository.operations(portfolio).stream()
                .map(OperationRecord::operation).toList());
    }

    @Test
    void repositoryAllowsNegativeCashWhenPolicyFlagDisablesNoNegativeRole() {
        // DE-P-16 (repository): allowNegativeCash=true disables a populated noNegative role.
        PortfolioId portfolio = portfolio("allow-negative-cash");
        BondAccountingPolicy policy = customPolicy("ALLOW_NEGATIVE");
        LedgerRepository repository = repository();
        LedgerApplicationService service = new LedgerApplicationService(repository, policy);
        OperationResult withdrawal = policy.operations().withdrawal(id("allow-negative-withdrawal"), DAY,
                portfolio, RUB, money("25", RUB));

        assertThrows(IllegalStateException.class, () -> service.apply(portfolio, false, withdrawal),
                "configured noNegative role rejects a negative spendable balance by default");
        assertTrue(repository.load(portfolio).isEmpty() && repository.operations(portfolio).isEmpty(),
                "rejected negative-cash operation is not persisted");

        LedgerSnapshot snapshot = service.apply(portfolio, true, withdrawal);
        Ledger restored = Ledger.restore(snapshot.state());
        assertTrue(snapshot.state().allowNegativeCash());
        assertEquals(policy.chart(), restored.chart());
        assertEquals(money("-25", RUB), restored.balance(account(portfolio, policy.cash(), RUB)));
        assertEquals(Map.of(RUB, money("-25", RUB)), restored.cashBalances());
    }

    @Test
    void repositoryRestoresBothPositionBindingsAndSpendableViews() {
        // DE-P-16 (repository): two stored positions retain both chart-bound measures and currency views.
        PortfolioId portfolio = portfolio("two-position-views");
        BondAccountingPolicy policy = customPolicy("TWO_POSITION");
        LedgerRepository repository = repository();
        LedgerApplicationService service = new LedgerApplicationService(repository, policy);
        BondOperations operations = policy.operations();
        service.apply(portfolio, false, operations.deposit(id("two-rub-deposit"), DAY,
                portfolio, RUB, money("1000", RUB)));
        service.apply(portfolio, false, operations.deposit(id("two-usd-deposit"), DAY,
                portfolio, USD, money("100", USD)));

        Position rubEmpty = Position.empty(new PositionId(portfolio, new SecurityId("SU-TWO-RUB")), RUB);
        Position usdEmpty = Position.empty(new PositionId(portfolio, new SecurityId("SU-TWO-USD")), USD);
        OperationResult rubBuy = operations.buy(id("two-rub-buy"), DAY.plusDays(1), rubEmpty, 1,
                money("80", RUB), money("2", RUB), money("100", RUB), money("0", RUB));
        OperationResult usdBuy = operations.buy(id("two-usd-buy"), DAY.plusDays(1), usdEmpty, 1,
                money("40", USD), money("1", USD), money("50", USD), money("0", USD));
        service.apply(portfolio, false, rubBuy);
        LedgerSnapshot snapshot = service.apply(portfolio, false, usdBuy);
        Ledger restored = Ledger.restore(snapshot.state());
        Position rubPosition = restored.positions().get(rubEmpty.id().security());
        Position usdPosition = restored.positions().get(usdEmpty.id().security());

        assertEquals(policy.chart(), restored.chart());
        assertEquals(Set.of(policy.cash()), restored.chart().spendableCodes());
        assertEquals(Set.of(policy.cash()), restored.chart().noNegativeCodes());
        assertEquals(money("80", RUB), restored.balance(new Account(portfolio, policy.securities(),
                Optional.of(rubEmpty.id().security()), RUB)));
        assertEquals(rubPosition.carryingAmount(), restored.balance(new Account(portfolio, policy.securities(),
                Optional.of(rubEmpty.id().security()), RUB)));
        assertEquals(rubPosition.purchasedAccrued(), restored.balance(new Account(portfolio,
                policy.accruedInterest(), Optional.of(rubEmpty.id().security()), RUB)));
        assertEquals(1, usdPosition.quantity());
        assertEquals(usdPosition.carryingAmount(), restored.balance(new Account(portfolio, policy.securities(),
                Optional.of(usdEmpty.id().security()), USD)));
        assertEquals(usdPosition.purchasedAccrued(), restored.balance(new Account(portfolio,
                policy.accruedInterest(), Optional.of(usdEmpty.id().security()), USD)));
        assertEquals(Map.of(RUB, money("918", RUB), USD, money("59", USD)), restored.cashBalances());
        assertTrue(restored.taxWithheldBalances().isEmpty(), "tax view remains distinct from spendable cash");
        assertEquals(snapshot.state(), restored.state());
    }

    @Test
    void duplicateCommitsAreRejectedWithoutMutation() {
        // DE-P-13: successful commit followed by duplicate preserves snapshot+journal and duplicate precedence.
        PortfolioId portfolio = portfolio("duplicate-cas");
        LedgerRepository repository = repository();
        LedgerApplicationService service = new LedgerApplicationService(repository, STANDARD);
        OperationResult opening = OPERATIONS.opening(id("duplicate-open"), DAY,
                portfolio, RUB, money("100", RUB));
        LedgerState empty = Ledger.open(portfolio, STANDARD.chart(), false).state();
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
        // DE-P-13: version conflict leaves both persisted snapshot and journal unchanged.
        PortfolioId portfolio = portfolio("stale-cas");
        LedgerRepository repository = repository();
        LedgerApplicationService service = new LedgerApplicationService(repository, STANDARD);
        OperationResult opening = OPERATIONS.opening(id("stale-open"), DAY,
                portfolio, RUB, money("100", RUB));
        service.apply(portfolio, false, opening);
        LedgerSnapshot current = repository.load(portfolio).orElseThrow();
        OperationResult staleOperation = OPERATIONS.deposit(id("stale-candidate"), DAY.plusDays(1),
                portfolio, RUB, money("1", RUB));
        LedgerCommit stale = transition(current, staleOperation);
        OperationResult winner = OPERATIONS.deposit(id("cas-winner"), DAY.plusDays(1),
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
        // DE-P-13: exact before-state mismatch is a CAS conflict; malformed after-state is not persisted.
        PortfolioId portfolio = portfolio("forged-before");
        LedgerRepository repository = repository();
        LedgerApplicationService service = new LedgerApplicationService(repository, STANDARD);
        OperationResult opening = OPERATIONS.opening(id("forged-open"), DAY,
                portfolio, RUB, money("100", RUB));
        service.apply(portfolio, false, opening);
        LedgerSnapshot actual = repository.load(portfolio).orElseThrow();
        OperationResult deposit = OPERATIONS.deposit(id("forged-deposit"), DAY.plusDays(1),
                portfolio, RUB, money("1", RUB));

        LedgerState wrongPolicyBefore = Ledger.open(portfolio, STANDARD.chart(), true).apply(opening).state();
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
    void chartDriftIsRejectedByBothServicesAndRepositoryBeforeStateCas() {
        // DE-N-07: both application services reject a persisted chart different from their policy.
        // DE-P-13: subsequent repository commits compare the exact before-state, including its chart.
        PortfolioId portfolio = portfolio("chart-drift");
        BondAccountingPolicy storedPolicy = customPolicy("STORED");
        BondAccountingPolicy otherPolicy = customPolicy("OTHER");
        LedgerRepository repository = repository();
        LedgerApplicationService storedService = new LedgerApplicationService(repository, storedPolicy);
        storedService.apply(portfolio, false, storedPolicy.operations().deposit(id("drift-seed"), DAY,
                portfolio, RUB, money("100", RUB)));
        LedgerSnapshot stored = repository.load(portfolio).orElseThrow();
        List<OperationRecord> journalBeforeDrift = repository.operations(portfolio);

        LedgerApplicationService driftedService = new LedgerApplicationService(repository, otherPolicy);
        assertThrows(IllegalStateException.class,
                () -> driftedService.apply(portfolio, false, otherPolicy.operations().deposit(
                        id("drift-service-operation"), DAY.plusDays(1), portfolio, RUB, money("1", RUB))),
                "ledger application rejects chart drift before attempting a commit");
        assertThrows(IllegalStateException.class,
                () -> new TaxCorrectionService(repository, otherPolicy).correct(portfolio,
                        id("drift-correction"), DAY.plusDays(1), id("not-a-withholding"),
                        money("1", RUB), false),
                "tax correction rejects chart drift before querying the absent source operation");

        OperationResult foreignSeed = otherPolicy.operations().deposit(id("foreign-seed"), DAY,
                portfolio, RUB, money("100", RUB));
        LedgerState foreignBefore = Ledger.open(portfolio, otherPolicy.chart(), false)
                .apply(foreignSeed).state();
        OperationResult foreignNext = otherPolicy.operations().deposit(id("foreign-next"), DAY.plusDays(1),
                portfolio, RUB, money("1", RUB));
        LedgerState foreignAfter = Ledger.restore(foreignBefore).apply(foreignNext).state();
        LedgerCommit wrongChartCommit = new LedgerCommit(1, foreignBefore, foreignNext, foreignAfter);
        assertThrows(LedgerConcurrencyException.class, () -> repository.commit(wrongChartCommit),
                "a same-version before-state with a different chart loses the repository CAS");

        assertEquals(stored, repository.load(portfolio).orElseThrow(), "chart drift cannot mutate stored snapshot");
        assertEquals(journalBeforeDrift, repository.operations(portfolio), "chart drift cannot append journal data");
        assertEquals(storedPolicy.chart(), stored.state().chart());
    }

    @Test
    void ledgerCommitRejectsAChartChangeBetweenBeforeAndAfter() {
        // DE-N-08 / R11.2: chart identity is explicitly frozen across one transition.
        PortfolioId portfolio = portfolio("commit-chart-change");
        BondAccountingPolicy beforePolicy = customPolicy("BEFORE");
        BondAccountingPolicy afterPolicy = customPolicy("AFTER");
        LedgerState before = Ledger.open(portfolio, beforePolicy.chart(), false).state();
        OperationResult operation = beforePolicy.operations().deposit(id("chart-change-operation"), DAY,
                portfolio, RUB, money("10", RUB));
        LedgerState expectedAfter = Ledger.restore(before).apply(operation).state();
        LedgerState changedChartAfter = new LedgerState(expectedAfter.portfolio(), afterPolicy.chart(),
                expectedAfter.allowNegativeCash(), expectedAfter.balances(), expectedAfter.positions(),
                expectedAfter.postingsByDate(), expectedAfter.operationIds());

        assertThrows(IllegalArgumentException.class,
                () -> new LedgerCommit(0, before, operation, changedChartAfter),
                "commit rejects an after-state whose chart differs from before");
    }

    @Test
    void sameDateSequenceBackdateAndImmutableReads() {
        PortfolioId portfolio = portfolio("same-date");
        LedgerRepository repository = repository();
        LedgerApplicationService service = new LedgerApplicationService(repository, STANDARD);
        service.apply(portfolio, false,
                OPERATIONS.opening(id("same-open"), DAY, portfolio, RUB, money("10", RUB)));
        service.apply(portfolio, false,
                OPERATIONS.deposit(id("same-deposit-a"), DAY, portfolio, RUB, money("1", RUB)));
        LedgerSnapshot beforeLast = repository.load(portfolio).orElseThrow();
        service.apply(portfolio, false,
                OPERATIONS.deposit(id("same-deposit-b"), DAY, portfolio, RUB, money("2", RUB)));
        LedgerSnapshot snapshot = repository.load(portfolio).orElseThrow();
        List<OperationRecord> journal = repository.operations(portfolio);
        assertEquals(3, snapshot.version(), "same-date commits preserve monotonic version");
        assertEquals(3, snapshot.lastSequence(), "same-date commits preserve monotonic sequence");
        assertTrue(journal.get(1).operation().equals(id("same-deposit-a"))
                        && journal.get(2).operation().equals(id("same-deposit-b")),
                "same-date journal order follows committed sequence");

        assertThrows(IllegalStateException.class,
                () -> service.apply(portfolio, false, OPERATIONS.deposit(id("backdated"),
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
        LedgerApplicationService service = new LedgerApplicationService(repository, STANDARD);
        PortfolioId first = portfolio("isolated-first");
        PortfolioId second = portfolio("isolated-second");
        OperationId sharedId = id("same-operation-id");

        service.apply(first, false, OPERATIONS.opening(sharedId, DAY, first, RUB, money("10", RUB)));
        LedgerSnapshot firstSnapshot = repository.load(first).orElseThrow();
        service.apply(second, false, OPERATIONS.opening(sharedId, DAY, second, RUB, money("20", RUB)));
        LedgerSnapshot secondSnapshot = repository.load(second).orElseThrow();
        assertEquals(1, firstSnapshot.version(), "first portfolio version is independent");
        assertEquals(1, secondSnapshot.version(), "second portfolio version is independent");
        assertEquals(1, firstSnapshot.lastSequence(), "first portfolio sequence is independent");
        assertEquals(1, secondSnapshot.lastSequence(), "second portfolio sequence is independent");
        assertTrue(firstSnapshot.state().balances().get(account(first, STANDARD.cash(), RUB)).equals(money("10", RUB))
                        && secondSnapshot.state().balances().get(account(second, STANDARD.cash(), RUB))
                        .equals(money("20", RUB)),
                "same operation ID in different portfolios does not collide");
        assertEquals(1, repository.operations(first).size(), "first journal query is portfolio-scoped");
        assertEquals(1, repository.operations(second).size(), "second journal query is portfolio-scoped");
        assertTrue(factory.create(RepositoryConfig.empty()).load(first).isEmpty(),
                "separate repository instances do not share state");
    }

    @Test
    void concurrentCommitCasIsAtomic() throws Exception {
        // DE-P-13: real in-memory CAS allows exactly one same-version transition.
        PortfolioId portfolio = portfolio("parallel-cas");
        LedgerRepository repository = repository();
        LedgerApplicationService service = new LedgerApplicationService(repository, STANDARD);
        service.apply(portfolio, false,
                OPERATIONS.opening(id("parallel-open"), DAY, portfolio, RUB, money("100", RUB)));
        LedgerSnapshot before = repository.load(portfolio).orElseThrow();

        OperationResult firstOperation = OPERATIONS.deposit(id("parallel-one"), DAY.plusDays(1),
                portfolio, RUB, money("1", RUB));
        OperationResult secondOperation = OPERATIONS.deposit(id("parallel-two"), DAY.plusDays(1),
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
        BigDecimal winningCash = after.state().balances().get(account(portfolio, STANDARD.cash(), RUB)).amount();
        assertTrue(after.state().operationIds().size() == 2
                        && (winningCash.compareTo(new BigDecimal("101")) == 0
                        || winningCash.compareTo(new BigDecimal("102")) == 0),
                "losing writer contributes no partial operation or balance");
        assertTrue(journal.get(1).operation().equals(id("parallel-one"))
                        || journal.get(1).operation().equals(id("parallel-two")),
                "journal contains only the winning concurrent operation");
    }

    @Test
    void concurrentFirstCommitsWithDifferentChartsSelectExactlyOnePolicy() throws Exception {
        // DE-N-09: distinct valid version-zero commits race; the winner chooses the stored chart.
        PortfolioId portfolio = portfolio("concurrent-first-chart");
        BondAccountingPolicy firstPolicy = customPolicy("FIRST");
        BondAccountingPolicy secondPolicy = customPolicy("SECOND");
        OperationResult firstOperation = firstPolicy.operations().deposit(id("first-chart-operation"),
                DAY, portfolio, RUB, money("17", RUB));
        OperationResult secondOperation = secondPolicy.operations().deposit(id("second-chart-operation"),
                DAY, portfolio, RUB, money("29", RUB));
        LedgerCommit first = initialCommit(portfolio, firstPolicy, firstOperation);
        LedgerCommit second = initialCommit(portfolio, secondPolicy, secondOperation);
        LedgerRepository repository = repository();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicReference<LedgerCommit> winner = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstResult = executor.submit(() -> contendFirstWriter(repository, first, ready, start,
                    successes, conflicts, winner));
            Future<?> secondResult = executor.submit(() -> contendFirstWriter(repository, second, ready, start,
                    successes, conflicts, winner));
            assertTrue(ready.await(10, TimeUnit.SECONDS), "both different-chart first writers reach barrier");
            start.countDown();
            firstResult.get(10, TimeUnit.SECONDS);
            secondResult.get(10, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        LedgerCommit winningCommit = winner.get();
        LedgerSnapshot snapshot = repository.load(portfolio).orElseThrow();
        List<OperationRecord> journal = repository.operations(portfolio);
        assertEquals(1, successes.get(), "exactly one version-zero chart commits");
        assertEquals(1, conflicts.get(), "the competing first chart receives a concurrency conflict");
        assertEquals(1, snapshot.version());
        assertEquals(1, snapshot.lastSequence());
        assertEquals(winningCommit.after(), snapshot.state(), "snapshot equals the winner's complete state");
        assertEquals(winningCommit.after().chart(), snapshot.state().chart(),
                "first successful commit selects the portfolio chart without authentication");
        assertEquals(List.of(winningCommit.operation().operation()),
                journal.stream().map(OperationRecord::operation).toList());
        assertEquals(winningCommit.operation().postings(), journal.getFirst().postings());
        assertEquals(1, snapshot.state().operationIds().size(), "loser contributes no operation ID");
        assertEquals(snapshot.state(), Ledger.restore(snapshot.state()).state());
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

    private static void contendFirstWriter(LedgerRepository repository, LedgerCommit commit, CountDownLatch ready,
                                           CountDownLatch start, AtomicInteger successes, AtomicInteger conflicts,
                                           AtomicReference<LedgerCommit> winner) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("first-commit start barrier timed out");
            }
            repository.commit(commit);
            winner.set(commit);
            successes.incrementAndGet();
        } catch (LedgerConcurrencyException expected) {
            conflicts.incrementAndGet();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("first-commit thread interrupted", interrupted);
        }
    }

    private static LedgerCommit initialCommit(PortfolioId portfolio, BondAccountingPolicy policy,
                                              OperationResult operation) {
        LedgerState before = Ledger.open(portfolio, policy.chart(), false).state();
        LedgerState after = Ledger.restore(before).apply(operation).state();
        return new LedgerCommit(0, before, operation, after);
    }

    private static LedgerCommit transition(LedgerSnapshot before, OperationResult operation) {
        Ledger after = Ledger.restore(before.state()).apply(operation);
        return new LedgerCommit(before.version(), before.state(), operation, after.state());
    }

    private static LedgerRepository repository() {
        return new InMemoryRepositoryFactory().create(RepositoryConfig.empty());
    }

    private static Account account(PortfolioId portfolio, AccountCode code, Currency currency) {
        return new Account(portfolio, code, Optional.empty(), currency);
    }

    private static BondAccountingPolicy customPolicy(String prefix) {
        return new BondAccountingPolicy(
                AccountCode.of(prefix + "_CASH"),
                AccountCode.of(prefix + "_EQUITY"),
                AccountCode.of(prefix + "_SECURITIES"),
                AccountCode.of(prefix + "_ACCRUED"),
                AccountCode.of(prefix + "_COUPON"),
                AccountCode.of(prefix + "_AMORTIZATION"),
                AccountCode.of(prefix + "_PNL"),
                AccountCode.of(prefix + "_TAX"),
                AccountCode.of(prefix + "_COMMISSION"));
    }

    private static void assertEntryAmount(OperationRecord record, AccountCode code, String expected) {
        Money actual = record.postings().stream()
                .flatMap(posting -> posting.entries().stream())
                .filter(entry -> entry.account().code().equals(code))
                .map(Posting.Entry::amount)
                .reduce(Money.zero(RUB), Money::add);
        assertEquals(money(expected, RUB), actual, "journal has exact signed amount for custom code " + code.value());
    }

    private static void assertPostingEntryAmount(Posting posting, AccountCode code, String expected) {
        Money actual = posting.entries().stream()
                .filter(entry -> entry.account().code().equals(code))
                .map(Posting.Entry::amount)
                .reduce(Money.zero(RUB), Money::add);
        assertEquals(money(expected, RUB), actual, "posting has exact signed amount for custom code " + code.value());
    }

    private static PortfolioId portfolio(String value) { return new PortfolioId(value); }
    private static OperationId id(String value) { return new OperationId(value); }
    private static Money money(String value, Currency currency) {
        return new Money(new BigDecimal(value), currency);
    }
}
