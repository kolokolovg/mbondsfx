package ru.onlychill.mbondsfx.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import ru.onlychill.mbondsfx.core.id.OperationId;
import ru.onlychill.mbondsfx.core.id.PortfolioId;
import ru.onlychill.mbondsfx.core.id.PositionId;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.core.ledger.Account;
import ru.onlychill.mbondsfx.core.ledger.AccountCode;
import ru.onlychill.mbondsfx.core.ledger.BondAccountingPolicy;
import ru.onlychill.mbondsfx.core.ledger.LedgerChart;
import ru.onlychill.mbondsfx.core.ledger.BondOperations;
import ru.onlychill.mbondsfx.core.ledger.Commissions;
import ru.onlychill.mbondsfx.core.ledger.Ledger;
import ru.onlychill.mbondsfx.core.ledger.LedgerState;
import ru.onlychill.mbondsfx.core.ledger.OperationResult;
import ru.onlychill.mbondsfx.core.ledger.Position;
import ru.onlychill.mbondsfx.core.ledger.Posting;
import ru.onlychill.mbondsfx.core.ledger.UnbalancedPostingException;
import ru.onlychill.mbondsfx.core.money.Money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LedgerContractTests {
    private static final Currency RUB = Currency.getInstance("RUB");
    private static final Currency USD = Currency.getInstance("USD");
    private static final PortfolioId PF = new PortfolioId("portfolio-1");
    private static final SecurityId SEC = new SecurityId("SU0000000001");
    private static final LocalDate DAY1 = LocalDate.of(2026, 1, 1);
    private static final BondAccountingPolicy POLICY = BondAccountingPolicy.standard();
    private static final LedgerChart CHART = POLICY.chart();
    private static final BondOperations OPS = POLICY.operations();

    // DE-P-03..05: exact opening/deposit/withdrawal postings and both negative-cash policies.
    @Test
    void openingDepositWithdrawalAndCashConstraints() {
        Ledger ledger = Ledger.open(PF, CHART, false);
        OperationResult opening = OPS.opening(id("open"), DAY1, PF, RUB, m("10000"));
        assertEntries(opening.postings().getFirst(),
                entry(PF, POLICY.cash(), null, RUB, "10000"),
                entry(PF, POLICY.equity(), null, RUB, "-10000"));
        ledger = ledger.apply(opening);
        OperationResult deposit = OPS.deposit(id("deposit"), DAY1, PF, RUB, m("500"));
        assertEntries(deposit.postings().getFirst(),
                entry(PF, POLICY.cash(), null, RUB, "500"),
                entry(PF, POLICY.equity(), null, RUB, "-500"));
        ledger = ledger.apply(deposit);
        OperationResult withdrawal = OPS.withdrawal(id("withdraw"), DAY1, PF, RUB, m("2000"));
        assertEntries(withdrawal.postings().getFirst(),
                entry(PF, POLICY.equity(), null, RUB, "2000"),
                entry(PF, POLICY.cash(), null, RUB, "-2000"));
        ledger = ledger.apply(withdrawal);
        assertEquals(m("8500"), ledger.balance(account(POLICY.cash(), null, RUB)),
                "opening/deposit/withdrawal cash total");
        assertEquals(m("-8500"), ledger.balance(account(POLICY.equity(), null, RUB)),
                "equity is the counter-account");

        assertThrows(IllegalStateException.class,
                () -> Ledger.open(PF, CHART, false).apply(OPS.withdrawal(id("no-cash"), DAY1, PF, RUB, m("1"))),
                "strict ledger rejects a withdrawal into negative cash");
        Ledger margin = Ledger.open(PF, CHART, true).apply(
                OPS.withdrawal(id("margin"), DAY1, PF, RUB, m("2000")));
        assertEquals(m("-2000"), margin.cashBalances().get(RUB), "explicit margin allows negative cash");
    }

    // DE-P-06..09: exact buy/coupon/amortization/sell entries, fee/tax postings, and position snapshots.
    @Test
    void dirtyPurchaseCouponAmortizationAndRedemption() {
        Ledger ledger = Ledger.open(PF, CHART, false).apply(OPS.opening(id("fund"), DAY1, PF, RUB, m("20000")));
        Position empty = Position.empty(positionId(), RUB);
        OperationResult purchase = OPS.buy(id("buy"), DAY1.plusDays(1), empty, 10,
                m("985"), m("15.20"), m("1000"), m("50"));
        assertEntries(purchase.postings().getFirst(),
                entry(PF, POLICY.securities(), SEC, RUB, "9850"),
                entry(PF, POLICY.accruedInterest(), SEC, RUB, "152"),
                entry(PF, POLICY.cash(), null, RUB, "-10002"));
        assertEntries(purchase.postings().get(1),
                entry(PF, POLICY.commissionExpense(), null, RUB, "50"),
                entry(PF, POLICY.cash(), null, RUB, "-50"));
        assertSharedPostingIdentity(purchase);
        Position position = purchase.positionAfter().orElseThrow();
        assertEquals(10, position.quantity(), "dirty purchase quantity");
        assertEquals(m("9850"), position.carryingAmount(), "dirty purchase carrying amount");
        assertEquals(m("10000"), position.faceAmount(), "dirty purchase face amount");
        assertEquals(m("152"), position.purchasedAccrued(), "dirty purchase stores purchased accrued");
        Ledger beforePurchase = ledger;
        ledger = ledger.apply(purchase);
        assertTrue(beforePurchase.position(SEC).isEmpty(), "apply leaves the old ledger position unchanged");
        assertEquals(m("20000"), beforePurchase.cashBalances().get(RUB),
                "apply leaves the old ledger cash unchanged");

        OperationResult coupon = OPS.coupon(id("coupon"), DAY1.plusDays(2), position,
                m("374"), Optional.of(m("48.62")));
        assertEntries(coupon.postings().getFirst(),
                entry(PF, POLICY.cash(), null, RUB, "374"),
                entry(PF, POLICY.accruedInterest(), SEC, RUB, "-152"),
                entry(PF, POLICY.couponIncome(), null, RUB, "-222"));
        assertEntries(coupon.postings().get(1),
                entry(PF, POLICY.taxWithheld(), null, RUB, "48.62"),
                entry(PF, POLICY.cash(), null, RUB, "-48.62"));
        assertEquals(m("0"), coupon.positionAfter().orElseThrow().purchasedAccrued(),
                "coupon releases the purchased accrued asset");
        ledger = ledger.apply(coupon);
        assertEquals(m("-222"), ledger.balance(account(POLICY.couponIncome(), null, RUB)),
                "only net-of-purchased-accrued coupon is income");
        assertEquals(m("48.62"), ledger.taxWithheldBalances().get(RUB),
                "withholding is tracked separately from coupon income");

        Position afterCoupon = coupon.positionAfter().orElseThrow();
        OperationResult amort = OPS.amortization(id("amort"), DAY1.plusDays(3), afterCoupon,
                m("1000"), m("1000"), Optional.empty());
        assertEntries(amort.postings().getFirst(),
                entry(PF, POLICY.cash(), null, RUB, "1000"),
                entry(PF, POLICY.securities(), SEC, RUB, "-985"),
                entry(PF, POLICY.amortizationGain(), null, RUB, "-15"));
        assertEquals(m("8865"), amort.positionAfter().orElseThrow().carryingAmount(),
                "amortization reduces carrying amount proportionally to face");
        assertEquals(m("9000"), amort.positionAfter().orElseThrow().faceAmount(),
                "amortization reduces face amount");
        ledger = ledger.apply(amort);

        OperationResult redemption = OPS.sell(id("redeem"), DAY1.plusDays(4),
                amort.positionAfter().orElseThrow(), 10, m("1000"), m("0"), m("0"), Optional.empty());
        assertEntries(redemption.postings().getFirst(),
                entry(PF, POLICY.cash(), null, RUB, "10000"),
                entry(PF, POLICY.securities(), SEC, RUB, "-8865"),
                entry(PF, POLICY.realizedPnl(), null, RUB, "-1135"));
        assertEquals(Position.empty(positionId(), RUB), redemption.positionAfter().orElseThrow(),
                "full redemption yields explicit empty position");
        ledger = ledger.apply(redemption);
        assertTrue(ledger.balance(account(POLICY.securities(), SEC, RUB)).isZero()
                && ledger.balance(account(POLICY.accruedInterest(), SEC, RUB)).isZero(),
                "after redemption security asset accounts are zero");
        assertEquals(0, ledger.position(SEC).orElseThrow().quantity(), "ledger retains the explicit empty position");
    }

    @Test
    void exactCentAccruedSplitAcrossPartialSales() {
        Position current = Position.empty(positionId(), RUB);
        OperationResult first = OPS.buy(id("lot-a"), DAY1, current, 10,
                m("100"), m("15.20"), m("1000"), m("0"));
        OperationResult second = OPS.buy(id("lot-b"), DAY1.plusDays(1),
                first.positionAfter().orElseThrow(), 3, m("100"), m("5"), m("1000"), m("0"));
        Position combined = second.positionAfter().orElseThrow();
        assertEquals(m("167"), combined.purchasedAccrued(), "two purchases retain total purchased accrued");
        OperationResult partial = OPS.sell(id("lot-sell-1"), DAY1.plusDays(2), combined,
                5, m("100"), m("0"), m("0"), Optional.empty());
        Money firstRelease = entryAmount(partial.postings().getFirst(),
                account(POLICY.accruedInterest(), SEC, RUB)).negate();
        assertEquals(m("64.23"), firstRelease, "partial sale releases purchased accrued rounded once");
        Position residual = partial.positionAfter().orElseThrow();
        assertEquals(m("102.77"), residual.purchasedAccrued(), "partial-sale exact residual retained");
        OperationResult finalSale = OPS.sell(id("lot-sell-2"), DAY1.plusDays(3), residual,
                8, m("100"), m("0"), m("0"), Optional.empty());
        Money finalRelease = entryAmount(finalSale.postings().getFirst(),
                account(POLICY.accruedInterest(), SEC, RUB)).negate();
        assertEquals(m("102.77"), finalRelease, "final disposal releases the full exact remainder");
        assertEquals(m("167"), firstRelease.add(finalRelease), "accrued releases sum to original total exactly");
    }

    @Test
    void accruedSaleAndRealizedLoss() {
        Position empty = Position.empty(positionId(), RUB);
        Position accruedPosition = OPS.buy(id("f-buy"), DAY1, empty, 1,
                m("100"), m("120"), m("1000"), m("0")).positionAfter().orElseThrow();
        OperationResult betweenCouponSale = OPS.sell(id("f-sell"), DAY1.plusDays(1),
                accruedPosition, 1, m("100"), m("200"), m("0"), Optional.empty());
        assertEntries(betweenCouponSale.postings().getFirst(),
                entry(PF, POLICY.cash(), null, RUB, "300"),
                entry(PF, POLICY.securities(), SEC, RUB, "-100"),
                entry(PF, POLICY.accruedInterest(), SEC, RUB, "-120"),
                entry(PF, POLICY.couponIncome(), null, RUB, "-80"),
                entry(PF, POLICY.realizedPnl(), null, RUB, "0"));

        Position lossPosition = new Position(positionId(), 1, m("100"), m("100"), m("0"));
        OperationResult loss = OPS.sell(id("loss"), DAY1, lossPosition, 1,
                m("80"), m("0"), m("0"), Optional.empty());
        assertEquals(m("20"), entryAmount(loss.postings().getFirst(), account(POLICY.realizedPnl(), null, RUB)),
                "sale below carrying cost debits realized PnL");
        assertFalse(loss.postings().getFirst().entries().stream().anyMatch(e ->
                e.account().code().equals(POLICY.realizedPnl()) && e.amount().isZero()),
                "zero-valued account lines are omitted");
    }

    @Test
    void subMinorPartialAndFinalSales() {
        Ledger fractionalLedger = Ledger.open(PF, CHART, false).apply(
                OPS.opening(id("fractional-fund"), DAY1, PF, RUB, m("1")));
        Position empty = Position.empty(positionId(), RUB);
        OperationResult fractionalBuy = OPS.buy(id("fractional-buy"), DAY1.plusDays(1),
                empty, 10, m("0.0009"), m("0.0009"), m("0.0009"), m("0"));
        fractionalLedger = fractionalLedger.apply(fractionalBuy);
        Position original = fractionalBuy.positionAfter().orElseThrow();
        assertEquals(m("0.009"), original.carryingAmount(), "fractional-quote purchase carrying amount");
        assertEquals(m("0.009"), original.purchasedAccrued(), "fractional-quote purchase accrued amount");
        assertEquals(m("0.009"), original.faceAmount(), "fractional-quote purchase face amount");
        assertBalanced(fractionalBuy.postings().getFirst());

        OperationResult partialSale = OPS.sell(id("fractional-sell-9"), DAY1.plusDays(2),
                original, 9, m("0"), m("0"), m("0"), Optional.empty());
        Position afterPartial = partialSale.positionAfter().orElseThrow();
        assertEquals(1, afterPartial.quantity(), "sub-minor partial sale leaves one unit");
        assertTrue(afterPartial.carryingAmount().isZero(), "sub-minor partial sale leaves no carrying amount");
        assertTrue(afterPartial.purchasedAccrued().isZero(), "sub-minor partial sale leaves no purchased accrued");
        assertTrue(afterPartial.faceAmount().isZero(), "sub-minor partial sale leaves no face amount");
        assertEquals(m("-0.009"), entryAmount(partialSale.postings().getFirst(),
                account(POLICY.securities(), SEC, RUB)), "partial sale caps the security asset release");
        assertEquals(m("-0.009"), entryAmount(partialSale.postings().getFirst(),
                account(POLICY.accruedInterest(), SEC, RUB)), "partial sale caps the accrued asset release");
        assertEquals(m("0.009"), original.carryingAmount().subtract(afterPartial.carryingAmount()),
                "sub-minor partial sale accounts for original carrying total exactly");
        assertEquals(m("0.009"), original.purchasedAccrued().subtract(afterPartial.purchasedAccrued()),
                "sub-minor partial sale accounts for original accrued total exactly");
        assertEquals(m("0.009"), original.faceAmount().subtract(afterPartial.faceAmount()),
                "sub-minor partial sale accounts for original face total exactly");
        assertBalanced(partialSale.postings().getFirst());
        fractionalLedger = fractionalLedger.apply(partialSale);
        assertPositionNonnegative(fractionalLedger.position(SEC).orElseThrow());

        OperationResult finalSale = OPS.sell(id("fractional-sell-final"), DAY1.plusDays(3),
                afterPartial, 1, m("0"), m("0"), m("0"), Optional.empty());
        assertTrue(finalSale.postings().getFirst().entries().isEmpty(),
                "final disposal of already released fractional assets adds no cash or asset movement");
        assertBalanced(finalSale.postings().getFirst());
        fractionalLedger = fractionalLedger.apply(finalSale);
        assertEquals(Position.empty(positionId(), RUB), fractionalLedger.position(SEC).orElseThrow(),
                "final sub-minor disposal retires the remaining quantity");

    }

    @Test
    void subMinorAmortizationDoesNotOverdrawCarryingValue() {
        SecurityId amortSecurity = new SecurityId("SU-TINY-AMORT");
        PositionId amortPositionId = new PositionId(PF, amortSecurity);
        Ledger amortLedger = Ledger.open(PF, CHART, false).apply(
                OPS.opening(id("tiny-amort-fund"), DAY1, PF, RUB, m("1")));
        Position amortEmpty = Position.empty(amortPositionId, RUB);
        OperationResult amortBuy = OPS.buy(id("tiny-amort-buy"), DAY1.plusDays(1),
                amortEmpty, 10, m("0.0009"), m("0"), m("0.0009"), m("0"));
        amortLedger = amortLedger.apply(amortBuy);
        OperationResult tinyAmort = OPS.amortization(id("tiny-amort-90"), DAY1.plusDays(2),
                amortBuy.positionAfter().orElseThrow(), m("0.0081"), m("0.0081"), Optional.empty());
        Position afterTinyAmort = tinyAmort.positionAfter().orElseThrow();
        assertEquals(10, afterTinyAmort.quantity(), "sub-minor amortization keeps quantity outstanding");
        assertEquals(m("0.0009"), afterTinyAmort.faceAmount(), "sub-minor amortization caps face reduction");
        assertTrue(afterTinyAmort.carryingAmount().isZero(), "sub-minor amortization caps carrying removal");
        assertEquals(m("-0.009"), entryAmount(tinyAmort.postings().getFirst(),
                account(POLICY.securities(), amortSecurity, RUB)),
                "sub-minor partial amortization never credits more carrying value than held");
        assertBalanced(tinyAmort.postings().getFirst());
        amortLedger = amortLedger.apply(tinyAmort);
        assertPositionNonnegative(amortLedger.position(amortSecurity).orElseThrow());

    }

    @Test
    void fullAmortizationCanBeRetiredWithoutDuplicateCashFlow() {
        SecurityId maturitySecurity = new SecurityId("SU-FULL-AMORT");
        PositionId maturityPositionId = new PositionId(PF, maturitySecurity);
        Ledger maturityLedger = Ledger.open(PF, CHART, false).apply(
                OPS.opening(id("maturity-fund"), DAY1, PF, RUB, m("1000")));
        OperationResult maturityBuy = OPS.buy(id("maturity-buy"), DAY1.plusDays(1),
                Position.empty(maturityPositionId, RUB), 1, m("100"), m("0"), m("100"), m("0"));
        maturityLedger = maturityLedger.apply(maturityBuy);
        OperationResult fullAmort = OPS.amortization(id("maturity-amort"), DAY1.plusDays(2),
                maturityBuy.positionAfter().orElseThrow(), m("100"), m("100"), Optional.empty());
        maturityLedger = maturityLedger.apply(fullAmort);
        Money cashAfterBookedMaturityPayment = maturityLedger.cashBalances().get(RUB);
        assertEquals(1, fullAmort.positionAfter().orElseThrow().quantity(),
                "full principal amortization leaves quantity outstanding");
        assertTrue(fullAmort.positionAfter().orElseThrow().faceAmount().isZero(),
                "full principal amortization leaves zero face value");
        assertTrue(fullAmort.positionAfter().orElseThrow().carryingAmount().isZero(),
                "full principal amortization leaves zero carrying value");
        OperationResult retire = OPS.sell(id("maturity-retire"), DAY1.plusDays(3),
                fullAmort.positionAfter().orElseThrow(), 1, m("0"), m("0"), m("0"), Optional.empty());
        assertTrue(retire.postings().getFirst().entries().isEmpty(),
                "zero-value sell after amortization creates no duplicate cash flow");
        maturityLedger = maturityLedger.apply(retire);
        assertEquals(cashAfterBookedMaturityPayment, maturityLedger.cashBalances().get(RUB),
                "zero-value sell does not duplicate amortization cash");
        assertEquals(0, maturityLedger.position(maturitySecurity).orElseThrow().quantity(),
                "zero-value sell retires the quantity");
    }

    // DE-N-12: checked long overflow in buy quantity remains ArithmeticException.
    @Test
    void ledgerOperationAndPositionValidation() {
        Ledger initial = Ledger.open(PF, CHART, false).apply(OPS.opening(id("dup"), DAY1, PF, RUB, m("10")));
        OperationResult duplicate = OPS.deposit(id("dup"), DAY1, PF, RUB, m("1"));
        assertThrows(IllegalStateException.class, () -> initial.apply(duplicate), "duplicate operation id rejected");

        OperationResult foreign = OPS.opening(id("foreign"), DAY1,
                new PortfolioId("other"), RUB, m("1"));
        assertThrows(IllegalStateException.class, () -> Ledger.open(PF, CHART, false).apply(foreign),
                "cross-portfolio posting rejected");

        Position empty = Position.empty(positionId(), RUB);
        OperationResult staleOne = OPS.buy(id("stale-one"), DAY1, empty, 1,
                m("100"), m("0"), m("100"), m("0"));
        OperationResult staleTwo = OPS.buy(id("stale-two"), DAY1, empty, 2,
                m("100"), m("0"), m("100"), m("0"));
        Ledger positioned = Ledger.open(PF, CHART, true).apply(staleOne);
        assertThrows(IllegalStateException.class, () -> positioned.apply(staleTwo), "stale position rejected");

        Ledger chronological = Ledger.open(PF, CHART, false).apply(
                OPS.opening(id("later"), DAY1.plusDays(2), PF, RUB, m("5")));
        assertThrows(IllegalStateException.class, () -> chronological.apply(
                OPS.deposit(id("backdated"), DAY1.plusDays(1), PF, RUB, m("1"))),
                "backdated operation rejected");

        Position one = new Position(positionId(), 1, m("100"), m("100"), m("10"));
        assertThrows(IllegalArgumentException.class, () -> OPS.sell(id("oversell"), DAY1,
                one, 2, m("100"), m("0"), m("0"), Optional.empty()), "over-sell rejected");
        assertThrows(IllegalArgumentException.class, () -> OPS.amortization(id("overamort"), DAY1,
                one, m("101"), m("100"), Optional.empty()), "over-amortization rejected");
        assertThrows(IllegalArgumentException.class, () -> OPS.amortization(id("orphan"), DAY1,
                one, m("100"), m("100"), Optional.empty()),
                "full principal reduction with outstanding purchased accrued rejected");
        assertThrows(IllegalArgumentException.class, () -> OPS.coupon(id("bad-tax"), DAY1,
                one, m("1"), Optional.of(m("2"))), "tax above gross coupon rejected");
        assertThrows(IllegalArgumentException.class, () -> OPS.buy(id("negative"), DAY1,
                empty, 1, m("-1"), m("0"), m("100"), m("0")), "negative price rejected");
        assertThrows(ArithmeticException.class, () -> OPS.buy(id("overflow"), DAY1,
                new Position(positionId(), Long.MAX_VALUE, m("1"), m("1"), m("0")),
                1, m("1"), m("0"), m("1"), m("0")), "quantity overflow is detected");
        assertThrows(IllegalArgumentException.class, () -> new Position(positionId(), 0,
                m("1"), m("0"), m("0")), "zero quantity cannot retain carrying amount");
        assertThrows(IllegalArgumentException.class, () -> new Position(positionId(), 1,
                m("-1"), m("0"), m("0")), "negative position amount rejected");

    }

    @Test
    void ledgerCollectionsAndOperationResultsAreImmutable() {
        Ledger initial = Ledger.open(PF, CHART, false).apply(OPS.opening(id("dup"), DAY1, PF, RUB, m("10")));
        OperationResult duplicate = OPS.deposit(id("dup"), DAY1, PF, RUB, m("1"));
        assertThrows(UnsupportedOperationException.class, () -> initial.balances().clear(),
                "balances map is immutable");
        assertThrows(UnsupportedOperationException.class, () -> initial.positions().clear(),
                "positions map is immutable");
        assertThrows(UnsupportedOperationException.class, () -> initial.operationIds().clear(),
                "operation id set is immutable");
        assertThrows(UnsupportedOperationException.class, () -> initial.postingsByDate().clear(),
                "posting dates map is immutable");
        assertThrows(UnsupportedOperationException.class, () -> initial.postingsByDate().get(DAY1).clear(),
                "posting date lists are immutable");
        assertThrows(UnsupportedOperationException.class, () -> duplicate.postings().clear(),
                "operation result posting list is immutable");
    }

    @Test
    void currenciesAndPostingBalance() {
        Ledger ledger = Ledger.open(PF, CHART, false)
                .apply(OPS.opening(id("rub-open"), DAY1, PF, RUB, m("100")))
                .apply(OPS.opening(id("usd-open"), DAY1, PF, USD,
                        new Money(new BigDecimal("20"), USD)));
        assertEquals(m("100"), ledger.cashBalances().get(RUB), "RUB cash balance isolated");
        assertEquals(new Money(new BigDecimal("20"), USD), ledger.cashBalances().get(USD),
                "USD cash balance isolated");
        assertThrows(UnbalancedPostingException.class, () -> new Posting(id("cross-offset"), DAY1, List.of(
                new Posting.Entry(account(POLICY.cash(), null, RUB), m("1")),
                new Posting.Entry(account(POLICY.cash(), null, USD), new Money(new BigDecimal("-1"), USD)))),
                "different currencies cannot offset each other");

        Account unvalidatedScopedAccount = new Account(PF, POLICY.securities(), Optional.empty(), RUB);
        assertTrue(unvalidatedScopedAccount.security().isEmpty(),
                "account value object does not know chart security-scoping rules");
        assertThrows(IllegalArgumentException.class, () -> new Posting(id("wrong-currency"), DAY1, List.of(
                new Posting.Entry(account(POLICY.cash(), null, RUB), new Money(new BigDecimal("1"), USD)))),
                "entry amount currency must match its account");

        for (String code : List.of("XDR", "XTS", "XAU")) {
            assertThrows(IllegalArgumentException.class,
                    () -> account(POLICY.cash(), null, Currency.getInstance(code)),
                    code + " pseudo-currency account rejected");
        }
        assertEquals(RUB, account(POLICY.cash(), null, RUB).currency(), "RUB account accepted");
        Currency jpy = Currency.getInstance("JPY");
        assertEquals(0, jpy.getDefaultFractionDigits(), "JPY has zero minor-unit digits");
        assertEquals(jpy, account(POLICY.cash(), null, jpy).currency(), "zero-decimal JPY account accepted");
    }

    @Test
    void withholdingUsesBalancedCurrencyPostingsAndSurvivesRestore() {
        SecurityId couponSecurity = new SecurityId("SU-COUPON-TAX");
        SecurityId amortSecurity = new SecurityId("SU-AMORT-TAX");
        SecurityId saleSecurity = new SecurityId("SU-SALE-TAX");
        PositionId couponPositionId = new PositionId(PF, couponSecurity);
        PositionId amortPositionId = new PositionId(PF, amortSecurity);
        PositionId salePositionId = new PositionId(PF, saleSecurity);
        Currency usd = USD;

        Ledger ledger = Ledger.open(PF, CHART, false)
                .apply(OPS.opening(id("tax-rub-open"), DAY1, PF, RUB, m("1000")))
                .apply(OPS.opening(id("tax-usd-open"), DAY1.plusDays(1), PF, usd,
                        new Money(new BigDecimal("1000"), usd)));

        OperationResult couponBuy = OPS.buy(id("tax-coupon-buy"), DAY1.plusDays(2),
                Position.empty(couponPositionId, RUB), 1, m("0"), m("0"), m("100"), m("0"));
        ledger = ledger.apply(couponBuy);
        OperationResult coupon = OPS.coupon(id("tax-coupon"), DAY1.plusDays(3),
                couponBuy.positionAfter().orElseThrow(), m("10"), Optional.of(m("10")));
        assertEquals(2, coupon.postings().size(), "coupon withholding has its own posting");
        assertEntries(coupon.postings().getFirst(),
                entry(PF, POLICY.cash(), null, RUB, "10"),
                entry(PF, POLICY.couponIncome(), null, RUB, "-10"));
        assertEntries(coupon.postings().get(1),
                entry(PF, POLICY.taxWithheld(), null, RUB, "10"),
                entry(PF, POLICY.cash(), null, RUB, "-10"));
        assertSharedPostingIdentity(coupon);
        ledger = ledger.apply(coupon);

        OperationResult amortBuy = OPS.buy(id("tax-amort-buy"), DAY1.plusDays(4),
                Position.empty(amortPositionId, usd), 1, new Money(new BigDecimal("50"), usd),
                Money.zero(usd), new Money(new BigDecimal("100"), usd), Money.zero(usd));
        ledger = ledger.apply(amortBuy);
        OperationResult amortization = OPS.amortization(id("tax-amort"), DAY1.plusDays(5),
                amortBuy.positionAfter().orElseThrow(), new Money(new BigDecimal("40"), usd),
                new Money(new BigDecimal("40"), usd), Optional.of(new Money(new BigDecimal("40"), usd)));
        assertEquals(2, amortization.postings().size(), "amortization withholding has its own posting");
        assertEntries(amortization.postings().getFirst(),
                entry(PF, POLICY.cash(), null, usd, "40"),
                entry(PF, POLICY.securities(), amortSecurity, usd, "-20"),
                entry(PF, POLICY.amortizationGain(), null, usd, "-20"));
        assertEntries(amortization.postings().get(1),
                entry(PF, POLICY.taxWithheld(), null, usd, "40"),
                entry(PF, POLICY.cash(), null, usd, "-40"));
        assertSharedPostingIdentity(amortization);
        ledger = ledger.apply(amortization);

        OperationResult saleBuy = OPS.buy(id("tax-sale-buy"), DAY1.plusDays(6),
                Position.empty(salePositionId, RUB), 1, m("80"), m("0"), m("100"),
                new Commissions(m("3"), m("2")));
        assertEquals(2, saleBuy.postings().size(), "purchase commissions have their own posting");
        assertEntries(saleBuy.postings().getFirst(),
                entry(PF, POLICY.securities(), saleSecurity, RUB, "80"),
                entry(PF, POLICY.cash(), null, RUB, "-80"));
        assertEntries(saleBuy.postings().get(1),
                entry(PF, POLICY.commissionExpense(), null, RUB, "5"),
                entry(PF, POLICY.cash(), null, RUB, "-5"));
        assertSharedPostingIdentity(saleBuy);
        ledger = ledger.apply(saleBuy);
        OperationResult sale = OPS.sell(id("tax-sale"), DAY1.plusDays(7),
                saleBuy.positionAfter().orElseThrow(), 1, m("100"), m("0"),
                new Commissions(m("2"), m("3")), Optional.of(m("100")));
        assertEquals(3, sale.postings().size(), "full redemption has separate commission and withholding postings");
        assertEntries(sale.postings().getFirst(),
                entry(PF, POLICY.cash(), null, RUB, "100"),
                entry(PF, POLICY.securities(), saleSecurity, RUB, "-80"),
                entry(PF, POLICY.realizedPnl(), null, RUB, "-20"));
        assertEntries(sale.postings().get(1),
                entry(PF, POLICY.commissionExpense(), null, RUB, "5"),
                entry(PF, POLICY.cash(), null, RUB, "-5"));
        assertEntries(sale.postings().get(2),
                entry(PF, POLICY.taxWithheld(), null, RUB, "100"),
                entry(PF, POLICY.cash(), null, RUB, "-100"));
        assertSharedPostingIdentity(sale);
        OperationResult legacyCommissionSale = OPS.sell(id("legacy-tax-sale"), DAY1.plusDays(7),
                saleBuy.positionAfter().orElseThrow(), 1, m("100"), m("0"), m("5"), Optional.empty());
        assertEquals(2, legacyCommissionSale.postings().size(),
                "legacy single-commission sale overload also creates a separate commission posting");
        assertEntries(legacyCommissionSale.postings().get(1),
                entry(PF, POLICY.commissionExpense(), null, RUB, "5"),
                entry(PF, POLICY.cash(), null, RUB, "-5"));
        assertEquals(Position.empty(salePositionId, RUB), sale.positionAfter().orElseThrow(),
                "taxable full redemption still retires the position");
        ledger = ledger.apply(sale);

        assertEquals(m("910"), ledger.cashBalances().get(RUB), "cash contains net RUB receipts only");
        assertEquals(new Money(new BigDecimal("950"), usd), ledger.cashBalances().get(usd),
                "cash contains net USD amortization receipt only");
        assertEquals(m("110"), ledger.taxWithheldBalances().get(RUB), "RUB withholding is isolated");
        assertEquals(new Money(new BigDecimal("40"), usd), ledger.taxWithheldBalances().get(usd),
                "USD withholding is isolated");
        assertEquals(m("1020"), ledger.cashBalances().get(RUB).add(ledger.taxWithheldBalances().get(RUB)),
                "RUB cash plus actual withholding retains gross receipts less commissions and purchases");
        assertEquals(m("10"), ledger.balance(account(POLICY.commissionExpense(), null, RUB)),
                "broker and other commissions total to the unchanged expense amount");
        assertEquals(new Money(new BigDecimal("990"), usd),
                ledger.cashBalances().get(usd).add(ledger.taxWithheldBalances().get(usd)),
                "USD cash plus actual withholding retains the gross amortization economics");
        Map<Currency, Money> withheldView = ledger.taxWithheldBalances();
        assertThrows(UnsupportedOperationException.class, withheldView::clear,
                "withholding view is immutable");

        Ledger restored = Ledger.restore(ledger.state());
        assertEquals(ledger.state(), restored.state(), "both postings and balances round-trip through state");
        assertEquals(ledger.taxWithheldBalances(), restored.taxWithheldBalances(),
                "restored withholding view matches the original");
        assertEquals(2, restored.postingsByDate().get(DAY1.plusDays(3)).size(),
                "restore retains the coupon's base and withholding postings");
    }

    @Test
    void absentAndZeroWithholdingDoNotCreateTaxPostings() {
        Position position = new Position(positionId(), 1, m("100"), m("100"), m("0"));
        List<OperationResult> results = List.of(
                OPS.coupon(id("no-coupon-tax"), DAY1, position, m("10"), Optional.empty()),
                OPS.coupon(id("zero-coupon-tax"), DAY1, position, m("10"), Optional.of(m("0"))),
                OPS.amortization(id("no-amort-tax"), DAY1, position,
                        m("10"), m("10"), Optional.empty()),
                OPS.amortization(id("zero-amort-tax"), DAY1, position,
                        m("10"), m("10"), Optional.of(m("0"))),
                OPS.sell(id("no-sale-tax"), DAY1, position,
                        1, m("100"), m("0"), m("0"), Optional.empty()),
                OPS.sell(id("zero-sale-tax"), DAY1, position,
                        1, m("100"), m("0"), m("0"), Optional.of(m("0"))));
        for (OperationResult result : results) {
            assertEquals(1, result.postings().size(), "zero or absent withholding keeps only the base posting");
            assertTrue(result.postings().getFirst().entries().stream()
                    .noneMatch(entry -> entry.account().code().equals(POLICY.taxWithheld())),
                    "zero or absent withholding has no withheld-tax entry");
        }
    }

    @Test
    void commissionComponentsAreValidatedAndZeroFeesAreOmitted() {
        assertThrows(IllegalArgumentException.class,
                () -> new Commissions(m("-0.01"), m("0")), "negative broker commission rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new Commissions(m("0"), m("-0.01")), "negative other commission rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new Commissions(m("1"), new Money(new BigDecimal("1"), USD)),
                "commission components must use one currency");

        Position empty = Position.empty(positionId(), RUB);
        Commissions usdFees = new Commissions(new Money(new BigDecimal("1"), USD), Money.zero(USD));
        assertThrows(IllegalArgumentException.class, () -> OPS.buy(id("wrong-buy-fee-currency"), DAY1,
                empty, 1, m("100"), m("0"), m("100"), usdFees),
                "buy commissions must match the position currency");
        Position current = new Position(positionId(), 1, m("100"), m("100"), m("0"));
        assertThrows(IllegalArgumentException.class, () -> OPS.sell(id("wrong-sale-fee-currency"), DAY1,
                current, 1, m("100"), m("0"), usdFees, Optional.empty()),
                "sale commissions must match the position currency");

        Commissions noFees = new Commissions(m("0"), m("0"));
        OperationResult freeBuy = OPS.buy(id("zero-fees-buy"), DAY1, empty, 1,
                m("100"), m("0"), m("100"), noFees);
        OperationResult freeSale = OPS.sell(id("zero-fees-sale"), DAY1, current, 1,
                m("100"), m("0"), noFees, Optional.empty());
        assertEquals(1, freeBuy.postings().size(), "zero buy fees add no commission posting");
        assertEquals(1, freeSale.postings().size(), "zero sale fees add no commission posting");
    }

    @Test
    void withheldTaxCannotFundStrictCashSpending() {
        Ledger ledger = Ledger.open(PF, CHART, false);
        OperationResult freePosition = OPS.buy(id("free-position"), DAY1,
                Position.empty(positionId(), RUB), 1, m("0"), m("0"), m("100"), m("0"));
        ledger = ledger.apply(freePosition);
        OperationResult fullWithholding = OPS.coupon(id("full-withholding"), DAY1.plusDays(1),
                freePosition.positionAfter().orElseThrow(), m("10"), Optional.of(m("10")));
        ledger = ledger.apply(fullWithholding);
        assertTrue(ledger.cashBalances().isEmpty(), "withholding leaves no spendable cash");
        assertEquals(m("10"), ledger.taxWithheldBalances().get(RUB), "withheld balance remains recorded");
        Ledger afterWithheld = ledger;
        assertThrows(IllegalStateException.class, () -> afterWithheld.apply(
                OPS.withdrawal(id("spend-withheld"), DAY1.plusDays(2), PF, RUB, m("1"))),
                "strict cash validation cannot spend a withheld-tax balance");
        assertTrue(ledger.cashBalances().isEmpty(), "failed spending does not mutate ledger cash");
    }

    // DE-P-10/11: signed correction postings, append-only history, and cumulative withholding lifecycle.
    @Test
    void taxCorrectionsAreLinkedCumulativeAndScopedToTheirOriginalOperation() {
        PositionId firstPositionId = new PositionId(PF, new SecurityId("SU-TAX-CORRECTION-1"));
        PositionId secondPositionId = new PositionId(PF, new SecurityId("SU-TAX-CORRECTION-2"));
        Ledger ledger = Ledger.open(PF, CHART, false).apply(OPS.opening(id("correction-fund"), DAY1, PF, RUB, m("100")));
        TaxedOperation first = applyTaxedCoupon(ledger, "correction-origin-1", firstPositionId,
                DAY1.plusDays(1), m("10"), m("4"));
        ledger = first.ledger();
        TaxedOperation second = applyTaxedCoupon(ledger, "correction-origin-2", secondPositionId,
                DAY1.plusDays(3), m("10"), m("7"));
        ledger = second.ledger();
        assertEquals(m("4"), ledger.taxWithheldForOperation(first.operation()), "original tax is queryable by operation");
        assertEquals(m("7"), ledger.taxWithheldForOperation(second.operation()), "second original tax is isolated");

        OperationResult redemption = OPS.sell(id("correction-origin-redemption"), DAY1.plusDays(5),
                first.position(), 1, m("0"), m("0"), m("0"), Optional.empty());
        ledger = ledger.apply(redemption);
        Map<SecurityId, Position> afterRedemption = ledger.positions();

        OperationResult increase = OPS.taxCorrection(id("correction-increase"), DAY1.plusDays(6),
                PF, first.operation(), m("2"));
        assertEquals(Optional.of(first.operation()), increase.postings().getFirst().adjustsOperation(),
                "correction posting links to its original taxed operation");
        assertEntries(increase.postings().getFirst(),
                entry(PF, POLICY.taxWithheld(), null, RUB, "2"),
                entry(PF, POLICY.cash(), null, RUB, "-2"));
        Ledger afterIncrease = ledger.apply(increase);
        assertEquals(m("6"), afterIncrease.taxWithheldForOperation(first.operation()), "positive adjustment increases tax");
        assertEquals(afterRedemption, afterIncrease.positions(), "correction does not alter sold position history");

        OperationResult decrease = OPS.taxCorrection(id("correction-decrease"), DAY1.plusDays(7),
                PF, first.operation(), m("-1"));
        assertEntries(decrease.postings().getFirst(),
                entry(PF, POLICY.taxWithheld(), null, RUB, "-1"),
                entry(PF, POLICY.cash(), null, RUB, "1"));
        Ledger afterDecrease = afterIncrease.apply(decrease);
        assertEquals(m("5"), afterDecrease.taxWithheldForOperation(first.operation()), "negative adjustment decreases tax");
        OperationResult fullRefund = OPS.taxCorrection(id("correction-full-refund"), DAY1.plusDays(8),
                PF, first.operation(), m("-5"));
        Ledger corrected = afterDecrease.apply(fullRefund);
        assertEquals(Money.zero(RUB), corrected.taxWithheldForOperation(first.operation()),
                "repeated corrections can fully return original withholding");
        assertEquals(m("7"), corrected.taxWithheldForOperation(second.operation()),
                "another operation's withholding remains untouched");
        assertEquals(m("7"), corrected.taxWithheldBalances().get(RUB),
                "portfolio tax balance is the sum of independently corrected origins");
        assertEquals(afterRedemption, corrected.positions(), "tax corrections never reopen or change positions");

        Ledger afterFullRefund = corrected;
        assertThrows(IllegalStateException.class, () -> afterFullRefund.apply(
                OPS.taxCorrection(id("over-refund"), DAY1.plusDays(9),
                        PF, first.operation(), m("-0.01"))),
                "refund cannot exceed the selected origin even when another tax balance remains");
        assertThrows(IllegalArgumentException.class,
                () -> corrected.taxWithheldForOperation(id("unknown-tax-origin")),
                "query rejects an operation without original withholding");
        assertThrows(IllegalStateException.class, () -> corrected.apply(OPS.taxCorrection(
                id("correction-unknown-origin"), DAY1.plusDays(9), PF, id("not-in-ledger"), m("1"))),
                "correction rejects a nonexistent original operation");
        assertThrows(IllegalStateException.class, () -> corrected.apply(OPS.taxCorrection(
                id("correction-untaxed-origin"), DAY1.plusDays(9), PF, id("correction-fund"), m("1"))),
                "correction requires an actual nonzero original withholding posting");
        assertThrows(IllegalStateException.class, () -> corrected.apply(OPS.taxCorrection(
                id("correction-chain"), DAY1.plusDays(9), PF, increase.operation(), m("1"))),
                "correction cannot be linked to another correction");
        assertThrows(IllegalStateException.class, () -> corrected.apply(increase),
                "correction operation IDs remain unique");
        assertThrows(IllegalStateException.class, () -> corrected.apply(OPS.taxCorrection(
                id("backdated-correction"), DAY1.plusDays(7), PF, first.operation(), m("1"))),
                "corrections cannot be backdated");
        assertThrows(IllegalArgumentException.class, () -> OPS.taxCorrection(
                id("self-correction"), DAY1.plusDays(9), PF, id("self-correction"), m("1")),
                "correction cannot reference itself");
        assertThrows(IllegalStateException.class, () -> corrected.apply(OPS.taxCorrection(
                id("wrong-currency-correction"), DAY1.plusDays(9), PF, first.operation(),
                new Money(new BigDecimal("1"), USD))),
                "correction currency must match the original withholding currency");

        Ledger restored = Ledger.restore(corrected.state());
        assertEquals(corrected.state(), restored.state(), "correction links and postings survive state restoration");
        assertEquals(Money.zero(RUB), restored.taxWithheldForOperation(first.operation()),
                "restored query includes all linked adjustments");
        OperationResult continuation = OPS.taxCorrection(id("restored-correction"), DAY1.plusDays(9),
                PF, first.operation(), m("1"));
        assertEquals(corrected.apply(continuation).state(), restored.apply(continuation).state(),
                "restored ledger continues with the same linked correction");
    }

    @Test
    void taxCorrectionRespectsStrictCashAvailability() {
        PositionId freePositionId = new PositionId(PF, new SecurityId("SU-TAX-CORRECTION-CASH"));
        Ledger ledger = Ledger.open(PF, CHART, false);
        TaxedOperation fullTax = applyTaxedCoupon(ledger, "cash-correction-origin", freePositionId,
                DAY1, m("10"), m("10"));
        ledger = fullTax.ledger();
        assertTrue(ledger.cashBalances().isEmpty(), "gross coupon fully withheld leaves no cash");
        OperationResult extraTax = OPS.taxCorrection(id("cash-correction-extra"), DAY1.plusDays(2),
                PF, fullTax.operation(), m("1"));
        Ledger beforeRejectedCorrection = ledger;
        assertThrows(IllegalStateException.class, () -> beforeRejectedCorrection.apply(extraTax),
                "positive correction cannot make strict cash negative");
        assertEquals(m("10"), ledger.taxWithheldForOperation(fullTax.operation()),
                "failed correction is atomic");

        OperationResult refund = OPS.taxCorrection(id("cash-correction-refund"), DAY1.plusDays(2),
                PF, fullTax.operation(), m("-5"));
        ledger = ledger.apply(refund);
        assertEquals(m("5"), ledger.cashBalances().get(RUB), "refund returns cash to the portfolio");
        assertEquals(m("5"), ledger.taxWithheldForOperation(fullTax.operation()), "refund reduces actual tax balance");
        Ledger afterRefund = ledger;
        assertThrows(IllegalStateException.class, () -> afterRefund.apply(refund),
                "applied refund cannot be duplicated");
    }

    @Test
    void restoreRejectsMalformedTaxCorrectionLinksAndNegativeTax() {
        PositionId positionId = new PositionId(PF, new SecurityId("SU-MALFORMED-TAX-CORRECTION"));
        TaxedOperation taxed = applyTaxedCoupon(Ledger.open(PF, CHART, true), "malformed-tax-origin", positionId,
                DAY1, m("10"), m("4"));
        LedgerState originalState = taxed.ledger().state();
        LocalDate correctionDate = DAY1.plusDays(2);

        Posting missingOrigin = taxTransferPosting(id("malformed-missing-origin"), correctionDate,
                id("absent-origin"), m("1"));
        assertThrows(IllegalArgumentException.class,
                () -> Ledger.restore(appendPosting(originalState, missingOrigin)),
                "restore rejects correction links to missing origins");

        Posting wrongCurrency = taxTransferPosting(id("malformed-wrong-currency"), correctionDate,
                taxed.operation(), new Money(new BigDecimal("1"), USD));
        assertThrows(IllegalArgumentException.class,
                () -> Ledger.restore(appendPosting(originalState, wrongCurrency)),
                "restore rejects correction currency mismatch");

        Posting excessiveRefund = taxTransferPosting(id("malformed-excessive-refund"), correctionDate,
                taxed.operation(), m("-5"));
        assertThrows(IllegalArgumentException.class,
                () -> Ledger.restore(appendPosting(originalState, excessiveRefund)),
                "restore rejects negative cumulative withholding");

        Posting firstCorrection = taxTransferPosting(id("malformed-first-correction"), correctionDate,
                taxed.operation(), m("1"));
        LedgerState firstCorrectedState = appendPosting(originalState, firstCorrection);
        Posting chainedCorrection = taxTransferPosting(id("malformed-chained-correction"),
                correctionDate.plusDays(1), firstCorrection.operation(), m("1"));
        assertThrows(IllegalArgumentException.class,
                () -> Ledger.restore(appendPosting(firstCorrectedState, chainedCorrection)),
                "restore rejects links chained through another correction");

        Posting unlinkedNegativeTax = new Posting(id("malformed-unlinked-negative-tax"), correctionDate, List.of(
                new Posting.Entry(account(POLICY.taxWithheld(), null, RUB), m("-1")),
                new Posting.Entry(account(POLICY.cash(), null, RUB), m("1"))));
        assertThrows(IllegalArgumentException.class,
                () -> Ledger.restore(appendPosting(originalState, unlinkedNegativeTax)),
                "restore rejects negative tax entries without a correction link");
    }

    // DE-P-12: chart-bearing state restores and accepts a continuation operation.
    @Test
    void emptyAndRichLedgerStateRoundTripAndContinuation() {
        Ledger empty = Ledger.open(PF, CHART, true);
        assertEquals(empty.state(), Ledger.restore(empty.state()).state(), "empty ledger state round-trips");
        Ledger ledger = populatedLedger();
        LedgerState state = ledger.state();
        Ledger restored = Ledger.restore(state);
        assertEquals(state, restored.state(), "rich state round-trips balances, positions, policy, history, and IDs");
        assertEquals(2, state.postingsByDate().get(DAY1.plusDays(2)).size(),
                "coupon base and withholding postings share an operation without confusing restore validation");
        assertEquals(ledger.positions(), restored.positions(), "restored positions equal original positions");
        assertEquals(ledger.balances(), restored.balances(), "restored balances equal original balances");
        assertEquals(m("0.50"), restored.taxWithheldBalances().get(RUB),
                "restored ledger retains actual withholding separately");

        OperationResult next = OPS.deposit(id("state-next"), DAY1.plusDays(3), PF, RUB, m("7"));
        assertEquals(restored.apply(next).state(), ledger.apply(next).state(),
                "restored ledger continues with the same apply result");

    }

    @Test
    void ledgerStateCollectionsAreImmutableAndDefensivelyCopied() {
        LedgerState state = populatedLedger().state();
        assertThrows(UnsupportedOperationException.class, () -> state.balances().clear(),
                "snapshot balances are immutable");
        assertThrows(UnsupportedOperationException.class, () -> state.positions().clear(),
                "snapshot positions are immutable");
        assertThrows(UnsupportedOperationException.class, () -> state.operationIds().clear(),
                "snapshot operation IDs are immutable");
        assertThrows(UnsupportedOperationException.class, () -> state.postingsByDate().clear(),
                "snapshot date map is immutable");
        assertThrows(UnsupportedOperationException.class,
                () -> state.postingsByDate().firstEntry().getValue().clear(),
                "snapshot posting lists are immutable");

        Map<Account, Money> inputBalances = new LinkedHashMap<>(state.balances());
        Map<SecurityId, Position> inputPositions = new LinkedHashMap<>(state.positions());
        NavigableMap<LocalDate, List<Posting>> inputHistory = new TreeMap<>();
        state.postingsByDate().forEach((date, postings) -> inputHistory.put(date, new java.util.ArrayList<>(postings)));
        Set<OperationId> inputIds = new HashSet<>(state.operationIds());
        LedgerState detached = new LedgerState(state.portfolio(), state.chart(), state.allowNegativeCash(), inputBalances,
                inputPositions, inputHistory, inputIds);
        inputBalances.clear();
        inputPositions.clear();
        inputHistory.values().forEach(List::clear);
        inputHistory.clear();
        inputIds.clear();
        assertEquals(state, detached, "snapshot constructor copies every input collection deeply");
    }

    // DE-N-06: restored untrusted state must replay exactly and honor structural invariants.
    @Test
    void restoreRejectsStructurallyInconsistentState() {
        LedgerState state = populatedLedger().state();
        Map<Account, Money> wrongBalances = new HashMap<>(state.balances());
        Account cash = account(POLICY.cash(), null, RUB);
        wrongBalances.put(cash, wrongBalances.get(cash).add(m("1")));
        assertThrows(IllegalArgumentException.class,
                () -> Ledger.restore(withState(state, state.allowNegativeCash(), wrongBalances,
                        state.positions(), state.postingsByDate(), state.operationIds())),
                "restore rejects balances that disagree with history");

        Set<OperationId> missingId = new HashSet<>(state.operationIds());
        missingId.remove(id("state-buy"));
        assertThrows(IllegalArgumentException.class,
                () -> Ledger.restore(withState(state, state.allowNegativeCash(), state.balances(),
                        state.positions(), state.postingsByDate(), missingId)),
                "restore rejects operation IDs missing from history");

        NavigableMap<LocalDate, List<Posting>> wrongDate = new TreeMap<>();
        wrongDate.put(DAY1.plusDays(20), state.postingsByDate().firstEntry().getValue());
        assertThrows(IllegalArgumentException.class,
                () -> Ledger.restore(withState(state, state.allowNegativeCash(), state.balances(),
                        state.positions(), wrongDate, state.operationIds())),
                "restore rejects a posting stored under a different date");

        Map<SecurityId, Position> wrongPositions = new HashMap<>(state.positions());
        Position current = wrongPositions.get(SEC);
        wrongPositions.put(SEC, new Position(current.id(), current.quantity(),
                current.carryingAmount().add(m("1")), current.faceAmount(), current.purchasedAccrued()));
        assertThrows(IllegalArgumentException.class,
                () -> Ledger.restore(withState(state, state.allowNegativeCash(), state.balances(),
                        wrongPositions, state.postingsByDate(), state.operationIds())),
                "restore rejects position assets inconsistent with security balances");

        Position wrongAccrued = new Position(current.id(), current.quantity(), current.carryingAmount(),
                current.faceAmount(), Money.zero(RUB));
        Map<SecurityId, Position> wrongAccruedPositions = new HashMap<>(state.positions());
        wrongAccruedPositions.put(SEC, wrongAccrued);
        assertThrows(IllegalArgumentException.class,
                () -> Ledger.restore(withState(state, state.allowNegativeCash(), state.balances(),
                        wrongAccruedPositions, state.postingsByDate(), state.operationIds())),
                "restore rejects purchased-accrued position mismatch");

        PortfolioId foreignPortfolio = new PortfolioId("foreign-state");
        Position foreignPosition = new Position(new PositionId(foreignPortfolio, SEC), current.quantity(),
                current.carryingAmount(), current.faceAmount(), current.purchasedAccrued());
        Map<SecurityId, Position> wrongPositionIdentity = new HashMap<>(state.positions());
        wrongPositionIdentity.put(SEC, foreignPosition);
        assertThrows(IllegalArgumentException.class,
                () -> Ledger.restore(withState(state, state.allowNegativeCash(), state.balances(),
                        wrongPositionIdentity, state.postingsByDate(), state.operationIds())),
                "restore rejects position from a different portfolio");

        Ledger margin = Ledger.open(PF, CHART, true).apply(OPS.withdrawal(
                id("state-margin"), DAY1, PF, RUB, m("10")));
        LedgerState marginState = margin.state();
        assertThrows(IllegalArgumentException.class,
                () -> Ledger.restore(withState(marginState, false, marginState.balances(),
                        marginState.positions(), marginState.postingsByDate(), marginState.operationIds())),
                "restore enforces the persisted strict-cash policy");

        Account wrongCurrencyAccount = account(POLICY.cash(), null, RUB);
        Map<Account, Money> wrongCurrency = new HashMap<>(state.balances());
        wrongCurrency.put(wrongCurrencyAccount, new Money(new BigDecimal("1"), USD));
        assertThrows(IllegalArgumentException.class,
                () -> Ledger.restore(withState(state, state.allowNegativeCash(), wrongCurrency,
                        state.positions(), state.postingsByDate(), state.operationIds())),
                "restore rejects account and balance currency mismatch");

        Account foreignCash = new Account(foreignPortfolio, POLICY.cash(), Optional.empty(), RUB);
        Map<Account, Money> wrongPortfolioBalances = new HashMap<>(state.balances());
        Money cashBalance = wrongPortfolioBalances.remove(cash);
        wrongPortfolioBalances.put(foreignCash, cashBalance);
        assertThrows(IllegalArgumentException.class,
                () -> Ledger.restore(withState(state, state.allowNegativeCash(), wrongPortfolioBalances,
                        state.positions(), state.postingsByDate(), state.operationIds())),
                "restore rejects account from a different portfolio");
    }

    @Test
    void restoreAllowsMultiplePostingsPerOperationOnOneDate() {
        OperationId operation = id("split-posting-operation");
        Account cash = account(POLICY.cash(), null, RUB);
        Account equity = account(POLICY.equity(), null, RUB);
        Posting first = new Posting(operation, DAY1, List.of(
                new Posting.Entry(cash, m("1")), new Posting.Entry(equity, m("-1"))));
        Posting second = new Posting(operation, DAY1, List.of(
                new Posting.Entry(cash, m("2")), new Posting.Entry(equity, m("-2"))));
        NavigableMap<LocalDate, List<Posting>> history = new TreeMap<>();
        history.put(DAY1, List.of(first, second));
        LedgerState state = new LedgerState(PF, CHART, false,
                Map.of(cash, m("3"), equity, m("-3")), Map.of(), history, Set.of(operation));
        Ledger restored = Ledger.restore(state);
        assertEquals(state, restored.state(), "restore allows several balanced postings for one operation ID");

        NavigableMap<LocalDate, List<Posting>> reusedAcrossDates = new TreeMap<>();
        reusedAcrossDates.put(DAY1, List.of(first));
        reusedAcrossDates.put(DAY1.plusDays(1), List.of(new Posting(operation, DAY1.plusDays(1), List.of(
                new Posting.Entry(cash, m("2")), new Posting.Entry(equity, m("-2"))))));
        LedgerState corrupt = new LedgerState(PF, CHART, false,
                Map.of(cash, m("3"), equity, m("-3")), Map.of(), reusedAcrossDates, Set.of(operation));
        assertThrows(IllegalArgumentException.class, () -> Ledger.restore(corrupt),
                "restore rejects one operation ID split across dates");
    }

    private static Ledger populatedLedger() {
        Ledger ledger = Ledger.open(PF, CHART, false).apply(
                OPS.opening(id("state-open"), DAY1, PF, RUB, m("10000")));
        Position emptyPosition = Position.empty(positionId(), RUB);
        OperationResult purchase = OPS.buy(id("state-buy"), DAY1.plusDays(1), emptyPosition,
                2, m("100"), m("2"), m("1000"), m("1"));
        ledger = ledger.apply(purchase);
        OperationResult coupon = OPS.coupon(id("state-coupon"), DAY1.plusDays(2),
                purchase.positionAfter().orElseThrow(), m("3"), Optional.of(m("0.50")));
        return ledger.apply(coupon);
    }

    private static TaxedOperation applyTaxedCoupon(Ledger ledger, String operationName, PositionId positionId,
                                                   LocalDate buyDate, Money gross, Money tax) {
        Currency currency = gross.currency();
        OperationResult purchase = OPS.buy(id(operationName + "-buy"), buyDate,
                Position.empty(positionId, currency), 1, Money.zero(currency), Money.zero(currency),
                new Money(new BigDecimal("100"), currency), Money.zero(currency));
        ledger = ledger.apply(purchase);
        OperationId couponId = id(operationName);
        OperationResult coupon = OPS.coupon(couponId, buyDate.plusDays(1),
                purchase.positionAfter().orElseThrow(), gross, Optional.of(tax));
        ledger = ledger.apply(coupon);
        return new TaxedOperation(ledger, couponId, coupon.positionAfter().orElseThrow());
    }

    private static Posting taxTransferPosting(OperationId operation, LocalDate date,
                                              OperationId originalOperation, Money adjustment) {
        return new Posting(operation, date, List.of(
                new Posting.Entry(account(POLICY.taxWithheld(), null, adjustment.currency()), adjustment),
                new Posting.Entry(account(POLICY.cash(), null, adjustment.currency()), adjustment.negate())),
                Optional.of(originalOperation));
    }

    private static LedgerState appendPosting(LedgerState source, Posting posting) {
        Map<Account, Money> balances = new LinkedHashMap<>(source.balances());
        for (Posting.Entry entry : posting.entries()) {
            Account account = entry.account();
            Money updated = balances.getOrDefault(account, Money.zero(account.currency())).add(entry.amount());
            if (updated.isZero()) {
                balances.remove(account);
            } else {
                balances.put(account, updated);
            }
        }
        NavigableMap<LocalDate, List<Posting>> history = new TreeMap<>(source.postingsByDate());
        List<Posting> datedPostings = new java.util.ArrayList<>(history.getOrDefault(posting.date(), List.of()));
        datedPostings.add(posting);
        history.put(posting.date(), datedPostings);
        Set<OperationId> operationIds = new HashSet<>(source.operationIds());
        operationIds.add(posting.operation());
        return new LedgerState(source.portfolio(), source.chart(), source.allowNegativeCash(), balances, source.positions(),
                history, operationIds);
    }

    private static LedgerState withState(LedgerState source, boolean allowNegativeCash,
                                         Map<Account, Money> balances, Map<SecurityId, Position> positions,
                                         NavigableMap<LocalDate, List<Posting>> history,
                                         Set<OperationId> operationIds) {
        return new LedgerState(source.portfolio(), source.chart(), allowNegativeCash, balances, positions, history, operationIds);
    }

    private static Account account(AccountCode code, SecurityId security, Currency currency) {
        return new Account(PF, code, Optional.ofNullable(security), currency);
    }

    private static ExpectedEntry entry(PortfolioId portfolio, AccountCode code, SecurityId security,
                                       Currency currency, String amount) {
        return new ExpectedEntry(new Account(portfolio, code, Optional.ofNullable(security), currency),
                new Money(new BigDecimal(amount), currency));
    }

    private static void assertEntries(Posting posting, ExpectedEntry... expected) {
        Map<Account, Money> actual = new HashMap<>();
        for (Posting.Entry entry : posting.entries()) {
            actual.merge(entry.account(), entry.amount(), Money::add);
        }
        Map<Account, Money> expectedMap = new HashMap<>();
        for (ExpectedEntry entry : expected) {
            if (!entry.amount().isZero()) {
                expectedMap.put(entry.account(), entry.amount());
            }
        }
        assertEquals(expectedMap, actual, "posting entries expected " + expectedMap + " but got " + actual);
        assertBalanced(posting);
    }

    private static Money entryAmount(Posting posting, Account account) {
        return posting.entries().stream().filter(e -> e.account().equals(account))
                .map(Posting.Entry::amount).reduce(Money.zero(account.currency()), Money::add);
    }

    private static void assertBalanced(Posting posting) {
        Map<Currency, Money> totals = new HashMap<>();
        for (Posting.Entry entry : posting.entries()) {
            totals.merge(entry.amount().currency(), entry.amount(), Money::add);
        }
        assertTrue(totals.values().stream().allMatch(Money::isZero), "posting balances by currency");
    }

    private static void assertSharedPostingIdentity(OperationResult result) {
        assertTrue(result.postings().stream().allMatch(posting -> posting.operation().equals(result.operation())
                && posting.date().equals(result.date())), "split postings share operation identity and date");
        result.postings().forEach(LedgerContractTests::assertBalanced);
    }

    private static void assertPositionNonnegative(Position position) {
        assertTrue(position.quantity() >= 0 && !position.carryingAmount().isNegative()
                        && !position.faceAmount().isNegative() && !position.purchasedAccrued().isNegative(),
                "position quantity and asset totals remain nonnegative");
    }

    private static PositionId positionId() { return new PositionId(PF, SEC); }
    private static OperationId id(String value) { return new OperationId(value); }
    private static Money m(String value) { return new Money(new BigDecimal(value), RUB); }

    private record ExpectedEntry(Account account, Money amount) { }
    private record TaxedOperation(Ledger ledger, OperationId operation, Position position) { }

}
