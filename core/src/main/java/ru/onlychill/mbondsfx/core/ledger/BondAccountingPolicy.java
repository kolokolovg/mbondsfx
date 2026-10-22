package ru.onlychill.mbondsfx.core.ledger;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bond-domain account dictionary and its chart roles. */
public record BondAccountingPolicy(
        AccountCode cash,
        AccountCode equity,
        AccountCode securities,
        AccountCode accruedInterest,
        AccountCode couponIncome,
        AccountCode amortizationGain,
        AccountCode realizedPnl,
        AccountCode taxWithheld,
        AccountCode commissionExpense) {

    public BondAccountingPolicy {
        Objects.requireNonNull(cash, "cash");
        Objects.requireNonNull(equity, "equity");
        Objects.requireNonNull(securities, "securities");
        Objects.requireNonNull(accruedInterest, "accruedInterest");
        Objects.requireNonNull(couponIncome, "couponIncome");
        Objects.requireNonNull(amortizationGain, "amortizationGain");
        Objects.requireNonNull(realizedPnl, "realizedPnl");
        Objects.requireNonNull(taxWithheld, "taxWithheld");
        Objects.requireNonNull(commissionExpense, "commissionExpense");
        List<AccountCode> codes = List.of(cash, equity, securities, accruedInterest, couponIncome,
                amortizationGain, realizedPnl, taxWithheld, commissionExpense);
        if (new LinkedHashSet<>(codes).size() != codes.size()) {
            throw new IllegalArgumentException("Bond account codes must be pairwise distinct");
        }
    }

    public static BondAccountingPolicy standard() {
        return new BondAccountingPolicy(
                AccountCode.of("CASH"),
                AccountCode.of("EQUITY"),
                AccountCode.of("SECURITIES"),
                AccountCode.of("ACCRUED_INTEREST"),
                AccountCode.of("COUPON_INCOME"),
                AccountCode.of("AMORTIZATION_GAIN"),
                AccountCode.of("REALIZED_PNL"),
                AccountCode.of("TAX_WITHHELD"),
                AccountCode.of("COMMISSION_EXPENSE"));
    }

    public LedgerChart chart() {
        return new LedgerChart(
                Set.of(cash, equity, securities, accruedInterest, couponIncome,
                        amortizationGain, realizedPnl, taxWithheld, commissionExpense),
                Set.of(cash), taxWithheld, cash, Set.of(cash), Set.of(securities, accruedInterest),
                Map.of(securities, PositionMeasure.CARRYING,
                        accruedInterest, PositionMeasure.PURCHASED_ACCRUED));
    }

    public BondOperations operations() {
        return new BondOperations(this);
    }
}
