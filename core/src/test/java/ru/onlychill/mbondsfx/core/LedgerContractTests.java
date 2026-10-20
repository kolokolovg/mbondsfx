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
import ru.onlychill.mbondsfx.core.ledger.AccountKind;
import ru.onlychill.mbondsfx.core.ledger.BondOperations;
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

    @Test
    void openingDepositWithdrawalAndCashConstraints() {
        Ledger ledger = Ledger.open(PF);
        OperationResult opening = BondOperations.opening(id("open"), DAY1, PF, RUB, m("10000"));
        assertEntries(opening.postings().getFirst(),
                entry(PF, AccountKind.CASH, null, RUB, "10000"),
                entry(PF, AccountKind.EQUITY, null, RUB, "-10000"));
        ledger = ledger.apply(opening);
        OperationResult deposit = BondOperations.deposit(id("deposit"), DAY1, PF, RUB, m("500"));
        ledger = ledger.apply(deposit);
        OperationResult withdrawal = BondOperations.withdrawal(id("withdraw"), DAY1, PF, RUB, m("2000"));
        assertEntries(withdrawal.postings().getFirst(),
                entry(PF, AccountKind.EQUITY, null, RUB, "2000"),
                entry(PF, AccountKind.CASH, null, RUB, "-2000"));
        ledger = ledger.apply(withdrawal);
        assertEquals(m("8500"), ledger.balance(account(AccountKind.CASH, null, RUB)),
                "opening/deposit/withdrawal cash total");
        assertEquals(m("-8500"), ledger.balance(account(AccountKind.EQUITY, null, RUB)),
                "equity is the counter-account");

        assertThrows(IllegalStateException.class,
                () -> Ledger.open(PF).apply(BondOperations.withdrawal(id("no-cash"), DAY1, PF, RUB, m("1"))),
                "strict ledger rejects a withdrawal into negative cash");
        Ledger margin = Ledger.open(PF, true).apply(
                BondOperations.withdrawal(id("margin"), DAY1, PF, RUB, m("2000")));
        assertEquals(m("-2000"), margin.cashBalances().get(RUB), "explicit margin allows negative cash");
    }

    @Test
    void dirtyPurchaseCouponAmortizationAndRedemption() {
        Ledger ledger = Ledger.open(PF).apply(BondOperations.opening(id("fund"), DAY1, PF, RUB, m("20000")));
        Position empty = Position.empty(positionId(), RUB);
        OperationResult purchase = BondOperations.buy(id("buy"), DAY1.plusDays(1), empty, 10,
                m("985"), m("15.20"), m("1000"), m("50"));
        assertEntries(purchase.postings().getFirst(),
                entry(PF, AccountKind.SECURITIES, SEC, RUB, "9850"),
                entry(PF, AccountKind.ACCRUED_INTEREST, SEC, RUB, "152"),
                entry(PF, AccountKind.COMMISSION_EXPENSE, null, RUB, "50"),
                entry(PF, AccountKind.CASH, null, RUB, "-10052"));
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

        OperationResult coupon = BondOperations.coupon(id("coupon"), DAY1.plusDays(2), position,
                m("374"), Optional.of(m("48.62")));
        assertEntries(coupon.postings().getFirst(),
                entry(PF, AccountKind.CASH, null, RUB, "325.38"),
                entry(PF, AccountKind.TAX_EXPENSE, null, RUB, "48.62"),
                entry(PF, AccountKind.ACCRUED_INTEREST, SEC, RUB, "-152"),
                entry(PF, AccountKind.COUPON_INCOME, null, RUB, "-222"));
        assertEquals(m("0"), coupon.positionAfter().orElseThrow().purchasedAccrued(),
                "coupon releases the purchased accrued asset");
        ledger = ledger.apply(coupon);
        assertEquals(m("-222"), ledger.balance(account(AccountKind.COUPON_INCOME, null, RUB)),
                "only net-of-purchased-accrued coupon is income");

        Position afterCoupon = coupon.positionAfter().orElseThrow();
        OperationResult amort = BondOperations.amortization(id("amort"), DAY1.plusDays(3), afterCoupon,
                m("1000"), m("1000"), Optional.empty());
        assertEntries(amort.postings().getFirst(),
                entry(PF, AccountKind.CASH, null, RUB, "1000"),
                entry(PF, AccountKind.SECURITIES, SEC, RUB, "-985"),
                entry(PF, AccountKind.AMORTIZATION_GAIN, null, RUB, "-15"));
        assertEquals(m("8865"), amort.positionAfter().orElseThrow().carryingAmount(),
                "amortization reduces carrying amount proportionally to face");
        assertEquals(m("9000"), amort.positionAfter().orElseThrow().faceAmount(),
                "amortization reduces face amount");
        ledger = ledger.apply(amort);

        OperationResult redemption = BondOperations.sell(id("redeem"), DAY1.plusDays(4),
                amort.positionAfter().orElseThrow(), 10, m("1000"), m("0"), m("0"), Optional.empty());
        assertEntries(redemption.postings().getFirst(),
                entry(PF, AccountKind.CASH, null, RUB, "10000"),
                entry(PF, AccountKind.SECURITIES, SEC, RUB, "-8865"),
                entry(PF, AccountKind.REALIZED_PNL, null, RUB, "-1135"));
        assertEquals(Position.empty(positionId(), RUB), redemption.positionAfter().orElseThrow(),
                "full redemption yields explicit empty position");
        ledger = ledger.apply(redemption);
        assertTrue(ledger.balance(account(AccountKind.SECURITIES, SEC, RUB)).isZero()
                && ledger.balance(account(AccountKind.ACCRUED_INTEREST, SEC, RUB)).isZero(),
                "after redemption security asset accounts are zero");
        assertEquals(0, ledger.position(SEC).orElseThrow().quantity(), "ledger retains the explicit empty position");
    }

    @Test
    void exactCentAccruedSplitAcrossPartialSales() {
        Position current = Position.empty(positionId(), RUB);
        OperationResult first = BondOperations.buy(id("lot-a"), DAY1, current, 10,
                m("100"), m("15.20"), m("1000"), m("0"));
        OperationResult second = BondOperations.buy(id("lot-b"), DAY1.plusDays(1),
                first.positionAfter().orElseThrow(), 3, m("100"), m("5"), m("1000"), m("0"));
        Position combined = second.positionAfter().orElseThrow();
        assertEquals(m("167"), combined.purchasedAccrued(), "two purchases retain total purchased accrued");
        OperationResult partial = BondOperations.sell(id("lot-sell-1"), DAY1.plusDays(2), combined,
                5, m("100"), m("0"), m("0"), Optional.empty());
        Money firstRelease = entryAmount(partial.postings().getFirst(),
                account(AccountKind.ACCRUED_INTEREST, SEC, RUB)).negate();
        assertEquals(m("64.23"), firstRelease, "partial sale releases purchased accrued rounded once");
        Position residual = partial.positionAfter().orElseThrow();
        assertEquals(m("102.77"), residual.purchasedAccrued(), "partial-sale exact residual retained");
        OperationResult finalSale = BondOperations.sell(id("lot-sell-2"), DAY1.plusDays(3), residual,
                8, m("100"), m("0"), m("0"), Optional.empty());
        Money finalRelease = entryAmount(finalSale.postings().getFirst(),
                account(AccountKind.ACCRUED_INTEREST, SEC, RUB)).negate();
        assertEquals(m("102.77"), finalRelease, "final disposal releases the full exact remainder");
        assertEquals(m("167"), firstRelease.add(finalRelease), "accrued releases sum to original total exactly");
    }

    @Test
    void accruedSaleAndRealizedLoss() {
        Position empty = Position.empty(positionId(), RUB);
        Position accruedPosition = BondOperations.buy(id("f-buy"), DAY1, empty, 1,
                m("100"), m("120"), m("1000"), m("0")).positionAfter().orElseThrow();
        OperationResult betweenCouponSale = BondOperations.sell(id("f-sell"), DAY1.plusDays(1),
                accruedPosition, 1, m("100"), m("200"), m("0"), Optional.empty());
        assertEntries(betweenCouponSale.postings().getFirst(),
                entry(PF, AccountKind.CASH, null, RUB, "300"),
                entry(PF, AccountKind.SECURITIES, SEC, RUB, "-100"),
                entry(PF, AccountKind.ACCRUED_INTEREST, SEC, RUB, "-120"),
                entry(PF, AccountKind.COUPON_INCOME, null, RUB, "-80"),
                entry(PF, AccountKind.REALIZED_PNL, null, RUB, "0"));

        Position lossPosition = new Position(positionId(), 1, m("100"), m("100"), m("0"));
        OperationResult loss = BondOperations.sell(id("loss"), DAY1, lossPosition, 1,
                m("80"), m("0"), m("0"), Optional.empty());
        assertEquals(m("20"), entryAmount(loss.postings().getFirst(), account(AccountKind.REALIZED_PNL, null, RUB)),
                "sale below carrying cost debits realized PnL");
        assertFalse(loss.postings().getFirst().entries().stream().anyMatch(e ->
                e.account().kind() == AccountKind.REALIZED_PNL && e.amount().isZero()),
                "zero-valued account lines are omitted");
    }

    @Test
    void subMinorPartialAndFinalSales() {
        Ledger fractionalLedger = Ledger.open(PF).apply(
                BondOperations.opening(id("fractional-fund"), DAY1, PF, RUB, m("1")));
        Position empty = Position.empty(positionId(), RUB);
        OperationResult fractionalBuy = BondOperations.buy(id("fractional-buy"), DAY1.plusDays(1),
                empty, 10, m("0.0009"), m("0.0009"), m("0.0009"), m("0"));
        fractionalLedger = fractionalLedger.apply(fractionalBuy);
        Position original = fractionalBuy.positionAfter().orElseThrow();
        assertEquals(m("0.009"), original.carryingAmount(), "fractional-quote purchase carrying amount");
        assertEquals(m("0.009"), original.purchasedAccrued(), "fractional-quote purchase accrued amount");
        assertEquals(m("0.009"), original.faceAmount(), "fractional-quote purchase face amount");
        assertBalanced(fractionalBuy.postings().getFirst());

        OperationResult partialSale = BondOperations.sell(id("fractional-sell-9"), DAY1.plusDays(2),
                original, 9, m("0"), m("0"), m("0"), Optional.empty());
        Position afterPartial = partialSale.positionAfter().orElseThrow();
        assertEquals(1, afterPartial.quantity(), "sub-minor partial sale leaves one unit");
        assertTrue(afterPartial.carryingAmount().isZero(), "sub-minor partial sale leaves no carrying amount");
        assertTrue(afterPartial.purchasedAccrued().isZero(), "sub-minor partial sale leaves no purchased accrued");
        assertTrue(afterPartial.faceAmount().isZero(), "sub-minor partial sale leaves no face amount");
        assertEquals(m("-0.009"), entryAmount(partialSale.postings().getFirst(),
                account(AccountKind.SECURITIES, SEC, RUB)), "partial sale caps the security asset release");
        assertEquals(m("-0.009"), entryAmount(partialSale.postings().getFirst(),
                account(AccountKind.ACCRUED_INTEREST, SEC, RUB)), "partial sale caps the accrued asset release");
        assertEquals(m("0.009"), original.carryingAmount().subtract(afterPartial.carryingAmount()),
                "sub-minor partial sale accounts for original carrying total exactly");
        assertEquals(m("0.009"), original.purchasedAccrued().subtract(afterPartial.purchasedAccrued()),
                "sub-minor partial sale accounts for original accrued total exactly");
        assertEquals(m("0.009"), original.faceAmount().subtract(afterPartial.faceAmount()),
                "sub-minor partial sale accounts for original face total exactly");
        assertBalanced(partialSale.postings().getFirst());
        fractionalLedger = fractionalLedger.apply(partialSale);
        assertPositionNonnegative(fractionalLedger.position(SEC).orElseThrow());

        OperationResult finalSale = BondOperations.sell(id("fractional-sell-final"), DAY1.plusDays(3),
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
        Ledger amortLedger = Ledger.open(PF).apply(
                BondOperations.opening(id("tiny-amort-fund"), DAY1, PF, RUB, m("1")));
        Position amortEmpty = Position.empty(amortPositionId, RUB);
        OperationResult amortBuy = BondOperations.buy(id("tiny-amort-buy"), DAY1.plusDays(1),
                amortEmpty, 10, m("0.0009"), m("0"), m("0.0009"), m("0"));
        amortLedger = amortLedger.apply(amortBuy);
        OperationResult tinyAmort = BondOperations.amortization(id("tiny-amort-90"), DAY1.plusDays(2),
                amortBuy.positionAfter().orElseThrow(), m("0.0081"), m("0.0081"), Optional.empty());
        Position afterTinyAmort = tinyAmort.positionAfter().orElseThrow();
        assertEquals(10, afterTinyAmort.quantity(), "sub-minor amortization keeps quantity outstanding");
        assertEquals(m("0.0009"), afterTinyAmort.faceAmount(), "sub-minor amortization caps face reduction");
        assertTrue(afterTinyAmort.carryingAmount().isZero(), "sub-minor amortization caps carrying removal");
        assertEquals(m("-0.009"), entryAmount(tinyAmort.postings().getFirst(),
                account(AccountKind.SECURITIES, amortSecurity, RUB)),
                "sub-minor partial amortization never credits more carrying value than held");
        assertBalanced(tinyAmort.postings().getFirst());
        amortLedger = amortLedger.apply(tinyAmort);
        assertPositionNonnegative(amortLedger.position(amortSecurity).orElseThrow());

    }

    @Test
    void fullAmortizationCanBeRetiredWithoutDuplicateCashFlow() {
        SecurityId maturitySecurity = new SecurityId("SU-FULL-AMORT");
        PositionId maturityPositionId = new PositionId(PF, maturitySecurity);
        Ledger maturityLedger = Ledger.open(PF).apply(
                BondOperations.opening(id("maturity-fund"), DAY1, PF, RUB, m("1000")));
        OperationResult maturityBuy = BondOperations.buy(id("maturity-buy"), DAY1.plusDays(1),
                Position.empty(maturityPositionId, RUB), 1, m("100"), m("0"), m("100"), m("0"));
        maturityLedger = maturityLedger.apply(maturityBuy);
        OperationResult fullAmort = BondOperations.amortization(id("maturity-amort"), DAY1.plusDays(2),
                maturityBuy.positionAfter().orElseThrow(), m("100"), m("100"), Optional.empty());
        maturityLedger = maturityLedger.apply(fullAmort);
        Money cashAfterBookedMaturityPayment = maturityLedger.cashBalances().get(RUB);
        assertEquals(1, fullAmort.positionAfter().orElseThrow().quantity(),
                "full principal amortization leaves quantity outstanding");
        assertTrue(fullAmort.positionAfter().orElseThrow().faceAmount().isZero(),
                "full principal amortization leaves zero face value");
        assertTrue(fullAmort.positionAfter().orElseThrow().carryingAmount().isZero(),
                "full principal amortization leaves zero carrying value");
        OperationResult retire = BondOperations.sell(id("maturity-retire"), DAY1.plusDays(3),
                fullAmort.positionAfter().orElseThrow(), 1, m("0"), m("0"), m("0"), Optional.empty());
        assertTrue(retire.postings().getFirst().entries().isEmpty(),
                "zero-value sell after amortization creates no duplicate cash flow");
        maturityLedger = maturityLedger.apply(retire);
        assertEquals(cashAfterBookedMaturityPayment, maturityLedger.cashBalances().get(RUB),
                "zero-value sell does not duplicate amortization cash");
        assertEquals(0, maturityLedger.position(maturitySecurity).orElseThrow().quantity(),
                "zero-value sell retires the quantity");
    }

    @Test
    void ledgerOperationAndPositionValidation() {
        Ledger initial = Ledger.open(PF).apply(BondOperations.opening(id("dup"), DAY1, PF, RUB, m("10")));
        OperationResult duplicate = BondOperations.deposit(id("dup"), DAY1, PF, RUB, m("1"));
        assertThrows(IllegalStateException.class, () -> initial.apply(duplicate), "duplicate operation id rejected");

        OperationResult foreign = BondOperations.opening(id("foreign"), DAY1,
                new PortfolioId("other"), RUB, m("1"));
        assertThrows(IllegalStateException.class, () -> Ledger.open(PF).apply(foreign),
                "cross-portfolio posting rejected");

        Position empty = Position.empty(positionId(), RUB);
        OperationResult staleOne = BondOperations.buy(id("stale-one"), DAY1, empty, 1,
                m("100"), m("0"), m("100"), m("0"));
        OperationResult staleTwo = BondOperations.buy(id("stale-two"), DAY1, empty, 2,
                m("100"), m("0"), m("100"), m("0"));
        Ledger positioned = Ledger.open(PF, true).apply(staleOne);
        assertThrows(IllegalStateException.class, () -> positioned.apply(staleTwo), "stale position rejected");

        Ledger chronological = Ledger.open(PF).apply(
                BondOperations.opening(id("later"), DAY1.plusDays(2), PF, RUB, m("5")));
        assertThrows(IllegalStateException.class, () -> chronological.apply(
                BondOperations.deposit(id("backdated"), DAY1.plusDays(1), PF, RUB, m("1"))),
                "backdated operation rejected");

        Position one = new Position(positionId(), 1, m("100"), m("100"), m("10"));
        assertThrows(IllegalArgumentException.class, () -> BondOperations.sell(id("oversell"), DAY1,
                one, 2, m("100"), m("0"), m("0"), Optional.empty()), "over-sell rejected");
        assertThrows(IllegalArgumentException.class, () -> BondOperations.amortization(id("overamort"), DAY1,
                one, m("101"), m("100"), Optional.empty()), "over-amortization rejected");
        assertThrows(IllegalArgumentException.class, () -> BondOperations.amortization(id("orphan"), DAY1,
                one, m("100"), m("100"), Optional.empty()),
                "full principal reduction with outstanding purchased accrued rejected");
        assertThrows(IllegalArgumentException.class, () -> BondOperations.coupon(id("bad-tax"), DAY1,
                one, m("1"), Optional.of(m("2"))), "tax above gross coupon rejected");
        assertThrows(IllegalArgumentException.class, () -> BondOperations.buy(id("negative"), DAY1,
                empty, 1, m("-1"), m("0"), m("100"), m("0")), "negative price rejected");
        assertThrows(ArithmeticException.class, () -> BondOperations.buy(id("overflow"), DAY1,
                new Position(positionId(), Long.MAX_VALUE, m("1"), m("1"), m("0")),
                1, m("1"), m("0"), m("1"), m("0")), "quantity overflow is detected");
        assertThrows(IllegalArgumentException.class, () -> new Position(positionId(), 0,
                m("1"), m("0"), m("0")), "zero quantity cannot retain carrying amount");
        assertThrows(IllegalArgumentException.class, () -> new Position(positionId(), 1,
                m("-1"), m("0"), m("0")), "negative position amount rejected");

    }

    @Test
    void ledgerCollectionsAndOperationResultsAreImmutable() {
        Ledger initial = Ledger.open(PF).apply(BondOperations.opening(id("dup"), DAY1, PF, RUB, m("10")));
        OperationResult duplicate = BondOperations.deposit(id("dup"), DAY1, PF, RUB, m("1"));
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
        Ledger ledger = Ledger.open(PF)
                .apply(BondOperations.opening(id("rub-open"), DAY1, PF, RUB, m("100")))
                .apply(BondOperations.opening(id("usd-open"), DAY1, PF, USD,
                        new Money(new BigDecimal("20"), USD)));
        assertEquals(m("100"), ledger.cashBalances().get(RUB), "RUB cash balance isolated");
        assertEquals(new Money(new BigDecimal("20"), USD), ledger.cashBalances().get(USD),
                "USD cash balance isolated");
        assertThrows(UnbalancedPostingException.class, () -> new Posting(id("cross-offset"), DAY1, List.of(
                new Posting.Entry(account(AccountKind.CASH, null, RUB), m("1")),
                new Posting.Entry(account(AccountKind.CASH, null, USD), new Money(new BigDecimal("-1"), USD)))),
                "different currencies cannot offset each other");

        assertThrows(IllegalArgumentException.class, () -> new Account(PF, AccountKind.SECURITIES,
                Optional.empty(), RUB), "security account requires a security id");
        assertThrows(IllegalArgumentException.class, () -> new Posting(id("wrong-currency"), DAY1, List.of(
                new Posting.Entry(account(AccountKind.CASH, null, RUB), new Money(new BigDecimal("1"), USD)))),
                "entry amount currency must match its account");

        for (String code : List.of("XDR", "XTS", "XAU")) {
            assertThrows(IllegalArgumentException.class,
                    () -> account(AccountKind.CASH, null, Currency.getInstance(code)),
                    code + " pseudo-currency account rejected");
        }
        assertEquals(RUB, account(AccountKind.CASH, null, RUB).currency(), "RUB account accepted");
        Currency jpy = Currency.getInstance("JPY");
        assertEquals(0, jpy.getDefaultFractionDigits(), "JPY has zero minor-unit digits");
        assertEquals(jpy, account(AccountKind.CASH, null, jpy).currency(), "zero-decimal JPY account accepted");
    }

    @Test
    void emptyAndRichLedgerStateRoundTripAndContinuation() {
        Ledger empty = Ledger.open(PF, true);
        assertEquals(empty.state(), Ledger.restore(empty.state()).state(), "empty ledger state round-trips");
        Ledger ledger = populatedLedger();
        LedgerState state = ledger.state();
        Ledger restored = Ledger.restore(state);
        assertEquals(state, restored.state(), "rich state round-trips balances, positions, policy, history, and IDs");
        assertEquals(ledger.positions(), restored.positions(), "restored positions equal original positions");
        assertEquals(ledger.balances(), restored.balances(), "restored balances equal original balances");

        OperationResult next = BondOperations.deposit(id("state-next"), DAY1.plusDays(3), PF, RUB, m("7"));
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
        LedgerState detached = new LedgerState(state.portfolio(), state.allowNegativeCash(), inputBalances,
                inputPositions, inputHistory, inputIds);
        inputBalances.clear();
        inputPositions.clear();
        inputHistory.values().forEach(List::clear);
        inputHistory.clear();
        inputIds.clear();
        assertEquals(state, detached, "snapshot constructor copies every input collection deeply");
    }

    @Test
    void restoreRejectsStructurallyInconsistentState() {
        LedgerState state = populatedLedger().state();
        Map<Account, Money> wrongBalances = new HashMap<>(state.balances());
        Account cash = account(AccountKind.CASH, null, RUB);
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

        Ledger margin = Ledger.open(PF, true).apply(BondOperations.withdrawal(
                id("state-margin"), DAY1, PF, RUB, m("10")));
        LedgerState marginState = margin.state();
        assertThrows(IllegalArgumentException.class,
                () -> Ledger.restore(withState(marginState, false, marginState.balances(),
                        marginState.positions(), marginState.postingsByDate(), marginState.operationIds())),
                "restore enforces the persisted strict-cash policy");

        Account wrongCurrencyAccount = account(AccountKind.CASH, null, RUB);
        Map<Account, Money> wrongCurrency = new HashMap<>(state.balances());
        wrongCurrency.put(wrongCurrencyAccount, new Money(new BigDecimal("1"), USD));
        assertThrows(IllegalArgumentException.class,
                () -> Ledger.restore(withState(state, state.allowNegativeCash(), wrongCurrency,
                        state.positions(), state.postingsByDate(), state.operationIds())),
                "restore rejects account and balance currency mismatch");

        Account foreignCash = new Account(foreignPortfolio, AccountKind.CASH, Optional.empty(), RUB);
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
        Account cash = account(AccountKind.CASH, null, RUB);
        Account equity = account(AccountKind.EQUITY, null, RUB);
        Posting first = new Posting(operation, DAY1, List.of(
                new Posting.Entry(cash, m("1")), new Posting.Entry(equity, m("-1"))));
        Posting second = new Posting(operation, DAY1, List.of(
                new Posting.Entry(cash, m("2")), new Posting.Entry(equity, m("-2"))));
        NavigableMap<LocalDate, List<Posting>> history = new TreeMap<>();
        history.put(DAY1, List.of(first, second));
        LedgerState state = new LedgerState(PF, false,
                Map.of(cash, m("3"), equity, m("-3")), Map.of(), history, Set.of(operation));
        Ledger restored = Ledger.restore(state);
        assertEquals(state, restored.state(), "restore allows several balanced postings for one operation ID");

        NavigableMap<LocalDate, List<Posting>> reusedAcrossDates = new TreeMap<>();
        reusedAcrossDates.put(DAY1, List.of(first));
        reusedAcrossDates.put(DAY1.plusDays(1), List.of(new Posting(operation, DAY1.plusDays(1), List.of(
                new Posting.Entry(cash, m("2")), new Posting.Entry(equity, m("-2"))))));
        LedgerState corrupt = new LedgerState(PF, false,
                Map.of(cash, m("3"), equity, m("-3")), Map.of(), reusedAcrossDates, Set.of(operation));
        assertThrows(IllegalArgumentException.class, () -> Ledger.restore(corrupt),
                "restore rejects one operation ID split across dates");
    }

    private static Ledger populatedLedger() {
        Ledger ledger = Ledger.open(PF).apply(
                BondOperations.opening(id("state-open"), DAY1, PF, RUB, m("10000")));
        Position emptyPosition = Position.empty(positionId(), RUB);
        OperationResult purchase = BondOperations.buy(id("state-buy"), DAY1.plusDays(1), emptyPosition,
                2, m("100"), m("2"), m("1000"), m("1"));
        ledger = ledger.apply(purchase);
        OperationResult coupon = BondOperations.coupon(id("state-coupon"), DAY1.plusDays(2),
                purchase.positionAfter().orElseThrow(), m("3"), Optional.of(m("0.50")));
        return ledger.apply(coupon);
    }

    private static LedgerState withState(LedgerState source, boolean allowNegativeCash,
                                         Map<Account, Money> balances, Map<SecurityId, Position> positions,
                                         NavigableMap<LocalDate, List<Posting>> history,
                                         Set<OperationId> operationIds) {
        return new LedgerState(source.portfolio(), allowNegativeCash, balances, positions, history, operationIds);
    }

    private static Account account(AccountKind kind, SecurityId security, Currency currency) {
        return new Account(PF, kind, Optional.ofNullable(security), currency);
    }

    private static ExpectedEntry entry(PortfolioId portfolio, AccountKind kind, SecurityId security,
                                       Currency currency, String amount) {
        return new ExpectedEntry(new Account(portfolio, kind, Optional.ofNullable(security), currency),
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

    private static void assertPositionNonnegative(Position position) {
        assertTrue(position.quantity() >= 0 && !position.carryingAmount().isNegative()
                        && !position.faceAmount().isNegative() && !position.purchasedAccrued().isNegative(),
                "position quantity and asset totals remain nonnegative");
    }

    private static PositionId positionId() { return new PositionId(PF, SEC); }
    private static OperationId id(String value) { return new OperationId(value); }
    private static Money m(String value) { return new Money(new BigDecimal(value), RUB); }

    private record ExpectedEntry(Account account, Money amount) { }

}
