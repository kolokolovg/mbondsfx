package ru.onlychill.mbondsfx.core.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import ru.onlychill.mbondsfx.core.id.OperationId;
import ru.onlychill.mbondsfx.core.id.PortfolioId;
import ru.onlychill.mbondsfx.core.id.PositionId;
import ru.onlychill.mbondsfx.core.id.SecurityId;
import ru.onlychill.mbondsfx.core.money.Money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Package-level invariants that only crafted or restored inputs can reach: position snapshots that
 * disagree with posted assets, restored accounts outside the chart, and tax correction dating.
 */
final class LedgerInvariantContractTests {
    private static final Currency RUB = Currency.getInstance("RUB");
    private static final LocalDate DAY1 = LocalDate.of(2026, 1, 1);
    private static final PortfolioId PF = new PortfolioId("invariant-portfolio");
    private static final SecurityId SEC = new SecurityId("INVARIANT-SEC");
    private static final BondAccountingPolicy POLICY = BondAccountingPolicy.standard();
    private static final LedgerChart CHART = POLICY.chart();
    private static final BondOperations OPS = POLICY.operations();

    @Test
    void applyRejectsPositionSnapshotThatDisagreesWithPostedAssets() {
        Ledger ledger = Ledger.open(PF, CHART, false).apply(OPS.opening(id("open"), DAY1, PF, RUB, m("10000")));
        OperationResult purchase = OPS.buy(id("buy"), DAY1.plusDays(1), Position.empty(positionId(), RUB),
                2, m("100"), m("0"), m("1000"), m("0"));
        Ledger honest = ledger.apply(purchase);
        assertEquals(purchase.positionAfter().orElseThrow(), honest.position(SEC).orElseThrow(),
                "honest snapshot agrees with posted securities");

        // The securities posting carries 200 of carrying value; the snapshot claims 300.
        Position inflated = new Position(positionId(), 2, m("300"), m("2000"), m("0"));
        OperationResult tampered = new OperationResult(purchase.kind(), purchase.operation(), purchase.date(),
                purchase.postings(), purchase.positionBefore(), Optional.of(inflated));
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> ledger.apply(tampered));
        assertEquals("Position assets disagree with ledger balances for " + SEC, thrown.getMessage());
    }

    @Test
    void restoreReportsUnknownAndMisscopedBalanceAccountsBeforeReplayingHistory() {
        Account ghost = new Account(PF, AccountCode.of("GHOST"), Optional.empty(), RUB);
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> Ledger.restore(stateWithBalance(ghost, m("10"))));
        assertEquals("Invalid ledger state: Unknown account code GHOST", unknown.getMessage());

        Account unscopedSecurities = new Account(PF, POLICY.securities(), Optional.empty(), RUB);
        IllegalArgumentException misscoped = assertThrows(IllegalArgumentException.class,
                () -> Ledger.restore(stateWithBalance(unscopedSecurities, m("10"))));
        assertEquals("Invalid ledger state: Account security scoping differs from chart", misscoped.getMessage());
    }

    @Test
    void taxCorrectionIsAcceptedWhenOriginalSharesTheOnlyHistoryDate() {
        OperationId coupon = id("coupon");
        Ledger ledger = Ledger.open(PF, CHART, false).apply(OPS.opening(id("open"), DAY1, PF, RUB, m("1000")));
        OperationResult purchase = OPS.buy(id("buy"), DAY1, Position.empty(positionId(), RUB),
                1, m("100"), m("0"), m("100"), m("0"));
        ledger = ledger.apply(purchase);
        OperationResult taxedCoupon = OPS.coupon(coupon, DAY1, purchase.positionAfter().orElseThrow(),
                m("10"), Optional.of(m("1")));
        ledger = ledger.apply(taxedCoupon);

        // Every earlier operation shares the coupon's date, so the coupon is present in each history bucket.
        Ledger corrected = ledger.apply(OPS.taxCorrection(id("fix"), DAY1.plusDays(1), PF, coupon, m("-0.50")));
        assertEquals(m("0.50"), corrected.taxWithheldForOperation(coupon),
                "correction is accepted against the only dated history");
    }

    private static LedgerState stateWithBalance(Account account, Money amount) {
        return new LedgerState(PF, CHART, false, Map.of(account, amount), Map.of(), new TreeMap<>(), Set.of());
    }

    private static PositionId positionId() {
        return new PositionId(PF, SEC);
    }

    private static OperationId id(String value) {
        return new OperationId(value);
    }

    private static Money m(String value) {
        return new Money(new BigDecimal(value), RUB);
    }
}
