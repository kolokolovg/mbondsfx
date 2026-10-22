package ru.onlychill.mbondsfx.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Provide;
import net.jqwik.api.Property;
import org.junit.jupiter.api.function.Executable;
import ru.onlychill.mbondsfx.core.id.OperationId;
import ru.onlychill.mbondsfx.core.id.PortfolioId;
import ru.onlychill.mbondsfx.core.id.PositionId;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.core.ledger.Account;
import ru.onlychill.mbondsfx.core.ledger.AccountCode;
import ru.onlychill.mbondsfx.core.ledger.BondAccountingPolicy;
import ru.onlychill.mbondsfx.core.ledger.BondOperations;
import ru.onlychill.mbondsfx.core.ledger.Commissions;
import ru.onlychill.mbondsfx.core.ledger.Ledger;
import ru.onlychill.mbondsfx.core.ledger.LedgerChart;
import ru.onlychill.mbondsfx.core.ledger.LedgerState;
import ru.onlychill.mbondsfx.core.ledger.OperationResult;
import ru.onlychill.mbondsfx.core.ledger.OperationResult.OperationKind;
import ru.onlychill.mbondsfx.core.ledger.Position;
import ru.onlychill.mbondsfx.core.ledger.Posting;
import ru.onlychill.mbondsfx.core.ledger.UnbalancedPostingException;
import ru.onlychill.mbondsfx.core.money.Money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LedgerProperties {
    private static final Currency RUB = Currency.getInstance("RUB");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency JPY = Currency.getInstance("JPY");
    /** Currencies used by the aggregate property; security index i is denominated in CURRENCIES.get(i). */
    private static final List<Currency> CURRENCIES = List.of(RUB, USD, JPY);
    private static final List<SecurityId> AGGREGATE_SECURITIES = List.of(
            new SecurityId("LP-AGG-RUB"), new SecurityId("LP-AGG-USD"), new SecurityId("LP-AGG-JPY"));
    private static final LocalDate DAY = LocalDate.of(2026, 1, 1);
    private static final BondAccountingPolicy POLICY = BondAccountingPolicy.standard();
    private static final LedgerChart CHART = POLICY.chart();
    private static final BondOperations OPS = POLICY.operations();
    /** Sale properties use price ticks of 1/10000 RUB; one RUB minor unit is 100 ticks. */
    private static final long TICKS_PER_MINOR_UNIT = 100;
    private static final long BUFFER_TICKS = 10_000;
    /** Cash buffer in minor units left after buys; opening balance for the aggregate property. */
    private static final long BUFFER_MINOR = 10_000;
    private static final long OPENING_MINOR = 10_000_000_000L;
    /** Every kind case starts from one holding and one taxed coupon, on a large opening cash balance. */
    private static final PortfolioId KIND_PORTFOLIO = new PortfolioId("ledger-kinds");
    private static final SecurityId KIND_SECURITY = new SecurityId("LP-KIND");
    private static final PositionId KIND_POSITION = new PositionId(KIND_PORTFOLIO, KIND_SECURITY);
    private static final LocalDate KIND_DATE = DAY.plusDays(3);
    private static final long KIND_OPENING_MINOR = 1_000_000_000_000L;
    /** Withholding chains use RUB and USD, one security per currency, and a large opening cash balance. */
    private static final List<Currency> CHAIN_CURRENCIES = List.of(RUB, USD);
    private static final List<SecurityId> CHAIN_SECURITIES = List.of(
            new SecurityId("LP-CHAIN-RUB"), new SecurityId("LP-CHAIN-USD"));
    private static final long CHAIN_OPENING_MINOR = 1_000_000_000_000L;

    @Property(tries = 250)
    void multiCurrencyPostingsBalanceAndBuyChargesEachCommissionComponentOnce(
            @ForAll("caseNumbers") int caseNumber,
            @ForAll("quantities") int quantity,
            @ForAll("minorUnits") long cleanMinor,
            @ForAll("minorUnits") long accruedMinor,
            @ForAll("positiveMinorUnits") long faceMinor,
            @ForAll("minorUnits") long brokerMinor,
            @ForAll("minorUnits") long otherMinor) {
        PortfolioId portfolio = portfolio(caseNumber);
        Ledger ledger = Ledger.open(portfolio, CHART, false);
        List<OperationResult> operations = new ArrayList<>();

        for (Currency currency : CURRENCIES) {
            Money clean = money(currency, cleanMinor);
            Money accrued = money(currency, accruedMinor);
            Money broker = money(currency, brokerMinor);
            Money other = money(currency, otherMinor);
            Money fees = broker.add(other);
            Money expectedSpend = clean.multiply(quantity).add(accrued.multiply(quantity)).add(fees);
            Money opening = expectedSpend.add(money(currency, BUFFER_MINOR));
            OperationResult funding = OPS.opening(operationId(caseNumber, "open-" + code(currency)),
                    DAY, portfolio, currency, opening);
            operations.add(funding);
            ledger = ledger.apply(funding);
        }

        for (Currency currency : CURRENCIES) {
            Money clean = money(currency, cleanMinor);
            Money accrued = money(currency, accruedMinor);
            Money broker = money(currency, brokerMinor);
            Money other = money(currency, otherMinor);
            Money fees = broker.add(other);
            Money opening = clean.multiply(quantity).add(accrued.multiply(quantity)).add(fees)
                    .add(money(currency, BUFFER_MINOR));
            SecurityId security = new SecurityId("LP-" + caseNumber + "-" + code(currency));
            Position empty = Position.empty(new PositionId(portfolio, security), currency);
            OperationResult purchase = OPS.buy(operationId(caseNumber, "buy-" + code(currency)),
                    DAY.plusDays(1), empty, quantity, clean, accrued, money(currency, faceMinor),
                    new Commissions(broker, other));
            operations.add(purchase);
            ledger = ledger.apply(purchase);

            Money expectedCash = opening.subtract(clean.multiply(quantity))
                    .subtract(accrued.multiply(quantity)).subtract(broker).subtract(other);
            assertEquals(expectedCash, ledger.cashBalances().get(currency), "cash after " + code(currency) + " buy");
            assertEquals(fees, ledger.balance(account(portfolio, POLICY.commissionExpense(), null, currency)),
                    "total commissions are expensed once in " + code(currency));
        }

        for (Currency currency : CURRENCIES) {
            assertEquals(money(currency, BUFFER_MINOR), ledger.cashBalances().get(currency));
        }
        operations.forEach(LedgerProperties::assertEveryPostingBalancesByCurrency);
        assertBalancesSumToZero(ledger);
    }

    @Property(tries = 250)
    void partialSaleCapsRoundedReleaseAndFinalSaleRemovesExactRemainder(
            @ForAll("caseNumbers") int caseNumber,
            @ForAll("saleCases") SaleCase saleCase) {
        PortfolioId portfolio = portfolio(caseNumber);
        SecurityId security = new SecurityId("LP-SALE-" + caseNumber);
        PositionId positionId = new PositionId(portfolio, security);
        long quantity = saleCase.quantity();
        long carryingTicks = saleCase.unitTicks() * quantity;
        long accruedTicks = saleCase.accruedTicks() * quantity;
        long faceTicks = saleCase.faceTicks() * quantity;
        Ledger ledger = Ledger.open(portfolio, CHART, false).apply(OPS.opening(
                operationId(caseNumber, "sale-fund"), DAY, portfolio,
                RUB, rubTicks(carryingTicks + accruedTicks + BUFFER_TICKS)));

        OperationResult purchase = OPS.buy(operationId(caseNumber, "sale-buy"), DAY.plusDays(1),
                Position.empty(positionId, RUB), quantity, rubTicks(saleCase.unitTicks()),
                rubTicks(saleCase.accruedTicks()), rubTicks(saleCase.faceTicks()), money(RUB, 0));
        ledger = ledger.apply(purchase);
        Position original = purchase.positionAfter().orElseThrow();
        assertEquals(rubTicks(carryingTicks), original.carryingAmount(), "purchase carrying amount");
        assertEquals(rubTicks(accruedTicks), original.purchasedAccrued(), "purchase accrued amount");
        assertEquals(rubTicks(faceTicks), original.faceAmount(), "purchase face amount");

        long partialQuantity = saleCase.partialQuantity();
        OperationResult partial = OPS.sell(operationId(caseNumber, "sale-partial"), DAY.plusDays(2),
                original, partialQuantity, money(RUB, 0), money(RUB, 0), money(RUB, 0), Optional.empty());
        Position residual = partial.positionAfter().orElseThrow();
        long carryingRemoved = expectedRemovalTicks(carryingTicks, partialQuantity, quantity);
        long accruedRemoved = expectedRemovalTicks(accruedTicks, partialQuantity, quantity);
        long faceRemoved = expectedRemovalTicks(faceTicks, partialQuantity, quantity);

        assertEquals(rubTicks(carryingTicks - carryingRemoved), residual.carryingAmount());
        assertEquals(rubTicks(accruedTicks - accruedRemoved), residual.purchasedAccrued());
        assertEquals(rubTicks(faceTicks - faceRemoved), residual.faceAmount());
        assertTrue(!residual.carryingAmount().isNegative());
        assertTrue(!residual.purchasedAccrued().isNegative());
        assertTrue(!residual.faceAmount().isNegative());
        assertEquals(rubTicks(-carryingRemoved), entryAmount(partial.postings().getFirst(),
                account(portfolio, POLICY.securities(), security, RUB)));
        assertEquals(rubTicks(-accruedRemoved), entryAmount(partial.postings().getFirst(),
                account(portfolio, POLICY.accruedInterest(), security, RUB)));
        ledger = ledger.apply(partial);

        OperationResult finalSale = OPS.sell(operationId(caseNumber, "sale-final"), DAY.plusDays(3),
                residual, residual.quantity(), money(RUB, 0), money(RUB, 0), money(RUB, 0), Optional.empty());
        assertEquals(rubTicks(-(carryingTicks - carryingRemoved)), entryAmount(finalSale.postings().getFirst(),
                account(portfolio, POLICY.securities(), security, RUB)));
        assertEquals(rubTicks(-(accruedTicks - accruedRemoved)), entryAmount(finalSale.postings().getFirst(),
                account(portfolio, POLICY.accruedInterest(), security, RUB)));
        assertEquals(Position.empty(positionId, RUB), finalSale.positionAfter().orElseThrow());
        ledger = ledger.apply(finalSale);

        assertEquals(money(RUB, 0), ledger.balance(account(portfolio, POLICY.securities(), security, RUB)));
        assertEquals(money(RUB, 0), ledger.balance(account(portfolio, POLICY.accruedInterest(), security, RUB)));
        assertBalancesSumToZero(ledger);
    }

    @Property(tries = 250)
    void withholdingIsNotSpendableAndCorrectionOnlyMovesCashAndWithheldBalance(
            @ForAll("caseNumbers") int caseNumber,
            @ForAll("taxCases") TaxCase taxCase) {
        PortfolioId portfolio = portfolio(caseNumber);
        SecurityId security = new SecurityId("LP-TAX-" + caseNumber);
        PositionId positionId = new PositionId(portfolio, security);
        Ledger ledger = Ledger.open(portfolio, CHART, false);
        OperationResult purchase = OPS.buy(operationId(caseNumber, "tax-buy"), DAY,
                Position.empty(positionId, RUB), 1, money(RUB, 0), money(RUB, 0),
                new Money(new BigDecimal("100"), RUB), money(RUB, 0));
        ledger = ledger.apply(purchase);
        OperationId couponId = operationId(caseNumber, "tax-coupon");
        OperationResult coupon = OPS.coupon(couponId, DAY.plusDays(1),
                purchase.positionAfter().orElseThrow(), money(RUB, taxCase.grossMinor()),
                Optional.of(money(RUB, taxCase.grossMinor())));
        ledger = ledger.apply(coupon);

        assertTrue(ledger.cashBalances().isEmpty(), "fully withheld proceeds are not spendable cash");
        assertEquals(money(RUB, taxCase.grossMinor()), ledger.taxWithheldBalances().get(RUB));
        Ledger beforeCorrection = ledger;
        LedgerState originalState = ledger.state();
        assertThrows(IllegalStateException.class, () -> beforeCorrection.apply(OPS.withdrawal(
                operationId(caseNumber, "tax-cannot-spend"), DAY.plusDays(2), portfolio, RUB, money(RUB, 1))));

        long refundMinor = taxCase.refundMinor();
        if (refundMinor > taxCase.grossMinor()) {
            // Over-refund would drive cumulative actual withholding below zero: the correction is rejected.
            assertThrows(IllegalStateException.class, () -> beforeCorrection.apply(OPS.taxCorrection(
                    operationId(caseNumber, "tax-over-refund"), DAY.plusDays(2), portfolio, couponId,
                    money(RUB, refundMinor).negate())));
            assertEquals(originalState, beforeCorrection.state(), "rejected correction leaves state unchanged");
            return;
        }
        if (refundMinor == 0) {
            // A zero adjustment is not a correction at all and is rejected before it reaches the ledger.
            assertThrows(IllegalArgumentException.class, () -> OPS.taxCorrection(
                    operationId(caseNumber, "tax-zero"), DAY.plusDays(2), portfolio, couponId, money(RUB, 0)));
            assertEquals(money(RUB, taxCase.grossMinor()), beforeCorrection.taxWithheldForOperation(couponId),
                    "withholding without a correction is unchanged");
            return;
        }

        OperationResult correction = OPS.taxCorrection(operationId(caseNumber, "tax-correction"),
                DAY.plusDays(2), portfolio, couponId, money(RUB, refundMinor).negate());
        Ledger corrected = beforeCorrection.apply(correction);
        Posting correctionPosting = correction.postings().getFirst();
        assertEquals(Optional.of(couponId), correctionPosting.adjustsOperation());
        assertEquals(money(RUB, refundMinor), entryAmount(correctionPosting,
                account(portfolio, POLICY.cash(), null, RUB)));
        assertEquals(money(RUB, -refundMinor), entryAmount(correctionPosting,
                account(portfolio, POLICY.taxWithheld(), null, RUB)));

        assertEquals(money(RUB, refundMinor), corrected.cashBalances().getOrDefault(RUB, money(RUB, 0)));
        assertEquals(money(RUB, taxCase.grossMinor() - refundMinor),
                corrected.taxWithheldBalances().getOrDefault(RUB, money(RUB, 0)));
        assertEquals(money(RUB, taxCase.grossMinor() - refundMinor), corrected.taxWithheldForOperation(couponId));
        assertEquals(money(RUB, -taxCase.grossMinor()),
                corrected.balance(account(portfolio, POLICY.couponIncome(), null, RUB)));
        assertEquals(beforeCorrection.positions(), corrected.positions(), "correction leaves positions unchanged");
        assertEquals(originalState.postingsByDate().get(DAY.plusDays(1)),
                corrected.postingsByDate().get(DAY.plusDays(1)), "original coupon history is append-only");
        assertEquals(originalState, beforeCorrection.state(), "applying a correction does not mutate its source ledger");
        assertBalancesSumToZero(corrected);
    }

    @Property(tries = 250)
    void validDepositSequencesRestoreAndPreserveEveryPriorLedgerState(
            @ForAll("caseNumbers") int caseNumber,
            @ForAll("depositSequences") List<Long> deposits) {
        PortfolioId portfolio = portfolio(caseNumber);
        Ledger ledger = Ledger.open(portfolio, CHART, false).apply(OPS.opening(
                operationId(caseNumber, "sequence-open"), DAY, portfolio, RUB, money(RUB, 100_000)));

        for (int index = 0; index < deposits.size(); index++) {
            Ledger previous = ledger;
            LedgerState previousState = previous.state();
            ledger = previous.apply(OPS.deposit(operationId(caseNumber, "deposit-" + index),
                    DAY.plusDays(index + 1L), portfolio, RUB, money(RUB, deposits.get(index))));
            assertEquals(previousState, previous.state(), "applying an operation leaves the old ledger unchanged");
        }

        LedgerState saved = ledger.state();
        Ledger restored = Ledger.restore(saved);
        assertEquals(saved, restored.state(), "state and complete posting history round-trip");
        OperationResult continuation = OPS.deposit(operationId(caseNumber, "continuation"),
                DAY.plusDays(deposits.size() + 1L), portfolio, RUB, money(RUB, 7));
        assertEquals(ledger.apply(continuation).state(), restored.apply(continuation).state(),
                "restored state accepts the same next valid operation");
    }

    /**
     * Random mixed sequences of deposits, withdrawals, buys, sales, amortizations, coupons and tax corrections in
     * RUB, USD and JPY. Domain rejections are expected for some steps (insufficient cash, empty position,
     * over-refund); a rejected step never changes the ledger. Every accepted operation keeps each posting
     * balanced, and the whole ledger keeps every currency summing to zero.
     */
    @Property(tries = 250)
    void randomOperationSequencesKeepEveryPostingAndWholeLedgerBalanced(
            @ForAll("caseNumbers") int caseNumber,
            @ForAll("ledgerSteps") List<Step> steps) {
        Ledger ledger = play(caseNumber, steps).ledger();
        assertBalancesSumToZero(ledger);
        assertEquals(CHART, Ledger.restore(ledger.state()).chart(), "chart survives restore after random history");
        assertEquals(ledger.state(), Ledger.restore(ledger.state()).state(), "random history round-trips");
    }

    // 3.1: every OperationKind has a generator; postings balance, the ledger keeps its invariants, and invalid
    // inputs are refused without changing the ledger.

    @Property(tries = 250)
    void everyOperationKindPostsBalancedEntriesAndKeepsTheLedgerInvariants(
            @ForAll("kindCases") KindCase kindCase) {
        Built built = acceptedOperation(kindCase);
        OperationResult operation = built.operation();
        assertEquals(kindCase.kind(), operation.kind(), "the generator builds the case's own kind");
        assertEveryPostingBalancesByCurrency(operation);

        LedgerState beforeState = built.before().state();
        Ledger after = built.before().apply(operation);
        assertEquals(beforeState, built.before().state(), "applying an operation never mutates its source ledger");
        assertLedgerInvariants(after);
        assertHistoryIsAppendOnly(beforeState, after.state());
        assertTrue(after.operationIds().contains(operation.operation()), "the operation identity is recorded");
        assertEquals(built.cashDelta(), cashOf(after).subtract(cashOf(built.before())),
                "cash moves by the economic amount of the operation");
    }

    @Property(tries = 250)
    void everyOperationKindRejectsInvalidInputsWithoutChangingTheLedger(@ForAll("kindCases") KindCase kindCase) {
        Ledger before = setupLedger(kindCase.setup());
        LedgerState beforeState = before.state();
        for (Attempt attempt : rejections(kindCase, before)) {
            assertThrows(attempt.expected(), attempt.action(), attempt.label());
        }
        assertEquals(beforeState, before.state(), "rejected operations leave the source ledger unchanged");
        assertLedgerInvariants(before);
    }

    @Example
    void everyOperationKindHasAFixedAcceptedExample() {
        Setup setup = new Setup(new Holding(4, 10_000, 500, 100_000, 100, 50), new CouponTerms(20_000, 3_000));
        List<KindCase> examples = List.of(
                new OpeningCase(setup, 1_000),
                new DepositCase(setup, 1_000),
                new WithdrawalCase(setup, 1_000),
                new PurchaseCase(setup, new Holding(2, 10_000, 0, 100_000, 0, 0)),
                new CouponCase(setup, new CouponTerms(5_000, 0)),
                new AmortizationCase(setup, 100, 50_000, 1_000),
                new SellCase(setup, 50, 10_000, 500, 100, 0),
                new TaxCorrectionCase(setup, -3_000));
        Set<OperationKind> kinds = EnumSet.noneOf(OperationKind.class);
        for (KindCase example : examples) {
            Built built = acceptedOperation(example);
            kinds.add(built.operation().kind());
            Ledger after = built.before().apply(built.operation());
            assertLedgerInvariants(after);
            assertEquals(built.cashDelta(), cashOf(after).subtract(cashOf(built.before())));
        }
        assertEquals(EnumSet.allOf(OperationKind.class), kinds, "the fixed examples cover every OperationKind");
    }

    // 3.2: long chains of taxed incomes and corrections against an independent withholding model.

    /**
     * Chains of taxed coupons, corrections, exact refunds to zero, over-refunds and corrections of corrections in
     * RUB and USD. The model predicts which steps the ledger accepts; rejected steps leave the ledger unchanged.
     * Accepted corrections move only cash and withheld tax, withholding per original never goes negative, the
     * history only grows by appending, and withheld balances equal the model's per-currency sums.
     */
    @Property(tries = 250)
    void longTaxCorrectionChainsStayNonNegativeAppendOnlyAndConsistent(
            @ForAll("caseNumbers") int caseNumber,
            @ForAll("chainSteps") List<ChainStep> steps) {
        ChainRun run = new ChainRun(caseNumber);
        for (int index = 0; index < steps.size(); index++) {
            run.apply(index, steps.get(index));
        }
        run.assertFinalState();
    }

    // 3.3: replaying the accepted journal, or restoring at any cut and replaying the tail, reproduces the state.

    @Property(tries = 250)
    void replayOfTheAcceptedJournalReproducesTheSameLedgerStateAndBalances(
            @ForAll("caseNumbers") int caseNumber,
            @ForAll("ledgerSteps") List<Step> steps) {
        Recording live = play(caseNumber, steps);
        PortfolioId portfolio = portfolio(caseNumber);
        Ledger first = replayOn(Ledger.open(portfolio, CHART, false), live.operations());
        Ledger second = replayOn(Ledger.open(portfolio, CHART, false), live.operations());
        assertEquals(live.ledger().state(), first.state(), "replaying the accepted journal reproduces the live state");
        assertEquals(first.state(), second.state(), "two replays of one journal are identical");
        assertEquals(live.ledger().balances(), first.balances(), "balances are reproduced");
        assertEquals(live.ledger().cashBalances(), first.cashBalances(), "cash views are reproduced");
        assertEquals(live.ledger().taxWithheldBalances(), first.taxWithheldBalances(),
                "withheld views are reproduced");
        assertEquals(live.ledger().positions(), first.positions(), "positions are reproduced");
    }

    @Property(tries = 250)
    void restoringAtAnyCutAndReplayingTheTailReproducesTheFinalState(
            @ForAll("caseNumbers") int caseNumber,
            @ForAll("ledgerSteps") List<Step> steps,
            @ForAll("cutSeeds") int cutSeed) {
        Recording live = play(caseNumber, steps);
        PortfolioId portfolio = portfolio(caseNumber);
        List<OperationResult> journal = live.operations();
        int[] cuts = {0, journal.size(), Math.floorMod(cutSeed, journal.size() + 1)};
        for (int cut : cuts) {
            Ledger prefix = replayOn(Ledger.open(portfolio, CHART, false), journal.subList(0, cut));
            Ledger restored = Ledger.restore(prefix.state());
            assertEquals(prefix.state(), restored.state(), "restore returns the state saved at cut " + cut);
            Ledger completed = replayOn(restored, journal.subList(cut, journal.size()));
            assertEquals(live.ledger().state(), completed.state(),
                    "restore at cut " + cut + " plus the tail reproduces the final state");
        }
    }

    @Provide
    Arbitrary<Integer> caseNumbers() {
        return Arbitraries.integers().between(1, 1_000_000);
    }

    @Provide
    Arbitrary<Integer> quantities() {
        return Arbitraries.integers().between(1, 20);
    }

    /** Minor units from zero up to 10^9 (10 million RUB/USD, 10^9 JPY): well beyond 10000. */
    @Provide
    Arbitrary<Long> minorUnits() {
        return Arbitraries.longs().between(0, 1_000_000_000L);
    }

    @Provide
    Arbitrary<Long> positiveMinorUnits() {
        return Arbitraries.longs().between(1, 1_000_000_000L);
    }

    @Provide
    Arbitrary<SaleCase> saleCases() {
        return Arbitraries.integers().between(2, 50).flatMap(quantity ->
                Arbitraries.integers().between(1, quantity - 1).flatMap(partialQuantity ->
                        Combinators.combine(positiveTicks(), nonNegativeTicks(), positiveTicks())
                                .as((unit, accrued, face) ->
                                        new SaleCase(quantity, partialQuantity, unit, accrued, face))));
    }

    @Provide
    Arbitrary<TaxCase> taxCases() {
        return Arbitraries.longs().between(1, 1_000_000_000L).flatMap(grossMinor ->
                Arbitraries.oneOf(
                                Arbitraries.just(0L),
                                Arbitraries.just(grossMinor),
                                Arbitraries.just(grossMinor + 1),
                                Arbitraries.longs().between(0, grossMinor))
                        .map(refundMinor -> new TaxCase(grossMinor, refundMinor)));
    }

    @Provide
    Arbitrary<List<Long>> depositSequences() {
        return Arbitraries.longs().between(1, 1_000_000_000L).list().ofMinSize(1).ofMaxSize(8);
    }

    @Provide
    Arbitrary<List<Step>> ledgerSteps() {
        return ledgerStep().list().ofMinSize(1).ofMaxSize(25);
    }

    @Provide
    Arbitrary<Integer> cutSeeds() {
        return Arbitraries.integers().between(0, 1_000_000);
    }

    @Provide
    Arbitrary<KindCase> kindCases() {
        return Arbitraries.oneOf(
                setups().flatMap(setup -> positiveMinorUnits().<KindCase>map(amount -> new OpeningCase(setup, amount))),
                setups().flatMap(setup -> positiveMinorUnits().<KindCase>map(amount -> new DepositCase(setup, amount))),
                setups().flatMap(setup -> positiveMinorUnits().<KindCase>map(amount -> new WithdrawalCase(setup, amount))),
                setups().flatMap(setup -> holdings().<KindCase>map(second -> new PurchaseCase(setup, second))),
                setups().flatMap(setup -> couponsWithAnyTax().<KindCase>map(coupon -> new CouponCase(setup, coupon))),
                setups().flatMap(setup -> Arbitraries.integers().between(1, 100).flatMap(percent ->
                        minorUnits().flatMap(payment -> Arbitraries.longs().between(0, payment)
                                .<KindCase>map(tax -> new AmortizationCase(setup, percent, payment, tax))))),
                setups().flatMap(setup -> Combinators.combine(Arbitraries.integers().between(1, 100),
                                minorUnits(), minorUnits(), feeMinorUnits(), minorUnits())
                        .<KindCase>as((percent, price, accrued, commission, tax) ->
                                new SellCase(setup, percent, price, accrued, commission, tax))),
                setups().flatMap(setup -> adjustments().<KindCase>map(adjustment ->
                        new TaxCorrectionCase(setup, adjustment))));
    }

    @Provide
    Arbitrary<List<ChainStep>> chainSteps() {
        Arbitrary<ChainStep> taxed = Arbitraries.integers().between(0, CHAIN_CURRENCIES.size() - 1)
                .flatMap(currencyIndex -> positiveMinorUnits().flatMap(gross -> Arbitraries.longs().between(1, gross)
                        .<ChainStep>map(tax -> new ChainStep.Taxed(currencyIndex, gross, tax))));
        Arbitrary<ChainStep> corrections = Combinators.combine(targets(), adjustments())
                .<ChainStep>as(ChainStep.Correct::new);
        Arbitrary<ChainStep> exactRefunds = targets().<ChainStep>map(ChainStep.ExactRefund::new);
        Arbitrary<ChainStep> overRefunds = targets().<ChainStep>map(ChainStep.OverRefund::new);
        Arbitrary<ChainStep> correctionOfCorrections = Combinators.combine(targets(), adjustments())
                .<ChainStep>as(ChainStep.CorrectCorrection::new);
        return Arbitraries.oneOf(taxed, taxed, corrections, corrections, exactRefunds, overRefunds,
                correctionOfCorrections).list().ofMinSize(1).ofMaxSize(40);
    }

    private Arbitrary<Step> ledgerStep() {
        Arbitrary<Step> deposits = Combinators.combine(securityIndexes(), positiveMinorUnits())
                .as((currency, minor) -> new Step.Deposit(currency, minor));
        Arbitrary<Step> withdrawals = Combinators.combine(securityIndexes(), positiveMinorUnits())
                .as((currency, minor) -> new Step.Withdrawal(currency, minor));
        Arbitrary<Step> buys = Combinators.combine(securityIndexes(), quantities(), positiveMinorUnits(),
                        minorUnits(), positiveMinorUnits(), minorUnits(), minorUnits())
                .as((security, quantity, clean, accrued, face, broker, other) ->
                        new Step.Buy(security, quantity, clean, accrued, face, broker, other));
        Arbitrary<Step> sales = Combinators.combine(securityIndexes(), quantities(), positiveMinorUnits(),
                        minorUnits(), minorUnits(), minorUnits())
                .as((security, quantity, clean, accrued, commission, tax) ->
                        new Step.Sell(security, quantity, clean, accrued, commission, tax));
        Arbitrary<Step> coupons = securityIndexes().flatMap(security ->
                positiveMinorUnits().flatMap(gross ->
                        Arbitraries.longs().between(0, gross)
                                .<Step>map(tax -> new Step.Coupon(security, gross, tax))));
        Arbitrary<Step> amortizations = securityIndexes().flatMap(security ->
                Arbitraries.integers().between(1, 100).flatMap(percent ->
                        minorUnits().flatMap(payment -> Arbitraries.longs().between(0, payment)
                                .<Step>map(tax -> new Step.Amortization(security, percent, payment, tax)))));
        Arbitrary<Step> corrections = Combinators.combine(Arbitraries.integers().between(0, 100),
                        Arbitraries.longs().between(-10_000_000L, 10_000_000L).filter(adjustment -> adjustment != 0))
                .as((selector, adjustment) -> new Step.Correction(selector, adjustment));
        return Arbitraries.oneOf(deposits, withdrawals, buys, sales, coupons, amortizations, corrections);
    }

    private static Arbitrary<Integer> securityIndexes() {
        return Arbitraries.integers().between(0, AGGREGATE_SECURITIES.size() - 1);
    }

    /** Price ticks: sub-minor values (below one minor unit) plus ordinary and large amounts. */
    private static Arbitrary<Long> positiveTicks() {
        return Arbitraries.oneOf(
                Arbitraries.longs().between(1, 9),
                Arbitraries.longs().between(1, 1_000_000),
                Arbitraries.longs().between(1_000_000, 1_000_000_000L));
    }

    private static Arbitrary<Long> nonNegativeTicks() {
        return Arbitraries.oneOf(
                Arbitraries.just(0L),
                Arbitraries.longs().between(1, 9),
                Arbitraries.longs().between(1, 1_000_000),
                Arbitraries.longs().between(1_000_000, 1_000_000_000L));
    }

    private static Arbitrary<Long> feeMinorUnits() {
        return Arbitraries.longs().between(0, 1_000_000L);
    }

    private static Arbitrary<Long> adjustments() {
        return Arbitraries.longs().between(-1_000_000_000L, 1_000_000_000L).filter(adjustment -> adjustment != 0);
    }

    private static Arbitrary<Integer> targets() {
        return Arbitraries.integers().between(0, 1_000_000);
    }

    private Arbitrary<Setup> setups() {
        return Combinators.combine(holdings(), couponsWithTax()).as(Setup::new);
    }

    private Arbitrary<Holding> holdings() {
        return Combinators.combine(quantities(), positiveMinorUnits(), minorUnits(),
                        Arbitraries.longs().between(100, 1_000_000_000L), feeMinorUnits(), feeMinorUnits())
                .as(Holding::new);
    }

    /** The setup coupon always withholds something, so it is a genuine withholding original. */
    private Arbitrary<CouponTerms> couponsWithTax() {
        return positiveMinorUnits().flatMap(gross -> Arbitraries.longs().between(1, gross)
                .map(tax -> new CouponTerms(gross, tax)));
    }

    private Arbitrary<CouponTerms> couponsWithAnyTax() {
        return minorUnits().flatMap(gross -> Arbitraries.longs().between(0, gross)
                .map(tax -> new CouponTerms(gross, tax)));
    }

    private static OperationId kindId(String label) {
        return new OperationId("ledger-kinds-" + label);
    }

    /** Opening, one purchase and one taxed coupon: the common history every kind case builds on. */
    private static Ledger setupLedger(Setup setup) {
        Ledger ledger = Ledger.open(KIND_PORTFOLIO, CHART, false);
        ledger = ledger.apply(OPS.opening(kindId("setup-open"), DAY, KIND_PORTFOLIO, RUB,
                money(RUB, KIND_OPENING_MINOR)));
        Holding holding = setup.holding();
        ledger = ledger.apply(OPS.buy(kindId("setup-buy"), DAY.plusDays(1), Position.empty(KIND_POSITION, RUB),
                holding.quantity(), money(RUB, holding.cleanMinor()), money(RUB, holding.accruedMinor()),
                money(RUB, holding.faceUnitMinor()),
                new Commissions(money(RUB, holding.brokerMinor()), money(RUB, holding.otherMinor()))));
        Position held = ledger.position(KIND_SECURITY).orElseThrow();
        CouponTerms coupon = setup.coupon();
        return ledger.apply(OPS.coupon(kindId("setup-coupon"), DAY.plusDays(2), held,
                money(RUB, coupon.grossMinor()), Optional.of(money(RUB, coupon.taxMinor()))));
    }

    private static Built acceptedOperation(KindCase kindCase) {
        Ledger before = setupLedger(kindCase.setup());
        Position current = before.position(KIND_SECURITY).orElseThrow();
        OperationId operation = kindId("operation");
        return switch (kindCase) {
            case OpeningCase c -> new Built(before,
                    OPS.opening(operation, KIND_DATE, KIND_PORTFOLIO, RUB, money(RUB, c.amountMinor())),
                    money(RUB, c.amountMinor()));
            case DepositCase c -> new Built(before,
                    OPS.deposit(operation, KIND_DATE, KIND_PORTFOLIO, RUB, money(RUB, c.amountMinor())),
                    money(RUB, c.amountMinor()));
            case WithdrawalCase c -> new Built(before,
                    OPS.withdrawal(operation, KIND_DATE, KIND_PORTFOLIO, RUB, money(RUB, c.amountMinor())),
                    money(RUB, c.amountMinor()).negate());
            case PurchaseCase c -> {
                Holding second = c.second();
                Money clean = money(RUB, second.cleanMinor());
                Money accrued = money(RUB, second.accruedMinor());
                Commissions fees = new Commissions(money(RUB, second.brokerMinor()), money(RUB, second.otherMinor()));
                OperationResult purchase = OPS.buy(operation, KIND_DATE, current, second.quantity(), clean, accrued,
                        money(RUB, second.faceUnitMinor()), fees);
                Money cashOut = clean.multiply(second.quantity()).add(accrued.multiply(second.quantity()))
                        .add(fees.total()).negate();
                yield new Built(before, purchase, cashOut);
            }
            case CouponCase c -> {
                Money gross = money(RUB, c.coupon().grossMinor());
                Money tax = money(RUB, c.coupon().taxMinor());
                OperationResult coupon = OPS.coupon(operation, KIND_DATE, current, gross, Optional.of(tax));
                yield new Built(before, coupon, gross.subtract(tax));
            }
            case AmortizationCase c -> {
                boolean full = c.percent() == 100 && current.purchasedAccrued().isZero();
                long face = minorOf(current.faceAmount());
                long reduction = full ? face : Math.max(1, face * Math.min(c.percent(), 99) / 100);
                Money payment = money(RUB, c.paymentMinor());
                Money tax = money(RUB, c.taxMinor());
                OperationResult amortization = OPS.amortization(operation, KIND_DATE, current,
                        money(RUB, reduction), payment, Optional.of(tax));
                yield new Built(before, amortization, payment.subtract(tax));
            }
            case SellCase c -> {
                long quantity = Math.max(1, current.quantity() * c.percent() / 100);
                Money price = money(RUB, c.priceMinor());
                Money accrued = money(RUB, c.accruedMinor());
                Money commission = money(RUB, c.commissionMinor());
                Money tax = money(RUB, c.taxMinor());
                OperationResult sale = OPS.sell(operation, KIND_DATE, current, quantity, price, accrued,
                        new Commissions(commission, Money.zero(RUB)), Optional.of(tax));
                Money cashIn = price.multiply(quantity).add(accrued.multiply(quantity))
                        .subtract(commission).subtract(tax);
                yield new Built(before, sale, cashIn);
            }
            case TaxCorrectionCase c -> {
                long withheld = c.setup().coupon().taxMinor();
                // Clamp to the valid range: a correction may not take cumulative withholding below zero.
                long adjustment = Math.max(c.adjustmentMinor(), -withheld);
                if (adjustment == 0) {
                    adjustment = 1;
                }
                Money signed = money(RUB, adjustment);
                OperationResult correction = OPS.taxCorrection(operation, KIND_DATE, KIND_PORTFOLIO,
                        kindId("setup-coupon"), signed);
                yield new Built(before, correction, signed.negate());
            }
        };
    }

    /** Every invalid-input attempt for a case: argument rejections, ledger rejections, and null discipline. */
    private static List<Attempt> rejections(KindCase kindCase, Ledger before) {
        return switch (kindCase) {
            case OpeningCase ignored -> cashRejections(before,
                    (operation, date, amount) -> OPS.opening(operation, date, KIND_PORTFOLIO, RUB, amount));
            case DepositCase ignored -> cashRejections(before,
                    (operation, date, amount) -> OPS.deposit(operation, date, KIND_PORTFOLIO, RUB, amount));
            case WithdrawalCase ignored -> withdrawalRejections(before);
            case PurchaseCase ignored -> purchaseRejections(before);
            case CouponCase ignored -> couponRejections(before);
            case AmortizationCase ignored -> amortizationRejections(before);
            case SellCase ignored -> saleRejections(before);
            case TaxCorrectionCase correction -> taxCorrectionRejections(before, correction.setup().coupon().taxMinor());
        };
    }

    private static List<Attempt> cashRejections(Ledger before, CashOperation builder) {
        OperationId fresh = kindId("rejected");
        return new ArrayList<>(List.of(
                new Attempt("zero amount", IllegalArgumentException.class,
                        () -> builder.build(fresh, KIND_DATE, money(RUB, 0))),
                new Attempt("negative amount", IllegalArgumentException.class,
                        () -> builder.build(fresh, KIND_DATE, money(RUB, -1))),
                new Attempt("foreign currency", IllegalArgumentException.class,
                        () -> builder.build(fresh, KIND_DATE, money(USD, 1))),
                new Attempt("null amount", NullPointerException.class,
                        () -> builder.build(fresh, KIND_DATE, null)),
                new Attempt("duplicate operation id", IllegalStateException.class,
                        () -> before.apply(builder.build(kindId("setup-open"), KIND_DATE, money(RUB, 1)))),
                new Attempt("backdated operation", IllegalStateException.class,
                        () -> before.apply(builder.build(fresh, DAY.plusDays(1), money(RUB, 1))))));
    }

    private static List<Attempt> withdrawalRejections(Ledger before) {
        List<Attempt> attempts = cashRejections(before,
                (operation, date, amount) -> OPS.withdrawal(operation, date, KIND_PORTFOLIO, RUB, amount));
        Money cash = cashOf(before);
        attempts.add(new Attempt("withdrawal beyond available cash", IllegalStateException.class,
                () -> before.apply(OPS.withdrawal(kindId("rejected"), KIND_DATE, KIND_PORTFOLIO, RUB,
                        cash.add(money(RUB, 1))))));
        return attempts;
    }

    private static List<Attempt> purchaseRejections(Ledger before) {
        Position current = before.position(KIND_SECURITY).orElseThrow();
        Position stale = staleOf(current);
        OperationId fresh = kindId("rejected");
        Commissions none = new Commissions(money(RUB, 0), money(RUB, 0));
        return List.of(
                new Attempt("zero quantity", IllegalArgumentException.class,
                        () -> OPS.buy(fresh, KIND_DATE, current, 0, money(RUB, 1), money(RUB, 0), money(RUB, 1), none)),
                new Attempt("negative clean price", IllegalArgumentException.class,
                        () -> OPS.buy(fresh, KIND_DATE, current, 1, money(RUB, -1), money(RUB, 0), money(RUB, 1), none)),
                new Attempt("zero face per unit", IllegalArgumentException.class,
                        () -> OPS.buy(fresh, KIND_DATE, current, 1, money(RUB, 1), money(RUB, 0), money(RUB, 0), none)),
                new Attempt("negative commission", IllegalArgumentException.class,
                        () -> OPS.buy(fresh, KIND_DATE, current, 1, money(RUB, 1), money(RUB, 0), money(RUB, 1),
                                new Commissions(money(RUB, -1), money(RUB, 0)))),
                new Attempt("foreign-currency clean price", IllegalArgumentException.class,
                        () -> OPS.buy(fresh, KIND_DATE, current, 1, money(USD, 1), money(RUB, 0), money(RUB, 1), none)),
                new Attempt("null clean price", NullPointerException.class,
                        () -> OPS.buy(fresh, KIND_DATE, current, 1, null, money(RUB, 0), money(RUB, 1), none)),
                new Attempt("purchase beyond available cash", IllegalStateException.class,
                        () -> before.apply(OPS.buy(fresh, KIND_DATE, current, 1_000_000_000L, money(RUB, 1_000_000),
                                money(RUB, 0), money(RUB, 1), none))),
                new Attempt("stale position snapshot", IllegalStateException.class,
                        () -> before.apply(OPS.buy(fresh, KIND_DATE, stale, 1, money(RUB, 1), money(RUB, 0),
                                money(RUB, 1), none))),
                new Attempt("duplicate operation id", IllegalStateException.class,
                        () -> before.apply(OPS.buy(kindId("setup-buy"), KIND_DATE, current, 1, money(RUB, 1),
                                money(RUB, 0), money(RUB, 1), none))),
                new Attempt("backdated operation", IllegalStateException.class,
                        () -> before.apply(OPS.buy(fresh, DAY.plusDays(1), current, 1, money(RUB, 1), money(RUB, 0),
                                money(RUB, 1), none))));
    }

    private static List<Attempt> couponRejections(Ledger before) {
        Position current = before.position(KIND_SECURITY).orElseThrow();
        Position stale = staleOf(current);
        OperationId fresh = kindId("rejected");
        return List.of(
                new Attempt("negative gross", IllegalArgumentException.class,
                        () -> OPS.coupon(fresh, KIND_DATE, current, money(RUB, -1), Optional.empty())),
                new Attempt("tax above gross", IllegalArgumentException.class,
                        () -> OPS.coupon(fresh, KIND_DATE, current, money(RUB, 100), Optional.of(money(RUB, 101)))),
                new Attempt("negative tax", IllegalArgumentException.class,
                        () -> OPS.coupon(fresh, KIND_DATE, current, money(RUB, 100), Optional.of(money(RUB, -1)))),
                new Attempt("coupon on a closed position", IllegalArgumentException.class,
                        () -> OPS.coupon(fresh, KIND_DATE, Position.empty(KIND_POSITION, RUB), money(RUB, 100),
                                Optional.empty())),
                new Attempt("null tax optional", NullPointerException.class,
                        () -> OPS.coupon(fresh, KIND_DATE, current, money(RUB, 100), null)),
                new Attempt("null gross", NullPointerException.class,
                        () -> OPS.coupon(fresh, KIND_DATE, current, null, Optional.empty())),
                new Attempt("stale position snapshot", IllegalStateException.class,
                        () -> before.apply(OPS.coupon(fresh, KIND_DATE, stale, money(RUB, 100), Optional.empty()))),
                new Attempt("duplicate operation id", IllegalStateException.class,
                        () -> before.apply(OPS.coupon(kindId("setup-coupon"), KIND_DATE, current, money(RUB, 100),
                                Optional.empty()))),
                new Attempt("backdated operation", IllegalStateException.class,
                        () -> before.apply(OPS.coupon(fresh, DAY.plusDays(1), current, money(RUB, 100),
                                Optional.empty()))));
    }

    private static List<Attempt> amortizationRejections(Ledger before) {
        Position current = before.position(KIND_SECURITY).orElseThrow();
        Position stale = staleOf(current);
        OperationId fresh = kindId("rejected");
        long face = minorOf(current.faceAmount());
        List<Attempt> attempts = new ArrayList<>(List.of(
                new Attempt("zero face reduction", IllegalArgumentException.class,
                        () -> OPS.amortization(fresh, KIND_DATE, current, money(RUB, 0), money(RUB, 1),
                                Optional.empty())),
                new Attempt("face reduction above face", IllegalArgumentException.class,
                        () -> OPS.amortization(fresh, KIND_DATE, current, money(RUB, face + 1), money(RUB, 1),
                                Optional.empty())),
                new Attempt("negative payment", IllegalArgumentException.class,
                        () -> OPS.amortization(fresh, KIND_DATE, current, money(RUB, 1), money(RUB, -1),
                                Optional.empty())),
                new Attempt("tax above payment", IllegalArgumentException.class,
                        () -> OPS.amortization(fresh, KIND_DATE, current, money(RUB, 1), money(RUB, 1),
                                Optional.of(money(RUB, 2)))),
                new Attempt("null face reduction", NullPointerException.class,
                        () -> OPS.amortization(fresh, KIND_DATE, current, null, money(RUB, 1), Optional.empty())),
                new Attempt("stale position snapshot", IllegalStateException.class,
                        () -> before.apply(OPS.amortization(fresh, KIND_DATE, stale, money(RUB, 1), money(RUB, 1),
                                Optional.empty()))),
                new Attempt("duplicate operation id", IllegalStateException.class,
                        () -> before.apply(OPS.amortization(kindId("setup-coupon"), KIND_DATE, current,
                                money(RUB, 1), money(RUB, 1), Optional.empty()))),
                new Attempt("backdated operation", IllegalStateException.class,
                        () -> before.apply(OPS.amortization(fresh, DAY.plusDays(1), current, money(RUB, 1),
                                money(RUB, 1), Optional.empty())))));
        if (!current.purchasedAccrued().isZero()) {
            // Full principal reduction is refused while purchased accrued interest remains on the position.
            attempts.add(new Attempt("full redemption with accrued interest left", IllegalArgumentException.class,
                    () -> OPS.amortization(fresh, KIND_DATE, current, current.faceAmount(), money(RUB, 1),
                            Optional.empty())));
        }
        return attempts;
    }

    private static List<Attempt> saleRejections(Ledger before) {
        Position current = before.position(KIND_SECURITY).orElseThrow();
        Position stale = staleOf(current);
        OperationId fresh = kindId("rejected");
        Commissions none = new Commissions(money(RUB, 0), money(RUB, 0));
        return List.of(
                new Attempt("zero quantity", IllegalArgumentException.class,
                        () -> OPS.sell(fresh, KIND_DATE, current, 0, money(RUB, 1), money(RUB, 0), none,
                                Optional.empty())),
                new Attempt("quantity above holding", IllegalArgumentException.class,
                        () -> OPS.sell(fresh, KIND_DATE, current, current.quantity() + 1, money(RUB, 1),
                                money(RUB, 0), none, Optional.empty())),
                new Attempt("negative price", IllegalArgumentException.class,
                        () -> OPS.sell(fresh, KIND_DATE, current, 1, money(RUB, -1), money(RUB, 0), none,
                                Optional.empty())),
                new Attempt("negative accrued", IllegalArgumentException.class,
                        () -> OPS.sell(fresh, KIND_DATE, current, 1, money(RUB, 1), money(RUB, -1), none,
                                Optional.empty())),
                new Attempt("negative commission", IllegalArgumentException.class,
                        () -> OPS.sell(fresh, KIND_DATE, current, 1, money(RUB, 1), money(RUB, 0),
                                new Commissions(money(RUB, -1), money(RUB, 0)), Optional.empty())),
                new Attempt("negative tax", IllegalArgumentException.class,
                        () -> OPS.sell(fresh, KIND_DATE, current, 1, money(RUB, 1), money(RUB, 0), none,
                                Optional.of(money(RUB, -1)))),
                new Attempt("sale of a closed position", IllegalArgumentException.class,
                        () -> OPS.sell(fresh, KIND_DATE, Position.empty(KIND_POSITION, RUB), 1, money(RUB, 1),
                                money(RUB, 0), none, Optional.empty())),
                new Attempt("null price", NullPointerException.class,
                        () -> OPS.sell(fresh, KIND_DATE, current, 1, null, money(RUB, 0), none, Optional.empty())),
                new Attempt("stale position snapshot", IllegalStateException.class,
                        () -> before.apply(OPS.sell(fresh, KIND_DATE, stale, 1, money(RUB, 1), money(RUB, 0), none,
                                Optional.empty()))),
                new Attempt("duplicate operation id", IllegalStateException.class,
                        () -> before.apply(OPS.sell(kindId("setup-buy"), KIND_DATE, current, 1, money(RUB, 1),
                                money(RUB, 0), none, Optional.empty()))),
                new Attempt("backdated operation", IllegalStateException.class,
                        () -> before.apply(OPS.sell(fresh, DAY.plusDays(1), current, 1, money(RUB, 1), money(RUB, 0),
                                none, Optional.empty()))));
    }

    private static List<Attempt> taxCorrectionRejections(Ledger before, long withheldMinor) {
        OperationId fresh = kindId("rejected");
        OperationId original = kindId("setup-coupon");
        Ledger corrected = before.apply(OPS.taxCorrection(kindId("correction"), KIND_DATE, KIND_PORTFOLIO, original,
                money(RUB, 1)));
        return List.of(
                new Attempt("zero adjustment", IllegalArgumentException.class,
                        () -> OPS.taxCorrection(fresh, KIND_DATE, KIND_PORTFOLIO, original, money(RUB, 0))),
                new Attempt("correction equal to its original", IllegalArgumentException.class,
                        () -> OPS.taxCorrection(original, KIND_DATE, KIND_PORTFOLIO, original, money(RUB, 1))),
                new Attempt("null adjustment", NullPointerException.class,
                        () -> OPS.taxCorrection(fresh, KIND_DATE, KIND_PORTFOLIO, original, null)),
                new Attempt("unknown original", IllegalStateException.class,
                        () -> before.apply(OPS.taxCorrection(fresh, KIND_DATE, KIND_PORTFOLIO, kindId("unknown"),
                                money(RUB, 1)))),
                new Attempt("original without withholding", IllegalStateException.class,
                        () -> before.apply(OPS.taxCorrection(fresh, KIND_DATE, KIND_PORTFOLIO, kindId("setup-buy"),
                                money(RUB, 1)))),
                new Attempt("refund below zero cumulative", IllegalStateException.class,
                        () -> before.apply(OPS.taxCorrection(fresh, KIND_DATE, KIND_PORTFOLIO, original,
                                money(RUB, -(withheldMinor + 1))))),
                new Attempt("correction of a correction", IllegalStateException.class,
                        () -> corrected.apply(OPS.taxCorrection(fresh, KIND_DATE, KIND_PORTFOLIO, kindId("correction"),
                                money(RUB, 1)))),
                new Attempt("foreign-currency correction", IllegalStateException.class,
                        () -> before.apply(OPS.taxCorrection(fresh, KIND_DATE, KIND_PORTFOLIO, original,
                                money(USD, 1)))),
                new Attempt("duplicate operation id", IllegalStateException.class,
                        () -> before.apply(OPS.taxCorrection(kindId("setup-buy"), KIND_DATE, KIND_PORTFOLIO, original,
                                money(RUB, 1)))),
                new Attempt("backdated correction", IllegalStateException.class,
                        () -> before.apply(OPS.taxCorrection(fresh, DAY.plusDays(1), KIND_PORTFOLIO, original,
                                money(RUB, 1)))));
    }

    private static Position staleOf(Position current) {
        return new Position(current.id(), current.quantity(), current.carryingAmount().add(money(RUB, 1)),
                current.faceAmount(), current.purchasedAccrued());
    }

    /** Ledger-wide invariants: balanced accounts, non-negative cash when required, position mirrors, round-trip. */
    private static void assertLedgerInvariants(Ledger ledger) {
        LedgerState state = ledger.state();
        PortfolioId portfolio = state.portfolio();
        assertBalancesSumToZero(ledger);
        if (!state.allowNegativeCash()) {
            ledger.cashBalances().forEach((currency, cash) ->
                    assertFalse(cash.isNegative(), "cash in " + currency + " stays non-negative"));
        }
        ledger.positions().forEach((security, position) -> {
            Currency currency = position.carryingAmount().currency();
            assertEquals(position.carryingAmount(),
                    ledger.balance(account(portfolio, POLICY.securities(), security, currency)),
                    "carrying amount mirrors the securities account");
            assertEquals(position.purchasedAccrued(),
                    ledger.balance(account(portfolio, POLICY.accruedInterest(), security, currency)),
                    "purchased accrued interest mirrors the accrued-interest account");
        });
        assertEquals(state, Ledger.restore(state).state(), "restore re-validates the ledger and round-trips it");
    }

    private static void assertHistoryIsAppendOnly(LedgerState before, LedgerState after) {
        assertTrue(after.operationIds().containsAll(before.operationIds()), "operation identities are never removed");
        before.postingsByDate().forEach((date, postings) -> assertEquals(postings,
                after.postingsByDate().get(date).subList(0, postings.size()),
                "history of " + date + " only grows by appending"));
    }

    private static Money cashOf(Ledger ledger) {
        return ledger.cashBalances().getOrDefault(RUB, Money.zero(RUB));
    }

    private static Ledger replayOn(Ledger start, List<OperationResult> operations) {
        Ledger ledger = start;
        for (OperationResult operation : operations) {
            ledger = ledger.apply(operation);
        }
        return ledger;
    }

    /** The accepted journal of one random run (openings included) together with the resulting ledger. */
    private record Recording(List<OperationResult> operations, Ledger ledger) { }

    private static Recording play(int caseNumber, List<Step> steps) {
        PortfolioId portfolio = portfolio(caseNumber);
        Ledger ledger = Ledger.open(portfolio, CHART, false);
        List<OperationResult> journal = new ArrayList<>();
        for (Currency currency : CURRENCIES) {
            OperationResult opening = OPS.opening(operationId(caseNumber, "agg-open-" + code(currency)),
                    DAY, portfolio, currency, money(currency, OPENING_MINOR));
            ledger = ledger.apply(opening);
            journal.add(opening);
        }
        assertBalancesSumToZero(ledger);

        List<TaxedOperation> taxedOperations = new ArrayList<>();
        for (int index = 0; index < steps.size(); index++) {
            Step step = steps.get(index);
            OperationId operation = operationId(caseNumber, "agg-" + index);
            LocalDate date = DAY.plusDays(index + 1L);
            OperationResult result;
            try {
                result = build(step, ledger, portfolio, operation, date, taxedOperations);
            } catch (UnbalancedPostingException defect) {
                throw defect;
            } catch (IllegalArgumentException | IllegalStateException rejected) {
                continue;
            }
            Ledger next;
            try {
                next = ledger.apply(result);
            } catch (UnbalancedPostingException defect) {
                throw defect;
            } catch (IllegalArgumentException | IllegalStateException rejected) {
                continue;
            }

            assertEveryPostingBalancesByCurrency(result);
            assertBalancesSumToZero(next);
            ledger = next;
            journal.add(result);
            withheldCurrency(step).ifPresent(currency -> taxedOperations.add(new TaxedOperation(operation, currency)));
        }
        return new Recording(List.copyOf(journal), ledger);
    }

    /** Currency of the withholding a step adds to the ledger, when the step withholds anything. */
    private static Optional<Currency> withheldCurrency(Step step) {
        return switch (step) {
            case Step.Coupon coupon when coupon.taxMinor() > 0 -> Optional.of(CURRENCIES.get(coupon.securityIndex()));
            case Step.Amortization amortization when amortization.taxMinor() > 0 ->
                    Optional.of(CURRENCIES.get(amortization.securityIndex()));
            case Step.Sell sell when sell.taxMinor() > 0 -> Optional.of(CURRENCIES.get(sell.securityIndex()));
            default -> Optional.empty();
        };
    }

    /** Openings first, then one purchase per currency: the ledger keeps one chronology across all currencies. */
    private static Ledger openChain(PortfolioId portfolio, int caseNumber) {
        Ledger ledger = Ledger.open(portfolio, CHART, false);
        for (Currency currency : CHAIN_CURRENCIES) {
            ledger = ledger.apply(OPS.opening(operationId(caseNumber, "chain-open-" + code(currency)), DAY,
                    portfolio, currency, money(currency, CHAIN_OPENING_MINOR)));
        }
        for (int index = 0; index < CHAIN_CURRENCIES.size(); index++) {
            Currency currency = CHAIN_CURRENCIES.get(index);
            ledger = ledger.apply(OPS.buy(operationId(caseNumber, "chain-buy-" + code(currency)), DAY.plusDays(1),
                    Position.empty(new PositionId(portfolio, CHAIN_SECURITIES.get(index)), currency), 1,
                    money(currency, 10_000), money(currency, 0), money(currency, 100_000),
                    new Commissions(money(currency, 0), money(currency, 0))));
        }
        return ledger;
    }

    /** Independent oracle of withholding: per-original cumulative amounts and per-currency totals, in minor units. */
    private static final class WithholdingModel {
        private final List<OperationId> originals = new ArrayList<>();
        private final List<OperationId> corrections = new ArrayList<>();
        private final Map<OperationId, Currency> currencies = new HashMap<>();
        private final Map<OperationId, Long> cumulative = new HashMap<>();
        private final Map<Currency, Long> totals = new HashMap<>();

        void addOriginal(OperationId operation, Currency currency, long taxMinor) {
            originals.add(operation);
            currencies.put(operation, currency);
            cumulative.put(operation, taxMinor);
            totals.merge(currency, taxMinor, Long::sum);
        }

        void addCorrection(OperationId operation, OperationId original, long adjustmentMinor) {
            Currency currency = currencies.get(original);
            corrections.add(operation);
            currencies.put(operation, currency);
            cumulative.merge(original, adjustmentMinor, Long::sum);
            totals.merge(currency, adjustmentMinor, Long::sum);
        }

        List<OperationId> originals() {
            return originals;
        }

        List<OperationId> corrections() {
            return corrections;
        }

        boolean hasOriginals() {
            return !originals.isEmpty();
        }

        boolean hasCorrections() {
            return !corrections.isEmpty();
        }

        OperationId original(int selector) {
            return originals.get(Math.floorMod(selector, originals.size()));
        }

        OperationId correction(int selector) {
            return corrections.get(Math.floorMod(selector, corrections.size()));
        }

        Currency currencyOf(OperationId operation) {
            return currencies.get(operation);
        }

        long cumulative(OperationId original) {
            return cumulative.get(original);
        }

        long total(Currency currency) {
            return totals.getOrDefault(currency, 0L);
        }
    }

    /** Applies chain steps to a real ledger and checks every result against the withholding model. */
    private static final class ChainRun {
        private final int caseNumber;
        private final PortfolioId portfolio;
        private final WithholdingModel model = new WithholdingModel();
        private Ledger ledger;

        ChainRun(int caseNumber) {
            this.caseNumber = caseNumber;
            this.portfolio = portfolio(caseNumber);
            this.ledger = openChain(portfolio, caseNumber);
        }

        void apply(int index, ChainStep step) {
            String label = "chain-" + index;
            LocalDate date = DAY.plusDays(index + 2L);
            LedgerState before = ledger.state();
            switch (step) {
                case ChainStep.Taxed taxed -> withholdingIncome(label, date, taxed);
                case ChainStep.Correct correct -> {
                    if (model.hasOriginals()) {
                        correct(label, date, model.original(correct.target()), correct.adjustmentMinor());
                    }
                }
                case ChainStep.ExactRefund refund -> {
                    if (model.hasOriginals()) {
                        exactRefund(label, date, model.original(refund.target()));
                    }
                }
                case ChainStep.OverRefund refund -> {
                    if (model.hasOriginals()) {
                        OperationId target = model.original(refund.target());
                        correct(label, date, target, -(model.cumulative(target) + 1));
                    }
                }
                case ChainStep.CorrectCorrection correction -> {
                    if (model.hasCorrections()) {
                        correctCorrection(label, date, model.correction(correction.target()),
                                correction.adjustmentMinor());
                    }
                }
            }
            assertHistoryIsAppendOnly(before, ledger.state());
            for (Currency currency : CHAIN_CURRENCIES) {
                assertEquals(money(currency, model.total(currency)),
                        ledger.taxWithheldBalances().getOrDefault(currency, Money.zero(currency)),
                        "withheld balance equals the sum of cumulative withholdings");
            }
        }

        void assertFinalState() {
            for (OperationId original : model.originals()) {
                assertWithheldMatchesModel(original);
            }
            for (OperationId correction : model.corrections()) {
                Ledger finalLedger = ledger;
                assertThrows(IllegalArgumentException.class,
                        () -> finalLedger.taxWithheldForOperation(correction),
                        "a correction cannot be queried as an original operation");
            }
            assertBalancesSumToZero(ledger);
            assertEquals(ledger.state(), Ledger.restore(ledger.state()).state(), "the chain's final state round-trips");
        }

        private void withholdingIncome(String label, LocalDate date, ChainStep.Taxed taxed) {
            Currency currency = CHAIN_CURRENCIES.get(taxed.currencyIndex());
            Position held = ledger.position(CHAIN_SECURITIES.get(taxed.currencyIndex())).orElseThrow();
            OperationResult coupon = OPS.coupon(operationId(caseNumber, label), date, held,
                    money(currency, taxed.grossMinor()), Optional.of(money(currency, taxed.taxMinor())));
            ledger = ledger.apply(coupon);
            model.addOriginal(coupon.operation(), currency, taxed.taxMinor());
            assertWithheldMatchesModel(coupon.operation());
            assertEquals(coupon.postings(), ledger.postingsByDate().get(date), "the coupon appends its own bucket");
        }

        private void correct(String label, LocalDate date, OperationId target, long adjustmentMinor) {
            Currency currency = model.currencyOf(target);
            OperationResult correction = OPS.taxCorrection(operationId(caseNumber, label), date, portfolio, target,
                    money(currency, adjustmentMinor));
            if (model.cumulative(target) + adjustmentMinor < 0) {
                Ledger unchanged = ledger;
                assertThrows(IllegalStateException.class, () -> unchanged.apply(correction),
                        "a correction below zero cumulative withholding is rejected");
                return;
            }
            Ledger before = ledger;
            ledger = ledger.apply(correction);
            model.addCorrection(correction.operation(), target, adjustmentMinor);
            assertWithheldMatchesModel(target);
            assertEquals(correction.postings(), ledger.postingsByDate().get(date), "the correction appends its own bucket");
            assertOnlyCashAndWithheldMoved(before, ledger, portfolio, currency, adjustmentMinor);
        }

        private void exactRefund(String label, LocalDate date, OperationId target) {
            long cumulative = model.cumulative(target);
            if (cumulative == 0) {
                Currency currency = model.currencyOf(target);
                assertThrows(IllegalArgumentException.class,
                        () -> OPS.taxCorrection(operationId(caseNumber, label), date, portfolio, target,
                                money(currency, 0)),
                        "a zero correction is not a correction");
                return;
            }
            // Refunding exactly the cumulative amount is accepted and leaves zero withholding.
            correct(label, date, target, -cumulative);
        }

        private void correctCorrection(String label, LocalDate date, OperationId correction, long adjustmentMinor) {
            OperationResult attempt = OPS.taxCorrection(operationId(caseNumber, label), date, portfolio, correction,
                    money(model.currencyOf(correction), adjustmentMinor));
            Ledger unchanged = ledger;
            assertThrows(IllegalStateException.class, () -> unchanged.apply(attempt),
                    "a correction cannot itself be corrected");
        }

        private void assertWithheldMatchesModel(OperationId original) {
            Money withheld = ledger.taxWithheldForOperation(original);
            assertFalse(withheld.isNegative(), "cumulative withholding stays non-negative after corrections");
            assertEquals(money(model.currencyOf(original), model.cumulative(original)), withheld,
                    "withholding of the original matches the model");
        }
    }

    /** A correction changes exactly the cash account (by minus the adjustment) and the withheld-tax account (by it). */
    private static void assertOnlyCashAndWithheldMoved(Ledger before, Ledger after, PortfolioId portfolio,
                                                        Currency currency, long adjustmentMinor) {
        Money delta = money(currency, adjustmentMinor);
        Map<Account, Money> expected = new HashMap<>(before.balances());
        expected.merge(account(portfolio, POLICY.cash(), null, currency), delta.negate(), Money::add);
        expected.merge(account(portfolio, POLICY.taxWithheld(), null, currency), delta, Money::add);
        expected.values().removeIf(Money::isZero);
        assertEquals(expected, after.balances(), "a correction moves only cash and withheld tax");
        assertEquals(before.positions(), after.positions(), "a correction leaves positions unchanged");
    }

    private static void assertEveryPostingBalancesByCurrency(OperationResult result) {
        for (Posting posting : result.postings()) {
            Map<Currency, BigDecimal> totals = new HashMap<>();
            for (Posting.Entry entry : posting.entries()) {
                totals.merge(entry.amount().currency(), entry.amount().amount(), BigDecimal::add);
            }
            totals.forEach((currency, total) -> assertEquals(0, total.compareTo(BigDecimal.ZERO),
                    "posting balances independently in " + currency));
        }
    }

    private static void assertBalancesSumToZero(Ledger ledger) {
        Map<Currency, BigDecimal> totals = new HashMap<>();
        ledger.balances().forEach((account, amount) ->
                totals.merge(amount.currency(), amount.amount(), BigDecimal::add));
        totals.forEach((currency, total) -> assertEquals(0, total.compareTo(BigDecimal.ZERO),
                "all account balances sum to zero in " + currency));
    }

    private static Money entryAmount(Posting posting, Account account) {
        return posting.entries().stream().filter(entry -> entry.account().equals(account))
                .map(Posting.Entry::amount).reduce(Money.zero(account.currency()), Money::add);
    }

    private static Account account(PortfolioId portfolio, AccountCode code, SecurityId security, Currency currency) {
        return new Account(portfolio, code, Optional.ofNullable(security), currency);
    }

    private static Money money(Currency currency, long minorUnits) {
        return new Money(BigDecimal.valueOf(minorUnits, currency.getDefaultFractionDigits()), currency);
    }

    private static long minorOf(Money amount) {
        return amount.amount().movePointRight(amount.currency().getDefaultFractionDigits()).longValueExact();
    }

    private static Money rubTicks(long ticks) {
        return new Money(BigDecimal.valueOf(ticks, 4), RUB);
    }

    private static String code(Currency currency) {
        return currency.getCurrencyCode().toLowerCase();
    }

    private static PortfolioId portfolio(int caseNumber) {
        return new PortfolioId("ledger-properties-" + caseNumber);
    }

    private static OperationId operationId(int caseNumber, String label) {
        return new OperationId("ledger-properties-" + caseNumber + "-" + label);
    }

    private record SaleCase(int quantity, int partialQuantity, long unitTicks, long accruedTicks, long faceTicks) { }

    private record TaxCase(long grossMinor, long refundMinor) { }

    private record TaxedOperation(OperationId operation, Currency currency) { }

    private sealed interface Step {
        record Deposit(int currencyIndex, long minor) implements Step { }

        record Withdrawal(int currencyIndex, long minor) implements Step { }

        record Buy(int securityIndex, int quantity, long cleanMinor, long accruedMinor, long faceMinor,
                   long brokerMinor, long otherMinor) implements Step { }

        record Sell(int securityIndex, int quantity, long cleanMinor, long accruedMinor, long commissionMinor,
                    long taxMinor) implements Step { }

        record Coupon(int securityIndex, long grossMinor, long taxMinor) implements Step { }

        record Amortization(int securityIndex, int percent, long paymentMinor, long taxMinor) implements Step { }

        record Correction(int selector, long adjustmentMinor) implements Step { }
    }

    private static OperationResult build(Step step, Ledger ledger, PortfolioId portfolio, OperationId operation,
                                         LocalDate date, List<TaxedOperation> taxedOperations) {
        return switch (step) {
            case Step.Deposit deposit -> OPS.deposit(operation, date, portfolio,
                    CURRENCIES.get(deposit.currencyIndex()), money(CURRENCIES.get(deposit.currencyIndex()), deposit.minor()));
            case Step.Withdrawal withdrawal -> OPS.withdrawal(operation, date, portfolio,
                    CURRENCIES.get(withdrawal.currencyIndex()),
                    money(CURRENCIES.get(withdrawal.currencyIndex()), withdrawal.minor()));
            case Step.Buy buy -> buy(buy, ledger, portfolio, operation, date);
            case Step.Sell sell -> sell(sell, ledger, portfolio, operation, date);
            case Step.Coupon coupon -> {
                SecurityId security = AGGREGATE_SECURITIES.get(coupon.securityIndex());
                Currency currency = CURRENCIES.get(coupon.securityIndex());
                Position current = ledger.position(security).orElseThrow(() ->
                        new IllegalStateException("coupon requires a position"));
                yield OPS.coupon(operation, date, current, money(currency, coupon.grossMinor()),
                        Optional.of(money(currency, coupon.taxMinor())));
            }
            case Step.Amortization amortization -> amortize(amortization, ledger, portfolio, operation, date);
            case Step.Correction correction -> {
                if (taxedOperations.isEmpty()) {
                    throw new IllegalStateException("no taxed operation to correct");
                }
                TaxedOperation target = taxedOperations.get(Math.floorMod(correction.selector(), taxedOperations.size()));
                yield OPS.taxCorrection(operation, date, portfolio, target.operation(),
                        money(target.currency(), correction.adjustmentMinor()));
            }
        };
    }

    private static OperationResult buy(Step.Buy buy, Ledger ledger, PortfolioId portfolio,
                                       OperationId operation, LocalDate date) {
        SecurityId security = AGGREGATE_SECURITIES.get(buy.securityIndex());
        Currency currency = CURRENCIES.get(buy.securityIndex());
        Position current = ledger.position(security)
                .orElse(Position.empty(new PositionId(portfolio, security), currency));
        return OPS.buy(operation, date, current, buy.quantity(), money(currency, buy.cleanMinor()),
                money(currency, buy.accruedMinor()), money(currency, buy.faceMinor()),
                new Commissions(money(currency, buy.brokerMinor()), money(currency, buy.otherMinor())));
    }

    private static OperationResult sell(Step.Sell sell, Ledger ledger, PortfolioId portfolio,
                                        OperationId operation, LocalDate date) {
        SecurityId security = AGGREGATE_SECURITIES.get(sell.securityIndex());
        Currency currency = CURRENCIES.get(sell.securityIndex());
        Position current = ledger.position(security).orElseThrow(() ->
                new IllegalStateException("sale requires a position"));
        long quantity = Math.min(sell.quantity(), current.quantity());
        return OPS.sell(operation, date, current, quantity, money(currency, sell.cleanMinor()),
                money(currency, sell.accruedMinor()),
                new Commissions(money(currency, sell.commissionMinor()), Money.zero(currency)),
                Optional.of(money(currency, sell.taxMinor())));
    }

    private static OperationResult amortize(Step.Amortization amortization, Ledger ledger, PortfolioId portfolio,
                                            OperationId operation, LocalDate date) {
        SecurityId security = AGGREGATE_SECURITIES.get(amortization.securityIndex());
        Currency currency = CURRENCIES.get(amortization.securityIndex());
        Position current = ledger.position(security).orElseThrow(() ->
                new IllegalStateException("amortization requires a position"));
        boolean full = amortization.percent() == 100 && current.purchasedAccrued().isZero();
        long face = minorOf(current.faceAmount());
        long reduction = full ? face : Math.max(1, face * Math.min(amortization.percent(), 99) / 100);
        return OPS.amortization(operation, date, current, money(currency, reduction),
                money(currency, amortization.paymentMinor()), Optional.of(money(currency, amortization.taxMinor())));
    }

    /**
     * Independent oracle in integer price ticks: the released amount is the exact proportional share
     * rounded HALF_UP to whole minor units, then capped at the amount itself when rounding would overdraw it.
     * Uses only long arithmetic and never reads the production rounding code.
     */
    private static long expectedRemovalTicks(long amountTicks, long removedQuantity, long totalQuantity) {
        long numerator = amountTicks * removedQuantity;
        long denominator = totalQuantity * TICKS_PER_MINOR_UNIT;
        long roundedMinorUnits = (2 * numerator + denominator) / (2 * denominator);
        return Math.min(roundedMinorUnits * TICKS_PER_MINOR_UNIT, amountTicks);
    }

    /** Positive kind-case inputs: a holding, a taxed coupon, and the case-specific amounts. */
    private record Holding(int quantity, long cleanMinor, long accruedMinor, long faceUnitMinor,
                           long brokerMinor, long otherMinor) { }

    private record CouponTerms(long grossMinor, long taxMinor) { }

    private record Setup(Holding holding, CouponTerms coupon) { }

    private sealed interface KindCase {
        Setup setup();

        OperationKind kind();
    }

    private record OpeningCase(Setup setup, long amountMinor) implements KindCase {
        @Override
        public OperationKind kind() {
            return OperationKind.OPENING;
        }
    }

    private record DepositCase(Setup setup, long amountMinor) implements KindCase {
        @Override
        public OperationKind kind() {
            return OperationKind.DEPOSIT;
        }
    }

    private record WithdrawalCase(Setup setup, long amountMinor) implements KindCase {
        @Override
        public OperationKind kind() {
            return OperationKind.WITHDRAWAL;
        }
    }

    private record PurchaseCase(Setup setup, Holding second) implements KindCase {
        @Override
        public OperationKind kind() {
            return OperationKind.PURCHASE;
        }
    }

    private record CouponCase(Setup setup, CouponTerms coupon) implements KindCase {
        @Override
        public OperationKind kind() {
            return OperationKind.COUPON;
        }
    }

    /** Percent of the face reduced by a partial amortization; 100 requests full redemption when it is allowed. */
    private record AmortizationCase(Setup setup, int percent, long paymentMinor, long taxMinor) implements KindCase {
        @Override
        public OperationKind kind() {
            return OperationKind.AMORTIZATION;
        }
    }

    private record SellCase(Setup setup, int percent, long priceMinor, long accruedMinor, long commissionMinor,
                            long taxMinor) implements KindCase {
        @Override
        public OperationKind kind() {
            return OperationKind.SALE;
        }
    }

    private record TaxCorrectionCase(Setup setup, long adjustmentMinor) implements KindCase {
        @Override
        public OperationKind kind() {
            return OperationKind.TAX_CORRECTION;
        }
    }

    /** The accepted operation of a kind case, the ledger it applies to, and its expected cash movement. */
    private record Built(Ledger before, OperationResult operation, Money cashDelta) { }

    /** An invalid attempt: the expected exception type and the action that must throw it. */
    private record Attempt(String label, Class<? extends Throwable> expected, Executable action) { }

    @FunctionalInterface
    private interface CashOperation {
        OperationResult build(OperationId operation, LocalDate date, Money amount);
    }

    /** One step of a withholding chain: a taxed income, a correction, or an attempt to break a rule. */
    private sealed interface ChainStep {
        record Taxed(int currencyIndex, long grossMinor, long taxMinor) implements ChainStep { }

        record Correct(int target, long adjustmentMinor) implements ChainStep { }

        record ExactRefund(int target) implements ChainStep { }

        record OverRefund(int target) implements ChainStep { }

        record CorrectCorrection(int target, long adjustmentMinor) implements ChainStep { }
    }
}
