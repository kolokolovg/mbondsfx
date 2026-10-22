package ru.onlychill.mbondsfx.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Provide;
import net.jqwik.api.Property;
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
import ru.onlychill.mbondsfx.core.ledger.Position;
import ru.onlychill.mbondsfx.core.ledger.Posting;
import ru.onlychill.mbondsfx.core.ledger.UnbalancedPostingException;
import ru.onlychill.mbondsfx.core.money.Money;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
     * Random mixed sequences of deposits, withdrawals, buys, sales, coupons and tax corrections in RUB, USD
     * and JPY. Domain rejections are expected for some steps (insufficient cash, empty position, over-refund);
     * a rejected step never changes the ledger. Every accepted operation keeps each posting balanced, and the
     * whole ledger keeps every currency summing to zero.
     */
    @Property(tries = 250)
    void randomOperationSequencesKeepEveryPostingAndWholeLedgerBalanced(
            @ForAll("caseNumbers") int caseNumber,
            @ForAll("ledgerSteps") List<Step> steps) {
        PortfolioId portfolio = portfolio(caseNumber);
        Ledger ledger = Ledger.open(portfolio, CHART, false);
        for (Currency currency : CURRENCIES) {
            ledger = ledger.apply(OPS.opening(operationId(caseNumber, "agg-open-" + code(currency)),
                    DAY, portfolio, currency, money(currency, OPENING_MINOR)));
        }
        assertBalancesSumToZero(ledger);

        List<TaxedCoupon> taxedCoupons = new ArrayList<>();
        for (int index = 0; index < steps.size(); index++) {
            Step step = steps.get(index);
            OperationId operation = operationId(caseNumber, "agg-" + index);
            LocalDate date = DAY.plusDays(index + 1L);
            OperationResult result;
            try {
                result = build(step, ledger, portfolio, operation, date, taxedCoupons);
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
            if (step instanceof Step.Coupon coupon && coupon.taxMinor() > 0) {
                taxedCoupons.add(new TaxedCoupon(operation, CURRENCIES.get(coupon.securityIndex())));
            }
        }

        assertBalancesSumToZero(ledger);
        assertEquals(CHART, Ledger.restore(ledger.state()).chart(), "chart survives restore after random history");
        assertEquals(ledger.state(), Ledger.restore(ledger.state()).state(), "random history round-trips");
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
        Arbitrary<Step> corrections = Combinators.combine(Arbitraries.integers().between(0, 100),
                        Arbitraries.longs().between(-10_000_000L, 10_000_000L).filter(adjustment -> adjustment != 0))
                .as((selector, adjustment) -> new Step.Correction(selector, adjustment));
        return Arbitraries.oneOf(deposits, withdrawals, buys, sales, coupons, corrections);
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

    private static OperationResult build(Step step, Ledger ledger, PortfolioId portfolio, OperationId operation,
                                         LocalDate date, List<TaxedCoupon> taxedCoupons) {
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
            case Step.Correction correction -> {
                if (taxedCoupons.isEmpty()) {
                    throw new IllegalStateException("no taxed coupon to correct");
                }
                TaxedCoupon target = taxedCoupons.get(Math.floorMod(correction.selector(), taxedCoupons.size()));
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

    private record TaxedCoupon(OperationId operation, Currency currency) { }

    private sealed interface Step {
        record Deposit(int currencyIndex, long minor) implements Step { }

        record Withdrawal(int currencyIndex, long minor) implements Step { }

        record Buy(int securityIndex, int quantity, long cleanMinor, long accruedMinor, long faceMinor,
                   long brokerMinor, long otherMinor) implements Step { }

        record Sell(int securityIndex, int quantity, long cleanMinor, long accruedMinor, long commissionMinor,
                    long taxMinor) implements Step { }

        record Coupon(int securityIndex, long grossMinor, long taxMinor) implements Step { }

        record Correction(int selector, long adjustmentMinor) implements Step { }
    }
}
