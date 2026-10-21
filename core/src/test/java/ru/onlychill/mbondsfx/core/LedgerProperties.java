package ru.onlychill.mbondsfx.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Provide;
import net.jqwik.api.Property;
import ru.onlychill.mbondsfx.core.id.OperationId;
import ru.onlychill.mbondsfx.core.id.PortfolioId;
import ru.onlychill.mbondsfx.core.id.PositionId;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.core.ledger.Account;
import ru.onlychill.mbondsfx.core.ledger.AccountKind;
import ru.onlychill.mbondsfx.core.ledger.BondOperations;
import ru.onlychill.mbondsfx.core.ledger.Commissions;
import ru.onlychill.mbondsfx.core.ledger.Ledger;
import ru.onlychill.mbondsfx.core.ledger.LedgerState;
import ru.onlychill.mbondsfx.core.ledger.OperationResult;
import ru.onlychill.mbondsfx.core.ledger.Position;
import ru.onlychill.mbondsfx.core.ledger.Posting;
import ru.onlychill.mbondsfx.core.money.Money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LedgerProperties {
    private static final Currency RUB = Currency.getInstance("RUB");
    private static final Currency USD = Currency.getInstance("USD");
    private static final LocalDate DAY = LocalDate.of(2026, 1, 1);

    @Property(tries = 250)
    void multiCurrencyPostingsBalanceAndBuyChargesEachCommissionComponentOnce(
            @ForAll("caseNumbers") int caseNumber,
            @ForAll("quantities") int quantity,
            @ForAll("minorUnits") int cleanMinor,
            @ForAll("minorUnits") int accruedMinor,
            @ForAll("positiveMinorUnits") int faceMinor,
            @ForAll("minorUnits") int brokerMinor,
            @ForAll("minorUnits") int otherMinor) {
        PortfolioId portfolio = portfolio(caseNumber);
        Ledger ledger = Ledger.open(portfolio);
        List<OperationResult> operations = new ArrayList<>();

        for (Currency currency : List.of(RUB, USD)) {
            Money clean = money(currency, cleanMinor);
            Money accrued = money(currency, accruedMinor);
            Money broker = money(currency, brokerMinor);
            Money other = money(currency, otherMinor);
            Money fees = broker.add(other);
            Money expectedSpend = clean.multiply(quantity).add(accrued.multiply(quantity)).add(fees);
            Money opening = expectedSpend.add(money(currency, 10_000));
            String currencyCode = currency.getCurrencyCode().toLowerCase();
            OperationResult funding = BondOperations.opening(operationId(caseNumber, "open-" + currencyCode),
                    DAY, portfolio, currency, opening);
            operations.add(funding);
            ledger = ledger.apply(funding);
        }

        for (Currency currency : List.of(RUB, USD)) {
            Money clean = money(currency, cleanMinor);
            Money accrued = money(currency, accruedMinor);
            Money broker = money(currency, brokerMinor);
            Money other = money(currency, otherMinor);
            Money fees = broker.add(other);
            Money opening = clean.multiply(quantity).add(accrued.multiply(quantity)).add(fees)
                    .add(money(currency, 10_000));
            String currencyCode = currency.getCurrencyCode().toLowerCase();
            SecurityId security = new SecurityId("LP-" + caseNumber + "-" + currencyCode);
            Position empty = Position.empty(new PositionId(portfolio, security), currency);
            OperationResult purchase = BondOperations.buy(operationId(caseNumber, "buy-" + currencyCode),
                    DAY.plusDays(1), empty, quantity, clean, accrued, money(currency, faceMinor),
                    new Commissions(broker, other));
            operations.add(purchase);
            ledger = ledger.apply(purchase);

            Money expectedCash = opening.subtract(clean.multiply(quantity))
                    .subtract(accrued.multiply(quantity)).subtract(broker).subtract(other);
            assertEquals(expectedCash, ledger.cashBalances().get(currency), "cash after " + currencyCode + " buy");
            assertEquals(fees, ledger.balance(account(portfolio, AccountKind.COMMISSION_EXPENSE, null, currency)),
                    "total commissions are expensed once in " + currencyCode);
        }

        assertEquals(money(RUB, 10_000), ledger.cashBalances().get(RUB));
        assertEquals(money(USD, 10_000), ledger.cashBalances().get(USD));
        operations.forEach(LedgerProperties::assertEveryPostingBalancesByCurrency);
    }

    @Property(tries = 250)
    void partialSubMinorSaleCapsRoundedReleaseAndFinalSaleRemovesExactRemainder(
            @ForAll("caseNumbers") int caseNumber,
            @ForAll("saleCases") SaleCase saleCase) {
        PortfolioId portfolio = portfolio(caseNumber);
        SecurityId security = new SecurityId("LP-SALE-" + caseNumber);
        PositionId positionId = new PositionId(portfolio, security);
        Ledger ledger = Ledger.open(portfolio).apply(BondOperations.opening(
                operationId(caseNumber, "sale-fund"), DAY, portfolio, RUB, money(RUB, 1_000)));

        Money cleanPerUnit = subMinor(saleCase.unitMicros());
        Money accruedPerUnit = cleanPerUnit;
        Money facePerUnit = cleanPerUnit;
        OperationResult purchase = BondOperations.buy(operationId(caseNumber, "sale-buy"), DAY.plusDays(1),
                Position.empty(positionId, RUB), saleCase.quantity(), cleanPerUnit, accruedPerUnit,
                facePerUnit, money(RUB, 0));
        ledger = ledger.apply(purchase);
        Position original = purchase.positionAfter().orElseThrow();

        OperationResult partial = BondOperations.sell(operationId(caseNumber, "sale-partial"), DAY.plusDays(2),
                original, saleCase.partialQuantity(), money(RUB, 0), money(RUB, 0), money(RUB, 0), Optional.empty());
        Position residual = partial.positionAfter().orElseThrow();
        Money carryingRemoved = expectedPartialRemoval(original.carryingAmount(),
                saleCase.partialQuantity(), saleCase.quantity());
        Money accruedRemoved = expectedPartialRemoval(original.purchasedAccrued(),
                saleCase.partialQuantity(), saleCase.quantity());
        Money faceRemoved = expectedPartialRemoval(original.faceAmount(),
                saleCase.partialQuantity(), saleCase.quantity());

        assertEquals(original.carryingAmount().subtract(carryingRemoved), residual.carryingAmount());
        assertEquals(original.purchasedAccrued().subtract(accruedRemoved), residual.purchasedAccrued());
        assertEquals(original.faceAmount().subtract(faceRemoved), residual.faceAmount());
        assertTrue(!residual.carryingAmount().isNegative());
        assertTrue(!residual.purchasedAccrued().isNegative());
        assertTrue(!residual.faceAmount().isNegative());
        assertEquals(carryingRemoved.negate(), entryAmount(partial.postings().getFirst(),
                account(portfolio, AccountKind.SECURITIES, security, RUB)));
        assertEquals(accruedRemoved.negate(), entryAmount(partial.postings().getFirst(),
                account(portfolio, AccountKind.ACCRUED_INTEREST, security, RUB)));
        ledger = ledger.apply(partial);

        OperationResult finalSale = BondOperations.sell(operationId(caseNumber, "sale-final"), DAY.plusDays(3),
                residual, residual.quantity(), money(RUB, 0), money(RUB, 0), money(RUB, 0), Optional.empty());
        assertEquals(residual.carryingAmount().negate(), entryAmount(finalSale.postings().getFirst(),
                account(portfolio, AccountKind.SECURITIES, security, RUB)));
        assertEquals(residual.purchasedAccrued().negate(), entryAmount(finalSale.postings().getFirst(),
                account(portfolio, AccountKind.ACCRUED_INTEREST, security, RUB)));
        assertEquals(Position.empty(positionId, RUB), finalSale.positionAfter().orElseThrow());
        ledger = ledger.apply(finalSale);

        assertEquals(money(RUB, 0), ledger.balance(account(portfolio, AccountKind.SECURITIES, security, RUB)));
        assertEquals(money(RUB, 0), ledger.balance(account(portfolio, AccountKind.ACCRUED_INTEREST, security, RUB)));
    }

    @Property(tries = 250)
    void withholdingIsNotSpendableAndCorrectionOnlyMovesCashAndWithheldBalance(
            @ForAll("caseNumbers") int caseNumber,
            @ForAll("taxCases") TaxCase taxCase) {
        PortfolioId portfolio = portfolio(caseNumber);
        SecurityId security = new SecurityId("LP-TAX-" + caseNumber);
        PositionId positionId = new PositionId(portfolio, security);
        Ledger ledger = Ledger.open(portfolio);
        OperationResult purchase = BondOperations.buy(operationId(caseNumber, "tax-buy"), DAY,
                Position.empty(positionId, RUB), 1, money(RUB, 0), money(RUB, 0),
                new Money(new BigDecimal("100"), RUB), money(RUB, 0));
        ledger = ledger.apply(purchase);
        OperationId couponId = operationId(caseNumber, "tax-coupon");
        OperationResult coupon = BondOperations.coupon(couponId, DAY.plusDays(1),
                purchase.positionAfter().orElseThrow(), money(RUB, taxCase.grossMinor()),
                Optional.of(money(RUB, taxCase.grossMinor())));
        ledger = ledger.apply(coupon);

        assertTrue(ledger.cashBalances().isEmpty(), "fully withheld proceeds are not spendable cash");
        assertEquals(money(RUB, taxCase.grossMinor()), ledger.taxWithheldBalances().get(RUB));
        Ledger beforeCorrection = ledger;
        LedgerState originalState = ledger.state();
        assertThrows(IllegalStateException.class, () -> beforeCorrection.apply(BondOperations.withdrawal(
                operationId(caseNumber, "tax-cannot-spend"), DAY.plusDays(2), portfolio, RUB, money(RUB, 1))));

        long refundMinor = taxCase.refundMinor();
        OperationResult correction = BondOperations.taxCorrection(operationId(caseNumber, "tax-correction"),
                DAY.plusDays(2), portfolio, couponId, money(RUB, refundMinor).negate());
        Ledger corrected = beforeCorrection.apply(correction);
        Posting correctionPosting = correction.postings().getFirst();
        assertEquals(Optional.of(couponId), correctionPosting.adjustsOperation());
        assertEquals(money(RUB, refundMinor), entryAmount(correctionPosting,
                account(portfolio, AccountKind.CASH, null, RUB)));
        assertEquals(money(RUB, -refundMinor), entryAmount(correctionPosting,
                account(portfolio, AccountKind.TAX_WITHHELD, null, RUB)));

        assertEquals(money(RUB, refundMinor), corrected.cashBalances().getOrDefault(RUB, money(RUB, 0)));
        assertEquals(money(RUB, taxCase.grossMinor() - refundMinor),
                corrected.taxWithheldBalances().getOrDefault(RUB, money(RUB, 0)));
        assertEquals(money(RUB, taxCase.grossMinor() - refundMinor), corrected.taxWithheldForOperation(couponId));
        assertEquals(money(RUB, -taxCase.grossMinor()),
                corrected.balance(account(portfolio, AccountKind.COUPON_INCOME, null, RUB)));
        assertEquals(beforeCorrection.positions(), corrected.positions(), "correction leaves positions unchanged");
        assertEquals(originalState.postingsByDate().get(DAY.plusDays(1)),
                corrected.postingsByDate().get(DAY.plusDays(1)), "original coupon history is append-only");
        assertEquals(originalState, beforeCorrection.state(), "applying a correction does not mutate its source ledger");
    }

    @Property(tries = 250)
    void validDepositSequencesRestoreAndPreserveEveryPriorLedgerState(
            @ForAll("caseNumbers") int caseNumber,
            @ForAll("depositSequences") List<Integer> deposits) {
        PortfolioId portfolio = portfolio(caseNumber);
        Ledger ledger = Ledger.open(portfolio).apply(BondOperations.opening(
                operationId(caseNumber, "sequence-open"), DAY, portfolio, RUB, money(RUB, 100_000)));

        for (int index = 0; index < deposits.size(); index++) {
            Ledger previous = ledger;
            LedgerState previousState = previous.state();
            ledger = previous.apply(BondOperations.deposit(operationId(caseNumber, "deposit-" + index),
                    DAY.plusDays(index + 1L), portfolio, RUB, money(RUB, deposits.get(index))));
            assertEquals(previousState, previous.state(), "applying an operation leaves the old ledger unchanged");
        }

        LedgerState saved = ledger.state();
        Ledger restored = Ledger.restore(saved);
        assertEquals(saved, restored.state(), "state and complete posting history round-trip");
        OperationResult continuation = BondOperations.deposit(operationId(caseNumber, "continuation"),
                DAY.plusDays(deposits.size() + 1L), portfolio, RUB, money(RUB, 7));
        assertEquals(ledger.apply(continuation).state(), restored.apply(continuation).state(),
                "restored state accepts the same next valid operation");
    }

    @Provide
    Arbitrary<Integer> caseNumbers() {
        return Arbitraries.integers().between(1, 1_000_000);
    }

    @Provide
    Arbitrary<Integer> quantities() {
        return Arbitraries.integers().between(1, 20);
    }

    @Provide
    Arbitrary<Integer> minorUnits() {
        return Arbitraries.integers().between(0, 10_000);
    }

    @Provide
    Arbitrary<Integer> positiveMinorUnits() {
        return Arbitraries.integers().between(1, 10_000);
    }

    @Provide
    Arbitrary<SaleCase> saleCases() {
        return Arbitraries.integers().between(2, 10).flatMap(quantity ->
                Arbitraries.integers().between(1, quantity - 1).flatMap(partialQuantity ->
                        Arbitraries.integers().between(1, 9).map(unitMicros ->
                                new SaleCase(quantity, partialQuantity, unitMicros))));
    }

    @Provide
    Arbitrary<TaxCase> taxCases() {
        return Arbitraries.integers().between(1, 10_000).flatMap(grossMinor ->
                Arbitraries.integers().between(1, grossMinor).map(refundMinor ->
                        new TaxCase(grossMinor, refundMinor)));
    }

    @Provide
    Arbitrary<List<Integer>> depositSequences() {
        return Arbitraries.integers().between(1, 10_000).list().ofMinSize(1).ofMaxSize(8);
    }

    private static Money expectedPartialRemoval(Money amount, int removedQuantity, int totalQuantity) {
        BigDecimal rounded = amount.amount().multiply(BigDecimal.valueOf(removedQuantity))
                .divide(BigDecimal.valueOf(totalQuantity), amount.currency().getDefaultFractionDigits(),
                        RoundingMode.HALF_UP);
        return new Money(rounded.min(amount.amount()), amount.currency());
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

    private static Money entryAmount(Posting posting, Account account) {
        return posting.entries().stream().filter(entry -> entry.account().equals(account))
                .map(Posting.Entry::amount).reduce(Money.zero(account.currency()), Money::add);
    }

    private static Account account(PortfolioId portfolio, AccountKind kind, SecurityId security, Currency currency) {
        return new Account(portfolio, kind, Optional.ofNullable(security), currency);
    }

    private static Money money(Currency currency, long minorUnits) {
        return new Money(BigDecimal.valueOf(minorUnits, currency.getDefaultFractionDigits()), currency);
    }

    private static Money subMinor(long micros) {
        return new Money(BigDecimal.valueOf(micros).movePointLeft(4), RUB);
    }

    private static PortfolioId portfolio(int caseNumber) {
        return new PortfolioId("ledger-properties-" + caseNumber);
    }

    private static OperationId operationId(int caseNumber, String label) {
        return new OperationId("ledger-properties-" + caseNumber + "-" + label);
    }

    private record SaleCase(int quantity, int partialQuantity, int unitMicros) { }

    private record TaxCase(int grossMinor, int refundMinor) { }
}
