package ru.onlychill.mbondsfx.core.bond;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import ru.onlychill.mbondsfx.core.id.IndexId;

/** Issue-supplied ratio indexation rule; baseDate records the date for baseIndexValue. */
public record IndexationRule(IndexId index, BigDecimal baseIndexValue, LocalDate baseDate,
                             long observationLagDays) {
    public IndexationRule {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(baseIndexValue, "baseIndexValue");
        Objects.requireNonNull(baseDate, "baseDate");
        if (baseIndexValue.signum() <= 0) {
            throw new IllegalArgumentException("baseIndexValue must be positive");
        }
        if (observationLagDays < 0) {
            throw new IllegalArgumentException("observationLagDays must be non-negative");
        }
    }

    public LocalDate observationDate(LocalDate on) {
        return Objects.requireNonNull(on, "on").minusDays(observationLagDays);
    }
}
