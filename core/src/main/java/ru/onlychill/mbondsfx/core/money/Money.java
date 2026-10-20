package ru.onlychill.mbondsfx.core.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/** An amount in a single currency. Construction preserves the supplied precision. */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {
    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException("Currency without a minor-unit scale is not supported");
        }
        amount = amount.stripTrailingZeros();
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public static Money of(Currency currency, long majorUnits) {
        return new Money(BigDecimal.valueOf(majorUnits), currency);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    /** Exact multiplication; no currency-scale rounding is applied. */
    public Money multiply(long factor) {
        return new Money(amount.multiply(BigDecimal.valueOf(factor)), currency);
    }

    /** Multiplies once, then rounds to the currency's minor-unit scale using HALF_UP. */
    public Money multiply(BigDecimal factor) {
        Objects.requireNonNull(factor, "factor");
        return new Money(amount.multiply(factor).setScale(currencyScale(), RoundingMode.HALF_UP), currency);
    }

    public Money divide(long divisor) {
        return divide(BigDecimal.valueOf(divisor));
    }

    public Money divide(BigDecimal divisor) {
        Objects.requireNonNull(divisor, "divisor");
        return new Money(amount.divide(divisor, currencyScale(), RoundingMode.HALF_UP), currency);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private int currencyScale() {
        return Math.max(0, currency.getDefaultFractionDigits());
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " and " + other.currency);
        }
    }
}
