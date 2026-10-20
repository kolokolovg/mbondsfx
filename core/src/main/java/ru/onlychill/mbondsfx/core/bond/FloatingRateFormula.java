package ru.onlychill.mbondsfx.core.bond;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import ru.onlychill.mbondsfx.core.id.IndexId;
import ru.onlychill.mbondsfx.core.money.DayCount;

/** Single-reset floating rate; one exact-date fixing is used for the whole coupon period. */
public record FloatingRateFormula(
        IndexId index,
        long fixingOffsetDays,
        BigDecimal spread,
        DayCount dayCount,
        Optional<BigDecimal> floor,
        Optional<BigDecimal> cap) {
    public FloatingRateFormula {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(spread, "spread");
        Objects.requireNonNull(dayCount, "dayCount");
        Objects.requireNonNull(floor, "floor");
        Objects.requireNonNull(cap, "cap");
        if (floor.isPresent() && cap.isPresent()
                && floor.orElseThrow().compareTo(cap.orElseThrow()) > 0) {
            throw new IllegalArgumentException("floor must not exceed cap");
        }
    }
}
