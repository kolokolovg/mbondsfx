package ru.onlychill.mbondsfx.application.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;
import ru.onlychill.mbondsfx.core.money.Money;

/** Explicitly caller-attributed unpaid tax debt and key rate over a half-open date interval. */
public record PenaltyInterval(LocalDate fromInclusive, LocalDate toExclusive,
                              Money applicableDebt, BigDecimal annualKeyRate) {
    private static final Currency RUB = Currency.getInstance("RUB");

    public PenaltyInterval {
        Objects.requireNonNull(fromInclusive, "fromInclusive");
        Objects.requireNonNull(toExclusive, "toExclusive");
        Objects.requireNonNull(applicableDebt, "applicableDebt");
        Objects.requireNonNull(annualKeyRate, "annualKeyRate");
        if (!fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("fromInclusive must precede toExclusive");
        }
        if (!RUB.equals(applicableDebt.currency())) {
            throw new IllegalArgumentException("Tax penalty estimate supports RUB debt only");
        }
        if (applicableDebt.isNegative()) {
            throw new IllegalArgumentException("applicableDebt must not be negative");
        }
        if (annualKeyRate.signum() < 0) {
            throw new IllegalArgumentException("annualKeyRate must not be negative");
        }
    }
}
