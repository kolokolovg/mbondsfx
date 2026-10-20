package ru.onlychill.mbondsfx.core.bond.calculation;

import java.util.Objects;
import java.util.Optional;
import ru.onlychill.mbondsfx.core.money.DayCount;
import ru.onlychill.mbondsfx.core.money.Money;
import ru.onlychill.mbondsfx.core.bond.DatedPeriod;

public record AccruedInterest(Money amount, Optional<DatedPeriod> period, DayCount dayCount) {
    public AccruedInterest {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(dayCount, "dayCount");
    }
}
