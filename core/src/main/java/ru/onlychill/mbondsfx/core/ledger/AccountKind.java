package ru.onlychill.mbondsfx.core.ledger;

public enum AccountKind {
    CASH,
    SECURITIES,
    ACCRUED_INTEREST,
    COUPON_INCOME,
    AMORTIZATION_GAIN,
    REALIZED_PNL,
    TAX_WITHHELD,
    COMMISSION_EXPENSE,
    EQUITY;

    public boolean requiresSecurity() {
        return this == SECURITIES || this == ACCRUED_INTEREST;
    }
}
