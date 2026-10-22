package ru.onlychill.mbondsfx.core.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import ru.onlychill.mbondsfx.core.id.OperationId;
import ru.onlychill.mbondsfx.core.id.PortfolioId;
import ru.onlychill.mbondsfx.core.id.PositionId;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.core.money.Money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LedgerChartContractTests {
    private static final Currency RUB = Currency.getInstance("RUB");
    private static final LocalDate DAY = LocalDate.of(2026, 1, 1);
    private static final PortfolioId PORTFOLIO = new PortfolioId("chart-contract");
    private static final SecurityId SECURITY_1 = new SecurityId("CHART-SEC-1");
    private static final SecurityId SECURITY_2 = new SecurityId("CHART-SEC-2");
    private static final AccountCode CASH = AccountCode.of("CASH");
    private static final AccountCode EQUITY = AccountCode.of("EQUITY");
    private static final AccountCode SECURITIES = AccountCode.of("SECURITIES");
    private static final AccountCode ACCRUED = AccountCode.of("ACCRUED");
    private static final AccountCode TAX = AccountCode.of("TAX");
    private static final AccountCode PASSIVE = AccountCode.of("PASSIVE");
    private static final AccountCode SPENDABLE_2 = AccountCode.of("SPENDABLE_2");
    private static final BondAccountingPolicy STANDARD = BondAccountingPolicy.standard();
    private static final BondOperations OPERATIONS = STANDARD.operations();

    @Test
    void deN02AccountCodeValidatesAsciiFormatAndValueIdentity() {
        assertEquals("A", AccountCode.of("A").value());
        assertEquals(32, AccountCode.of("A" + "B".repeat(31)).value().length());
        assertEquals(AccountCode.of("CASH"), new AccountCode("CASH"));
        assertEquals(AccountCode.of("CASH").hashCode(), new AccountCode("CASH").hashCode());
        for (String invalid : List.of("", "cash", "1CASH", "_CASH", "CASH-1", "CASH A",
                "A" + "B".repeat(32), "Å")) {
            assertThrows(IllegalArgumentException.class, () -> AccountCode.of(invalid), invalid);
        }
        assertThrows(NullPointerException.class, () -> AccountCode.of(null));
    }

    @Test
    void deP01StandardAndCustomChartsAreImmutableValueObjectsWithDeterministicOrder() {
        BondAccountingPolicy custom = policy("PORTFOLIO_CASH", "PORTFOLIO_EQUITY", "BOND_ASSET",
                "PURCHASED_ACCRUED", "COUPON_REVENUE", "AMORTIZATION_RESULT", "REALIZED_RESULT",
                "TAX_RESERVE", "BROKER_COST");
        LedgerChart first = custom.chart();
        LedgerChart second = custom.chart();
        assertEquals(first, second, "policy chart derivation is value-deterministic");
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(9, first.knownCodes().size());
        assertEquals(Set.of(custom.cash()), first.spendableCodes());
        assertEquals(custom.taxWithheld(), first.adjustableCode());

        Set<AccountCode> reverseKnown = new LinkedHashSet<>(new ArrayList<>(first.knownCodes()).reversed());
        Set<AccountCode> reverseSpendable = new LinkedHashSet<>(new ArrayList<>(first.spendableCodes()));
        Set<AccountCode> reverseScoped = new LinkedHashSet<>(new ArrayList<>(first.securityScopedCodes()).reversed());
        Map<AccountCode, PositionMeasure> reverseBindings = new LinkedHashMap<>();
        first.positionBindings().entrySet().stream().toList().reversed()
                .forEach(entry -> reverseBindings.put(entry.getKey(), entry.getValue()));
        LedgerChart reordered = new LedgerChart(reverseKnown, reverseSpendable, first.adjustableCode(),
                first.adjustmentCounterpartCode(), first.noNegativeCodes(), reverseScoped, reverseBindings);
        assertEquals(first, reordered, "set/map insertion order does not affect chart equality");
        assertEquals(first.knownCodes().stream().map(AccountCode::value).sorted().toList(),
                first.knownCodes().stream().map(AccountCode::value).toList());
        assertEquals(first.positionBindings().keySet().stream().map(AccountCode::value).sorted().toList(),
                first.positionBindings().keySet().stream().map(AccountCode::value).toList());
        assertThrows(UnsupportedOperationException.class, () -> first.knownCodes().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.positionBindings().clear());
        assertNotEquals(first, STANDARD.chart(), "policy code changes produce a different chart value");

        Ledger empty = Ledger.open(PORTFOLIO, STANDARD.chart(), false);
        assertEquals(STANDARD.chart(), empty.state().chart());
        assertEquals(empty.state(), Ledger.restore(empty.state()).state(), "DE-P-02 chart survives round trip");

        BondOperations customOperations = custom.operations();
        Ledger customLedger = Ledger.open(PORTFOLIO, custom.chart(), false).apply(
                customOperations.opening(id("custom-policy-opening"), DAY, PORTFOLIO, RUB, money("10")));
        assertEquals(money("10"), customLedger.balance(flat(custom.cash())),
                "bond operations emit policy codes instead of standard dictionary literals");
        Ledger customAfterDeposit = customLedger.apply(customOperations.deposit(id("custom-policy-deposit"),
                DAY.plusDays(1), PORTFOLIO, RUB, money("5")));
        assertEquals(custom.chart(), customLedger.chart(), "R6.7: opening keeps the custom chart");
        assertEquals(custom.chart(), customAfterDeposit.chart(), "R6.7: applied operations keep the custom chart");
        assertEquals(custom.chart(), customAfterDeposit.state().chart(), "R6.7: state carries the frozen chart");
        assertEquals(custom.chart(), Ledger.restore(customAfterDeposit.state()).chart(),
                "R6.7: restore keeps the frozen custom chart without external configuration");
    }

    @Test
    void deN01ChartRejectsEveryIncompatibleRoleCombination() {
        assertInvalid(() -> chart(Set.of(), Set.of(CASH), TAX, CASH, Set.of(CASH),
                Set.of(SECURITIES, ACCRUED), bindings()));
        assertInvalid(() -> chart(known(), Set.of(SPENDABLE_2), TAX, CASH, Set.of(CASH),
                Set.of(SECURITIES, ACCRUED), bindings()));
        assertInvalid(() -> chart(known(), Set.of(), TAX, CASH, Set.of(), Set.of(SECURITIES, ACCRUED), bindings()));
        assertInvalid(() -> chart(known(), Set.of(CASH, SPENDABLE_2), TAX, CASH, Set.of(CASH),
                Set.of(SECURITIES, ACCRUED), bindings()));
        assertInvalid(() -> chart(known(), Set.of(CASH), TAX, CASH, Set.of(CASH, SPENDABLE_2),
                Set.of(SECURITIES, ACCRUED), bindings()));
        assertInvalid(() -> chart(known(), Set.of(CASH), CASH, CASH, Set.of(CASH),
                Set.of(SECURITIES, ACCRUED), bindings()));
        assertInvalid(() -> chart(known(), Set.of(CASH), TAX, CASH, Set.of(CASH),
                Set.of(SECURITIES, ACCRUED, TAX), bindings()));
        assertInvalid(() -> chart(known(), Set.of(CASH), SECURITIES, CASH, Set.of(CASH),
                Set.of(SECURITIES, ACCRUED), bindings()));
        assertInvalid(() -> chart(known(), Set.of(CASH), TAX, EQUITY, Set.of(CASH),
                Set.of(SECURITIES, ACCRUED), bindings()));
        assertInvalid(() -> chart(known(), Set.of(CASH), TAX, SECURITIES, Set.of(CASH),
                Set.of(SECURITIES, ACCRUED), bindings()));
        assertInvalid(() -> chart(known(), Set.of(CASH), CASH, CASH, Set.of(CASH),
                Set.of(SECURITIES, ACCRUED), bindings()));
        assertInvalid(() -> chart(known(), Set.of(CASH, SECURITIES), TAX, CASH, Set.of(CASH),
                Set.of(SECURITIES, ACCRUED), bindings()));
        assertInvalid(() -> chart(known(), Set.of(CASH), TAX, CASH, Set.of(EQUITY),
                Set.of(SECURITIES, ACCRUED), bindings()));
        assertInvalid(() -> chart(known(), Set.of(CASH), TAX, CASH, Set.of(CASH),
                Set.of(SECURITIES, ACCRUED), Map.of(SECURITIES, PositionMeasure.CARRYING,
                        ACCRUED, PositionMeasure.PURCHASED_ACCRUED, PASSIVE, PositionMeasure.CARRYING)));
        assertInvalid(() -> chart(known(), Set.of(CASH), TAX, CASH, Set.of(CASH),
                Set.of(SECURITIES, ACCRUED), Map.of(SECURITIES, PositionMeasure.CARRYING)));
        assertInvalid(() -> chart(known(), Set.of(CASH), TAX, CASH, Set.of(CASH),
                Set.of(SECURITIES, ACCRUED), Map.of(SECURITIES, PositionMeasure.CARRYING,
                        ACCRUED, PositionMeasure.CARRYING)));

        assertInvalid(() -> chart(known(), Set.of(CASH), CASH, CASH, Set.of(CASH),
                Set.of(SECURITIES, ACCRUED), bindings()));
        assertInvalid(() -> chart(known(), Set.of(CASH), TAX, PASSIVE, Set.of(CASH),
                Set.of(SECURITIES, ACCRUED), bindings()));
        assertInvalid(() -> chart(known(), Set.of(CASH), TAX, CASH, Set.of(CASH),
                Set.of(SECURITIES, ACCRUED), Map.of(SECURITIES, PositionMeasure.CARRYING,
                        PASSIVE, PositionMeasure.PURCHASED_ACCRUED)));
        AccountCode alien = AccountCode.of("ALIEN");
        assertInvalid(() -> chart(known(), Set.of(alien), TAX, CASH, Set.of(),
                Set.of(SECURITIES, ACCRUED), bindings()));
        assertInvalid(() -> chart(known(), Set.of(CASH), TAX, CASH, Set.of(alien),
                Set.of(SECURITIES, ACCRUED), bindings()));
        assertInvalid(() -> chart(known(), Set.of(CASH), TAX, CASH, Set.of(),
                Set.of(SECURITIES, ACCRUED, alien), Map.of(SECURITIES, PositionMeasure.CARRYING,
                        ACCRUED, PositionMeasure.PURCHASED_ACCRUED, alien, PositionMeasure.CARRYING)));
        assertInvalid(() -> chart(known(), Set.of(CASH), alien, CASH, Set.of(),
                Set.of(SECURITIES, ACCRUED), bindings()));
        assertInvalid(() -> chart(known(), Set.of(CASH), TAX, alien, Set.of(),
                Set.of(SECURITIES, ACCRUED), bindings()));
        assertInvalid(() -> chart(known(), Set.of(CASH), TAX, CASH, Set.of(),
                Set.of(SECURITIES, ACCRUED), Map.of(SECURITIES, PositionMeasure.CARRYING,
                        ACCRUED, PositionMeasure.PURCHASED_ACCRUED, alien, PositionMeasure.CARRYING)));
    }

    @Test
    void deN14AndDeN15ChartAndPolicyKeepExceptionTaxonomy() {
        LedgerChart chart = standardChart();
        assertThrows(IllegalArgumentException.class, () -> chart.positionBinding(CASH));
        assertThrows(NullPointerException.class, () -> new LedgerChart(null, Set.of(CASH), TAX, CASH,
                Set.of(CASH), Set.of(SECURITIES, ACCRUED), bindings()));
        assertThrows(NullPointerException.class, () -> new LedgerChart(known(), null, TAX, CASH,
                Set.of(CASH), Set.of(SECURITIES, ACCRUED), bindings()));
        assertThrows(NullPointerException.class, () -> new LedgerChart(known(), Set.of(CASH), null, CASH,
                Set.of(CASH), Set.of(SECURITIES, ACCRUED), bindings()));
        assertThrows(NullPointerException.class, () -> new LedgerChart(known(), Set.of(CASH), TAX, null,
                Set.of(CASH), Set.of(SECURITIES, ACCRUED), bindings()));
        assertThrows(NullPointerException.class, () -> new LedgerChart(known(), Set.of(CASH), TAX, CASH,
                null, Set.of(SECURITIES, ACCRUED), bindings()));
        assertThrows(NullPointerException.class, () -> new LedgerChart(known(), Set.of(CASH), TAX, CASH,
                Set.of(CASH), null, bindings()));
        assertThrows(NullPointerException.class, () -> new LedgerChart(known(), Set.of(CASH), TAX, CASH,
                Set.of(CASH), Set.of(SECURITIES, ACCRUED), null));

        AccountCode[] policyCodes = Arrays.stream(new String[]{"CASH_P", "EQUITY_P", "SEC_P",
                "ACCRUED_P", "COUPON_P", "AMORT_P", "PNL_P", "TAX_P", "FEE_P"})
                .map(AccountCode::of).toArray(AccountCode[]::new);
        for (int nullSlot = 0; nullSlot < policyCodes.length; nullSlot++) {
            int slot = nullSlot;
            assertThrows(NullPointerException.class, () -> policyWithNullSlot(policyCodes, slot),
                    "BondAccountingPolicy component " + slot + " is required");
        }
        assertThrows(IllegalArgumentException.class,
                () -> new BondAccountingPolicy(CASH, CASH, SECURITIES, ACCRUED, PASSIVE,
                        SPENDABLE_2, AccountCode.of("PNL"), TAX, AccountCode.of("FEE")));
        assertThrows(NullPointerException.class, () -> new BondOperations(null));
        assertThrows(NullPointerException.class,
                () -> STANDARD.operations().opening(null, DAY, PORTFOLIO, RUB, money("1")));
        assertThrows(NullPointerException.class,
                () -> STANDARD.operations().taxCorrection(new OperationId("null-arg"), DAY, PORTFOLIO,
                        new OperationId("origin"), null));
        assertAllBondOperationsObjectArgumentsRejectNull();
    }

    @Test
    void deP15PassiveCodesStoreBalancesWithoutSpecialEngineBehavior() {
        LedgerChart chart = new LedgerChart(known(), Set.of(CASH), TAX, CASH, Set.of(CASH),
                Set.of(SECURITIES, ACCRUED), bindings());
        Account passive = flat(PASSIVE);
        Account equity = flat(EQUITY);
        OperationResult result = operation(OperationResult.OperationKind.DEPOSIT, "passive", List.of(
                new Posting.Entry(passive, money("5")), new Posting.Entry(equity, money("-5"))));
        Ledger ledger = Ledger.open(PORTFOLIO, chart, false).apply(result);
        assertEquals(money("5"), ledger.balance(passive));
        assertEquals(money("-5"), ledger.balance(equity));
        assertTrue(ledger.cashBalances().isEmpty(), "passive balance is not spendable");

        LedgerChart passiveAdjustable = chart(known(), Set.of(CASH), PASSIVE, CASH, Set.of(CASH),
                Set.of(SECURITIES, ACCRUED), bindings());
        assertEquals(PASSIVE, passiveAdjustable.adjustableCode(),
                "a known passive code is valid as the chart's configurable adjustable role");
    }

    // DE-P-17: unknown/scoping reads fail while a valid known missing account remains zero.
    @Test
    void deN03DeN11AndDeN13ApplyRestoreAndBalanceEnforceChartScoping() {
        LedgerChart chart = STANDARD.chart();
        Ledger empty = Ledger.open(PORTFOLIO, chart, true);
        Account unknown = flat(AccountCode.of("UNKNOWN"));
        Account cash = flat(STANDARD.cash());
        assertThrows(IllegalStateException.class, () -> empty.apply(operation(OperationResult.OperationKind.DEPOSIT,
                "unknown-entry", List.of(new Posting.Entry(unknown, money("1")),
                        new Posting.Entry(flat(STANDARD.equity()), money("-1"))))));
        assertThrows(IllegalArgumentException.class, () -> empty.balance(unknown));

        Account flatWithSecurity = new Account(PORTFOLIO, STANDARD.cash(), Optional.of(SECURITY_1), RUB);
        assertThrows(IllegalStateException.class, () -> empty.apply(operation(OperationResult.OperationKind.DEPOSIT,
                "flat-with-security", List.of(new Posting.Entry(flatWithSecurity, money("1")),
                        new Posting.Entry(flat(STANDARD.equity()), money("-1"))))));
        assertThrows(IllegalArgumentException.class, () -> empty.balance(flatWithSecurity));

        Account scopedWithoutSecurity = flat(STANDARD.securities());
        assertThrows(IllegalStateException.class, () -> empty.apply(operation(OperationResult.OperationKind.DEPOSIT,
                "scoped-without-security", List.of(new Posting.Entry(scopedWithoutSecurity, money("1")),
                        new Posting.Entry(flat(STANDARD.equity()), money("-1"))))));
        assertThrows(IllegalArgumentException.class, () -> empty.balance(scopedWithoutSecurity));

        Account malformed = flat(STANDARD.securities());
        OperationResult malformedPosting = operation(OperationResult.OperationKind.DEPOSIT,
                "restore-malformed-scoped", List.of(new Posting.Entry(malformed, money("1")),
                        new Posting.Entry(flat(STANDARD.equity()), money("-1"))));
        LedgerState malformedState = stateWith(chart, malformedPosting, Map.of(malformed, money("1"),
                flat(STANDARD.equity()), money("-1")));
        assertThrows(IllegalArgumentException.class, () -> Ledger.restore(malformedState));
        assertThrows(IllegalArgumentException.class,
                () -> Ledger.restore(stateWith(chart, malformedPosting, Map.of())),
                "malformed scoped account in history is rejected before dereferencing security");

        OperationResult unknownPosting = operation(OperationResult.OperationKind.DEPOSIT, "restore-unknown",
                List.of(new Posting.Entry(unknown, money("1")),
                        new Posting.Entry(flat(STANDARD.equity()), money("-1"))));
        assertThrows(IllegalArgumentException.class,
                () -> Ledger.restore(stateWith(chart, unknownPosting,
                        Map.of(unknown, money("1"), flat(STANDARD.equity()), money("-1")))));
        assertThrows(IllegalArgumentException.class,
                () -> Ledger.restore(stateWith(chart, unknownPosting, Map.of())),
                "unknown code in history is rejected");

        Ledger zeroView = Ledger.open(PORTFOLIO, chart, false);
        assertEquals(Money.zero(RUB), zeroView.balance(cash), "known and correctly scoped absent account is zero");
        assertThrows(IllegalArgumentException.class, () -> zeroView.balance(flat(AccountCode.of("UNKNOWN"))));
    }

    @Test
    void deN04AndDeP16NoNegativeRoleAndTwoPositionMeasuresAreChartDriven() {
        LedgerChart noNegative = new LedgerChart(STANDARD.chart().knownCodes(), Set.of(STANDARD.cash()),
                STANDARD.taxWithheld(), STANDARD.cash(), Set.of(), STANDARD.chart().securityScopedCodes(),
                STANDARD.chart().positionBindings());
        OperationResult negativeCash = operation(OperationResult.OperationKind.DEPOSIT, "no-negative-empty",
                List.of(new Posting.Entry(flat(STANDARD.cash()), money("-5")),
                        new Posting.Entry(flat(STANDARD.equity()), money("5"))));
        Ledger relaxedChart = Ledger.open(PORTFOLIO, noNegative, false).apply(negativeCash);
        assertEquals(money("-5"), relaxedChart.cashBalances().get(RUB),
                "empty noNegative role allows a negative spendable balance");

        Ledger strict = Ledger.open(PORTFOLIO, STANDARD.chart(), false);
        OperationResult strictNegative = operation(OperationResult.OperationKind.DEPOSIT, "strict-negative",
                List.of(new Posting.Entry(flat(STANDARD.cash()), money("-5")),
                        new Posting.Entry(flat(STANDARD.equity()), money("5"))));
        assertThrows(IllegalStateException.class, () -> strict.apply(strictNegative),
                "single noNegative account rejects negative balance under strict policy");
        Ledger margin = Ledger.open(PORTFOLIO, STANDARD.chart(), true)
                .apply(operation(OperationResult.OperationKind.DEPOSIT, "margin-negative",
                        List.of(new Posting.Entry(flat(STANDARD.cash()), money("-5")),
                                new Posting.Entry(flat(STANDARD.equity()), money("5")))));
        assertEquals(money("-5"), margin.cashBalances().get(RUB),
                "allowNegativeCash disables the noNegative role");

        Ledger ledger = Ledger.open(PORTFOLIO, STANDARD.chart(), false).apply(
                OPERATIONS.opening(id("position-fund"), DAY, PORTFOLIO, RUB, money("1000")));
        Position first = Position.empty(new PositionId(PORTFOLIO, SECURITY_1), RUB);
        OperationResult buyFirst = OPERATIONS.buy(id("position-buy-1"), DAY.plusDays(1), first,
                2, money("10"), money("2"), money("100"), money("0"));
        ledger = ledger.apply(buyFirst);
        Position second = Position.empty(new PositionId(PORTFOLIO, SECURITY_2), RUB);
        OperationResult buySecond = OPERATIONS.buy(id("position-buy-2"), DAY.plusDays(2), second,
                3, money("20"), money("1"), money("100"), money("0"));
        ledger = ledger.apply(buySecond);
        assertEquals(money("20"), ledger.balance(security(STANDARD.securities(), SECURITY_1)));
        assertEquals(money("4"), ledger.balance(security(STANDARD.accruedInterest(), SECURITY_1)));
        assertEquals(money("60"), ledger.balance(security(STANDARD.securities(), SECURITY_2)));
        assertEquals(money("3"), ledger.balance(security(STANDARD.accruedInterest(), SECURITY_2)));
        assertEquals(2, ledger.positions().size(), "both binding measures are validated for two positions");
    }

    @Test
    void deP12RestoresAfterEveryOperationIncludingCorrection() {
        Ledger ledger = Ledger.open(PORTFOLIO, STANDARD.chart(), true);
        List<OperationResult> sequence = new ArrayList<>();
        sequence.add(OPERATIONS.opening(id("replay-open"), DAY, PORTFOLIO, RUB, money("1000")));
        sequence.add(OPERATIONS.deposit(id("replay-deposit"), DAY.plusDays(1), PORTFOLIO, RUB, money("5")));
        sequence.add(OPERATIONS.withdrawal(id("replay-withdrawal"), DAY.plusDays(2), PORTFOLIO, RUB, money("2")));
        Position current = Position.empty(new PositionId(PORTFOLIO, new SecurityId("REPLAY-SEC")), RUB);
        OperationResult buy = OPERATIONS.buy(id("replay-buy"), DAY.plusDays(3), current,
                2, money("10"), money("1"), money("100"), money("1"));
        sequence.add(buy);
        Position afterBuy = buy.positionAfter().orElseThrow();
        OperationResult coupon = OPERATIONS.coupon(id("replay-coupon"), DAY.plusDays(4), afterBuy,
                money("4"), Optional.of(money("1")));
        sequence.add(coupon);
        OperationResult amortization = OPERATIONS.amortization(id("replay-amortization"), DAY.plusDays(5),
                coupon.positionAfter().orElseThrow(), money("10"), money("10"), Optional.empty());
        sequence.add(amortization);
        OperationResult sale = OPERATIONS.sell(id("replay-sale"), DAY.plusDays(6),
                amortization.positionAfter().orElseThrow(), 1, money("10"), money("0"), money("0"), Optional.empty());
        sequence.add(sale);
        sequence.add(OPERATIONS.taxCorrection(id("replay-correction"), DAY.plusDays(7), PORTFOLIO,
                coupon.operation(), money("1")));

        for (OperationResult operation : sequence) {
            ledger = ledger.apply(operation);
            assertEquals(ledger.state(), Ledger.restore(ledger.state()).state(),
                    "each operation's state independently replays: " + operation.kind());
        }
    }

    @Test
    void deN05AdjustmentUsesPolicyRolesAndRejectsMalformedTransfers() {
        Ledger originLedger = Ledger.open(PORTFOLIO, STANDARD.chart(), true);
        Position position = Position.empty(new PositionId(PORTFOLIO, new SecurityId("ADJUST-SEC")), RUB);
        OperationResult buy = OPERATIONS.buy(id("adjust-buy"), DAY, position,
                1, money("0"), money("0"), money("100"), money("0"));
        originLedger = originLedger.apply(buy);
        OperationResult coupon = OPERATIONS.coupon(id("adjust-origin"), DAY.plusDays(1),
                buy.positionAfter().orElseThrow(), money("10"), Optional.of(money("2")));
        originLedger = originLedger.apply(coupon);

        OperationResult wrongPair = new OperationResult(OperationResult.OperationKind.TAX_CORRECTION,
                id("wrong-adjust-pair"), DAY.plusDays(2), List.of(new Posting(id("wrong-adjust-pair"),
                DAY.plusDays(2), List.of(new Posting.Entry(flat(STANDARD.taxWithheld()), money("1")),
                        new Posting.Entry(flat(STANDARD.equity()), money("-1"))),
                Optional.of(coupon.operation()))), Optional.empty(), Optional.empty());
        Ledger beforeBadCorrection = originLedger;
        assertThrows(IllegalStateException.class, () -> beforeBadCorrection.apply(wrongPair));
        assertThrows(IllegalArgumentException.class,
                () -> beforeBadCorrection.taxWithheldForOperation(id("absent-tax")));
        OperationId ambiguousId = id("ambiguous-tax-origin");
        Posting firstTax = new Posting(ambiguousId, DAY.plusDays(3), List.of(
                new Posting.Entry(flat(STANDARD.taxWithheld()), money("1")),
                new Posting.Entry(flat(STANDARD.cash()), money("-1"))));
        Posting secondTax = new Posting(ambiguousId, DAY.plusDays(3), List.of(
                new Posting.Entry(flat(STANDARD.taxWithheld()), money("2")),
                new Posting.Entry(flat(STANDARD.cash()), money("-2"))));
        OperationResult ambiguousOrigin = new OperationResult(OperationResult.OperationKind.DEPOSIT,
                ambiguousId, DAY.plusDays(3), List.of(firstTax, secondTax), Optional.empty(), Optional.empty());
        Ledger ambiguousLedger = beforeBadCorrection.apply(ambiguousOrigin);
        assertThrows(IllegalArgumentException.class,
                () -> ambiguousLedger.taxWithheldForOperation(ambiguousId),
                "direct query rejects more than one original withholding posting");
        OperationResult correction = OPERATIONS.taxCorrection(id("valid-adjustment"), DAY.plusDays(2), PORTFOLIO,
                coupon.operation(), money("-1"));
        Ledger adjusted = originLedger.apply(correction);
        assertEquals(money("1"), adjusted.taxWithheldForOperation(coupon.operation()));
        assertThrows(IllegalArgumentException.class,
                () -> adjusted.taxWithheldForOperation(correction.operation()));
    }

    @Test
    void deN16RestoreUsesPostingOrderInsideSameDateBucket() {
        OperationId origin = id("same-date-origin");
        OperationId correctionId = id("same-date-correction");
        Posting withholding = new Posting(origin, DAY, List.of(
                new Posting.Entry(flat(STANDARD.taxWithheld()), money("4")),
                new Posting.Entry(flat(STANDARD.cash()), money("-4"))));
        Posting correction = new Posting(correctionId, DAY, List.of(
                new Posting.Entry(flat(STANDARD.taxWithheld()), money("-1")),
                new Posting.Entry(flat(STANDARD.cash()), money("1"))), Optional.of(origin));
        Map<Account, Money> balances = Map.of(flat(STANDARD.taxWithheld()), money("3"),
                flat(STANDARD.cash()), money("-3"));
        LedgerState chronological = orderedState(List.of(withholding, correction), balances, origin, correctionId);
        assertEquals(money("3"), Ledger.restore(chronological).taxWithheldForOperation(origin));
        LedgerState reversed = orderedState(List.of(correction, withholding), balances, origin, correctionId);
        assertThrows(IllegalArgumentException.class, () -> Ledger.restore(reversed));
    }

    @Test
    void chartEqualityAndHashCodeFollowEveryRoleAndRejectForeignTypes() {
        LedgerChart standard = standardChart();
        assertEquals(standard, STANDARD.chart(), "equal standard charts compare equal");
        assertEquals(standard.hashCode(), STANDARD.chart().hashCode(), "equal charts share a hash code");
        assertNotEquals(standard, null, "chart is not equal to null");
        assertNotEquals(standard, "standard", "chart is not equal to a different type");

        LedgerChart withNoNegative = chart(known(), Set.of(CASH), TAX, CASH, Set.of(CASH),
                Set.of(SECURITIES, ACCRUED), bindings());
        LedgerChart withoutNoNegative = chart(known(), Set.of(CASH), TAX, CASH, Set.of(),
                Set.of(SECURITIES, ACCRUED), bindings());
        assertNotEquals(withNoNegative, withoutNoNegative, "no-negative role participates in equality");
        // Hash-quality check for this pair only: a constant hash would still satisfy the equals/hashCode contract.
        assertNotEquals(withNoNegative.hashCode(), withoutNoNegative.hashCode(),
                "hash code reflects the no-negative role");
    }

    @Test
    void positionBindingReturnsMeasureForScopedCodesAndRejectsOthers() {
        LedgerChart chart = chart(known(), Set.of(CASH), TAX, CASH, Set.of(CASH),
                Set.of(SECURITIES, ACCRUED), bindings());
        assertEquals(PositionMeasure.CARRYING, chart.positionBinding(SECURITIES));
        assertEquals(PositionMeasure.PURCHASED_ACCRUED, chart.positionBinding(ACCRUED));
        IllegalArgumentException unscoped = assertThrows(IllegalArgumentException.class,
                () -> chart.positionBinding(CASH));
        assertEquals("Account code is not security-scoped: CASH", unscoped.getMessage());
    }

    @Test
    void standardPolicyExposesTheDocumentedBondDictionary() {
        BondAccountingPolicy policy = BondAccountingPolicy.standard();
        assertEquals(AccountCode.of("CASH"), policy.cash());
        assertEquals(AccountCode.of("EQUITY"), policy.equity());
        assertEquals(AccountCode.of("SECURITIES"), policy.securities());
        assertEquals(AccountCode.of("ACCRUED_INTEREST"), policy.accruedInterest());
        assertEquals(AccountCode.of("COUPON_INCOME"), policy.couponIncome());
        assertEquals(AccountCode.of("AMORTIZATION_GAIN"), policy.amortizationGain());
        assertEquals(AccountCode.of("REALIZED_PNL"), policy.realizedPnl());
        assertEquals(AccountCode.of("TAX_WITHHELD"), policy.taxWithheld());
        assertEquals(AccountCode.of("COMMISSION_EXPENSE"), policy.commissionExpense());
        assertEquals(policy, BondAccountingPolicy.standard(), "standard policy is a value");
    }

    private static LedgerState orderedState(List<Posting> postings, Map<Account, Money> balances,
                                            OperationId origin, OperationId correction) {
        TreeMap<LocalDate, List<Posting>> history = new TreeMap<>();
        history.put(DAY, postings);
        return new LedgerState(PORTFOLIO, STANDARD.chart(), true, balances, Map.of(), history,
                Set.of(origin, correction));
    }

    private static void assertAllBondOperationsObjectArgumentsRejectNull() {
        Position current = new Position(new PositionId(PORTFOLIO, SECURITY_1), 1,
                money("10"), money("10"), money("0"));
        Commissions noFees = new Commissions(money("0"), money("0"));
        List<CashOperationFactory> cashOperations = List.of(
                OPERATIONS::opening, OPERATIONS::deposit, OPERATIONS::withdrawal);
        for (CashOperationFactory operation : cashOperations) {
            assertThrows(NullPointerException.class,
                    () -> operation.create(null, DAY, PORTFOLIO, RUB, money("1")));
            assertThrows(NullPointerException.class,
                    () -> operation.create(id("null-date"), null, PORTFOLIO, RUB, money("1")));
            assertThrows(NullPointerException.class,
                    () -> operation.create(id("null-portfolio"), DAY, null, RUB, money("1")));
            assertThrows(NullPointerException.class,
                    () -> operation.create(id("null-currency"), DAY, PORTFOLIO, null, money("1")));
            assertThrows(NullPointerException.class,
                    () -> operation.create(id("null-amount"), DAY, PORTFOLIO, RUB, null));
        }

        CorrectionFactory correction = OPERATIONS::taxCorrection;
        assertThrows(NullPointerException.class,
                () -> correction.create(null, DAY, PORTFOLIO, id("original"), money("1")));
        assertThrows(NullPointerException.class,
                () -> correction.create(id("null-correction-date"), null, PORTFOLIO, id("original"), money("1")));
        assertThrows(NullPointerException.class,
                () -> correction.create(id("null-correction-portfolio"), DAY, null, id("original"), money("1")));
        assertThrows(NullPointerException.class,
                () -> correction.create(id("null-original"), DAY, PORTFOLIO, null, money("1")));
        assertThrows(NullPointerException.class,
                () -> correction.create(id("null-adjustment"), DAY, PORTFOLIO, id("original"), null));

        assertThrows(NullPointerException.class,
                () -> OPERATIONS.buy(null, DAY, current, 1, money("1"), money("0"), money("1"), money("0")));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.buy(id("null-buy-date"), null, current, 1,
                        money("1"), money("0"), money("1"), money("0")));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.buy(id("null-buy-position"), DAY, null, 1,
                        money("1"), money("0"), money("1"), money("0")));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.buy(id("null-buy-clean"), DAY, current, 1,
                        null, money("0"), money("1"), money("0")));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.buy(id("null-buy-accrued"), DAY, current, 1,
                        money("1"), null, money("1"), money("0")));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.buy(id("null-buy-face"), DAY, current, 1,
                        money("1"), money("0"), null, money("0")));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.buy(id("null-buy-commission"), DAY, current, 1,
                        money("1"), money("0"), money("1"), (Money) null));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.buy(id("null-buy-commissions"), DAY, current, 1,
                        money("1"), money("0"), money("1"), (Commissions) null));

        assertThrows(NullPointerException.class,
                () -> OPERATIONS.coupon(null, DAY, current, money("1"), Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.coupon(id("null-coupon-date"), null, current, money("1"), Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.coupon(id("null-coupon-position"), DAY, null, money("1"), Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.coupon(id("null-coupon-gross"), DAY, current, null, Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.coupon(id("null-coupon-tax"), DAY, current, money("1"), null));

        assertThrows(NullPointerException.class,
                () -> OPERATIONS.amortization(null, DAY, current, money("1"), money("1"), Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.amortization(id("null-amort-date"), null, current,
                        money("1"), money("1"), Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.amortization(id("null-amort-position"), DAY, null,
                        money("1"), money("1"), Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.amortization(id("null-face-reduction"), DAY, current,
                        null, money("1"), Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.amortization(id("null-amort-payment"), DAY, current,
                        money("1"), null, Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.amortization(id("null-amort-tax"), DAY, current,
                        money("1"), money("1"), null));

        assertThrows(NullPointerException.class,
                () -> OPERATIONS.sell(null, DAY, current, 1, money("1"), money("0"), money("0"), Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.sell(id("null-sell-date"), null, current, 1,
                        money("1"), money("0"), money("0"), Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.sell(id("null-sell-position"), DAY, null, 1,
                        money("1"), money("0"), money("0"), Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.sell(id("null-sell-clean"), DAY, current, 1,
                        null, money("0"), money("0"), Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.sell(id("null-sell-accrued"), DAY, current, 1,
                        money("1"), null, money("0"), Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.sell(id("null-sell-commission"), DAY, current, 1,
                        money("1"), money("0"), (Money) null, Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.sell(id("null-sell-tax"), DAY, current, 1,
                        money("1"), money("0"), money("0"), null));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.sell(id("null-sell-commissions"), DAY, current, 1,
                        money("1"), money("0"), (Commissions) null, Optional.empty()));

        // The Commissions overload is the canonical instance path; guard its shared object inputs too.
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.buy(null, DAY, current, 1, money("1"), money("0"), money("1"), noFees));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.buy(id("null-buy-settle-commissions"), null, current, 1,
                        money("1"), money("0"), money("1"), noFees));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.buy(id("null-buy-current-commissions"), DAY, null, 1,
                        money("1"), money("0"), money("1"), noFees));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.buy(id("null-buy-clean-commissions"), DAY, current, 1,
                        null, money("0"), money("1"), noFees));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.buy(id("null-buy-accrued-commissions"), DAY, current, 1,
                        money("1"), null, money("1"), noFees));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.buy(id("null-buy-face-commissions"), DAY, current, 1,
                        money("1"), money("0"), null, noFees));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.sell(null, DAY, current, 1, money("1"), money("0"), noFees, Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.sell(id("null-sell-settle-commissions"), null, current, 1,
                        money("1"), money("0"), noFees, Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.sell(id("null-sell-current-commissions"), DAY, null, 1,
                        money("1"), money("0"), noFees, Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.sell(id("null-sell-clean-commissions"), DAY, current, 1,
                        null, money("0"), noFees, Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.sell(id("null-sell-accrued-commissions"), DAY, current, 1,
                        money("1"), null, noFees, Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> OPERATIONS.sell(id("null-sell-tax-commissions"), DAY, current, 1,
                        money("1"), money("0"), noFees, null));
    }

    private static LedgerState stateWith(LedgerChart chart, OperationResult result, Map<Account, Money> balances) {
        TreeMap<LocalDate, List<Posting>> history = new TreeMap<>();
        history.put(result.date(), result.postings());
        return new LedgerState(PORTFOLIO, chart, true, balances, Map.of(), history, Set.of(result.operation()));
    }

    private static OperationResult operation(OperationResult.OperationKind kind, String name,
                                             List<Posting.Entry> entries) {
        OperationId operation = id(name);
        Posting posting = new Posting(operation, DAY, entries);
        return new OperationResult(kind, operation, DAY, List.of(posting), Optional.empty(), Optional.empty());
    }

    @FunctionalInterface
    private interface CashOperationFactory {
        OperationResult create(OperationId operation, LocalDate date, PortfolioId portfolio,
                               Currency currency, Money amount);
    }

    @FunctionalInterface
    private interface CorrectionFactory {
        OperationResult create(OperationId operation, LocalDate date, PortfolioId portfolio,
                               OperationId originalOperation, Money adjustment);
    }

    private static Account flat(AccountCode code) {
        return new Account(PORTFOLIO, code, Optional.empty(), RUB);
    }

    private static Account security(AccountCode code, SecurityId security) {
        return new Account(PORTFOLIO, code, Optional.of(security), RUB);
    }

    private static BondAccountingPolicy policy(String... values) {
        List<AccountCode> codes = Arrays.stream(values).map(AccountCode::of).toList();
        return new BondAccountingPolicy(codes.get(0), codes.get(1), codes.get(2), codes.get(3), codes.get(4),
                codes.get(5), codes.get(6), codes.get(7), codes.get(8));
    }

    private static BondAccountingPolicy policyWithNullSlot(AccountCode[] codes, int nullSlot) {
        AccountCode[] withNull = codes.clone();
        withNull[nullSlot] = null;
        return new BondAccountingPolicy(withNull[0], withNull[1], withNull[2], withNull[3], withNull[4],
                withNull[5], withNull[6], withNull[7], withNull[8]);
    }

    private static LedgerChart standardChart() {
        return STANDARD.chart();
    }

    private static Set<AccountCode> known() {
        return Set.of(CASH, EQUITY, SECURITIES, ACCRUED, TAX, PASSIVE, SPENDABLE_2);
    }

    private static Map<AccountCode, PositionMeasure> bindings() {
        return Map.of(SECURITIES, PositionMeasure.CARRYING, ACCRUED, PositionMeasure.PURCHASED_ACCRUED);
    }

    private static LedgerChart chart(Set<AccountCode> known, Set<AccountCode> spendable,
                                     AccountCode adjustable, AccountCode counterpart,
                                     Set<AccountCode> noNegative, Set<AccountCode> scoped,
                                     Map<AccountCode, PositionMeasure> bindings) {
        return new LedgerChart(known, spendable, adjustable, counterpart, noNegative, scoped, bindings);
    }

    private static void assertInvalid(Supplier<LedgerChart> chart) {
        assertThrows(IllegalArgumentException.class, () -> chart.get());
    }

    private static Money money(String value) {
        return new Money(new BigDecimal(value), RUB);
    }

    private static OperationId id(String value) {
        return new OperationId(value);
    }
}
